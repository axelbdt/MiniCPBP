/*
 * mini-cpbp: the effective factor graph of the current search node, and the
 * static schedules derived from it (BP_SCHEDULING.md sections 2.1, 2.2;
 * BP_SCHEDULING_TODO.md).
 *
 * "Effective" is the operative word. The graph the historical sweep walks is
 * the one posted at the root: every constraint, every variable, every sweep.
 * The graph that actually carries information at a search node is smaller in
 * three ways, and all three are exact, not heuristic:
 *
 *   - an inactive (entailed) constraint carries nothing;
 *   - a bound variable carries nothing between its constraints. Its
 *     variable-to-constraint message is the same delta whatever the other
 *     constraints believe (AbstractConstraint.receiveMessages sends ONE for a
 *     bound variable), so a bound variable is a cut point of the graph, not an
 *     edge of it. Search therefore decomposes the factor graph as it descends;
 *   - a constraint with at most one unbound variable in its scope has no
 *     incoming message that can change during one BP invocation, so its
 *     outgoing message is constant: computing it once per invocation is exact,
 *     and every later recomputation is waste. Call such a factor frozen.
 *
 * On top of that structure this class provides
 *
 *   - a spanning forest and the two orders that go with it, leaves-to-root and
 *     root-to-leaves. On an acyclic component those two passes are exact BP and
 *     no further sweep changes anything. On a cyclic component they realise the
 *     objective Infer.NET's scheduler optimises -- one stale ("backward") edge
 *     per independent cycle, which is the minimum possible -- in linear time
 *     instead of its greedy cycle-cost search;
 *   - the two-core, i.e. which part of the graph is genuinely cyclic, so that
 *     the acyclic appendages can be paid for once;
 *   - dirty tracking at the granularity at which MiniCPBP executes a factor
 *     (one updateBelief() computes every outgoing message of a constraint),
 *     with the exact rule: a change of variable y dirties factor F only if F
 *     has an unbound variable other than y, because F's message to y does not
 *     depend on the message from y;
 *   - query reachability from the declared branching variables, so that work
 *     which cannot reach a search decision can be dropped (Infer.NET's
 *     OptimiseForVariables);
 *   - exact resynchronisation of a marginal from the messages it receives.
 *
 * The graph is rebuilt at every real BP invocation. That is deliberate: the
 * decomposition is the payload, and it changes at every node. The rebuild is
 * linear in the size of the graph while one sweep runs a weighted-counting
 * algorithm per factor, so the ratio is small; it is measured all the same
 * (BPStats.scheduleNanos).
 */

package minicpbp.engine.core;

import minicpbp.util.BPStats;

import java.util.IdentityHashMap;
import java.util.Iterator;

final class BPGraph implements MarginalResync {

    private final Solver cp;

    /* ---- variable identity ------------------------------------------------
     * A view and the variable it views share their marginals, so they are one
     * node of the factor graph: identity is taken on getBaseVar().
     */
    private final IdentityHashMap<IntVar, Integer> idOfVar = new IdentityHashMap<>();
    private int nIds;
    private int[] slotOfId = new int[64];   // compact index in this rebuild
    private int[] stampOfId = new int[64];  // validity stamp for slotOfId
    private int stamp;

    /* ---- the effective graph ---------------------------------------------- */
    private Constraint[] factor = new Constraint[64];
    private int nFactors;
    /** CSR over factors into scopeVar: the distinct unbound variables of the scope */
    private int[] scopeStart = new int[65];
    private int[] scopeVar = new int[256];
    private int nScope;
    /** owner factor of each entry of scopeVar */
    private int[] scopeFactor = new int[256];
    /** position of the variable in the owner's own scope, for its message residual */
    private int[] scopePos = new int[256];

    private IntVar[] varOf = new IntVar[64];   // compact index -> base variable
    private int nVars;
    /** CSR over variables into incFactor: the factors that hold the variable */
    private int[] incStart = new int[65];
    private int[] incFactor = new int[256];

    /* ---- derived schedule ------------------------------------------------- */
    private int[] order = new int[64];      // factors, spanning-forest discovery order
    /** probe Q (amendment 13): per-invocation hint — root the forest here */
    private IntVar preferredRoot;
    /** end of the preferred root's component in {@link #order}: the factors in
     *  order[0..rootComponentEnd) are exactly that component when the hint was
     *  consumed, and rootComponentEnd == nFactors otherwise, so a prefix
     *  iteration degrades to the whole forest. Consumed by the inward schedule */
    private int rootComponentEnd;
    private boolean[] frozen = new boolean[64];
    private boolean[] pruned = new boolean[64];
    private boolean[] inCore = new boolean[64];
    private int nComponents;
    private int nCoreFactors;

    /* ---- dirty tracking --------------------------------------------------- */
    private boolean[] dirty = new boolean[64];
    private int nDirty;

    /* ---- scratch ---------------------------------------------------------- */
    private int[] queue = new int[64];
    private int[] varDeg = new int[64];
    private int[] facDeg = new int[64];
    private boolean[] varSeen = new boolean[64];
    private boolean[] facSeen = new boolean[64];
    private int[] seenAt = new int[64];      // scope de-duplication stamp
    private int seenStamp;

    BPGraph(Solver cp) {
        this.cp = cp;
    }

    /* ====================================================================== */
    /* construction                                                            */
    /* ====================================================================== */

    /**
     * Rebuilds the effective graph and its schedule from the current search
     * state. Returns the number of factors that are worth executing.
     */
    /** probe Q (amendment 13): hint consumed by the next {@link #computeOrder};
     *  only read when {@code minicpbp.bp.rootAtDecision} is set */
    void setPreferredRoot(IntVar v) {
        preferredRoot = v;
    }

    int rebuild(boolean queryOnly) {
        long t0 = System.nanoTime();
        stamp++;
        nFactors = 0;
        nScope = 0;
        nVars = 0;
        Iterator<Constraint> it = cp.getConstraints().iterator();
        while (it.hasNext()) {
            Constraint c = it.next();
            if (!c.isActive()) continue;
            int f = nFactors++;
            factor = grow(factor, nFactors);
            scopeStart = grow(scopeStart, nFactors + 1);
            factor[f] = c;
            scopeStart[f] = nScope;
            seenStamp++;
            IntVar[] scope = c.getScope();
            for (int i = 0; i < scope.length; i++) {
                if (scope[i].isBound()) continue;
                int v = slotOf(scope[i].getBaseVar());
                if (seenAt[v] == seenStamp) continue; // two views of one variable
                seenAt[v] = seenStamp;
                scopeVar = grow(scopeVar, nScope + 1);
                scopeFactor = grow(scopeFactor, nScope + 1);
                scopePos = grow(scopePos, nScope + 1);
                scopeVar[nScope] = v;
                scopeFactor[nScope] = f;
                scopePos[nScope] = i;
                nScope++;
            }
            scopeStart[nFactors] = nScope;
        }
        buildIncidence();
        sizeSchedule();
        markFrozen();
        computeCore();
        computeOrder();
        if (queryOnly) prune();
        BPStats.schedulesBuilt++;
        BPStats.lastFactors = nFactors;
        BPStats.lastVars = nVars;
        BPStats.lastCyclicFactors = nCoreFactors;
        BPStats.lastAcyclicFactors = nFactors - nCoreFactors;
        BPStats.lastComponents = nComponents;
        BPStats.sumFactors += nFactors;
        BPStats.sumMessageNodes += nScope;
        BPStats.sumAcyclicFactors += nFactors - nCoreFactors;
        BPStats.sumComponents += nComponents;
        for (int f = 0; f < nFactors; f++) {
            if (frozen[f]) BPStats.sumFrozenFactors++;
            if (pruned[f]) BPStats.sumPrunedFactors++;
        }
        BPStats.scheduleNanos += System.nanoTime() - t0;
        return nFactors;
    }

    private int slotOf(IntVar base) {
        Integer id = idOfVar.get(base);
        if (id == null) {
            id = nIds++;
            idOfVar.put(base, id);
            slotOfId = grow(slotOfId, nIds);
            stampOfId = grow(stampOfId, nIds);
        }
        int i = id;
        if (stampOfId[i] != stamp) {
            stampOfId[i] = stamp;
            slotOfId[i] = nVars++;
            varOf = grow(varOf, nVars);
            varOf[nVars - 1] = base;
            seenAt = grow(seenAt, nVars);
            varDeg = grow(varDeg, nVars);
            varSeen = grow(varSeen, nVars);
            incStart = grow(incStart, nVars + 1);
        }
        return slotOfId[i];
    }

    private void buildIncidence() {
        for (int v = 0; v <= nVars; v++) incStart[v] = 0;
        for (int k = 0; k < nScope; k++) incStart[scopeVar[k] + 1]++;
        for (int v = 0; v < nVars; v++) incStart[v + 1] += incStart[v];
        incFactor = grow(incFactor, nScope);
        int[] fill = queue = grow(queue, Math.max(nVars + 1, nFactors + 1));
        for (int v = 0; v <= nVars; v++) fill[v] = incStart[v];
        for (int k = 0; k < nScope; k++) {
            incFactor[fill[scopeVar[k]]++] = scopeFactor[k];
        }
    }

    private void sizeSchedule() {
        order = grow(order, nFactors);
        frozen = grow(frozen, nFactors);
        pruned = grow(pruned, nFactors);
        inCore = grow(inCore, nFactors);
        dirty = grow(dirty, nFactors);
        facDeg = grow(facDeg, nFactors);
        facSeen = grow(facSeen, nFactors);
        queue = grow(queue, Math.max(nFactors, nVars) + 1);
    }

    private void markFrozen() {
        for (int f = 0; f < nFactors; f++) {
            frozen[f] = dynArity(f) <= 1;
            // every variable of the scope is bound: the factor has no marginal
            // left to write, so its weighted counting is pure waste. The
            // flooding sweep runs it anyway, once per sweep.
            pruned[f] = dynArity(f) == 0;
        }
    }

    /**
     * Peels the graph down to its two-core: every acyclic appendage is
     * repeatedly stripped by removing variables held by at most one remaining
     * factor and factors holding at most one remaining variable. What survives
     * carries every cycle, so BP outside it needs no iteration at all.
     */
    private void computeCore() {
        for (int v = 0; v < nVars; v++) {
            varDeg[v] = incStart[v + 1] - incStart[v];
            varSeen[v] = false;
        }
        for (int f = 0; f < nFactors; f++) {
            facDeg[f] = dynArity(f);
            facSeen[f] = false;
            inCore[f] = true;
        }
        int head = 0, tail = 0;
        int[] q = queue = grow(queue, nFactors + nVars + 2);
        // seed: the leaves of the graph, on both sides
        for (int f = 0; f < nFactors; f++)
            if (facDeg[f] <= 1 && !facSeen[f]) { facSeen[f] = true; q[tail++] = f; }
        for (int v = 0; v < nVars; v++)
            if (varDeg[v] <= 1 && !varSeen[v]) { varSeen[v] = true; q[tail++] = nFactors + v; }
        while (head < tail) {
            int n = q[head++];
            if (n < nFactors) {
                int f = n;
                inCore[f] = false;
                for (int k = scopeStart[f]; k < scopeStart[f + 1]; k++) {
                    int v = scopeVar[k];
                    if (varSeen[v]) continue;
                    if (--varDeg[v] <= 1) { varSeen[v] = true; q[tail++] = nFactors + v; }
                }
            } else {
                int v = n - nFactors;
                for (int j = incStart[v]; j < incStart[v + 1]; j++) {
                    int f = incFactor[j];
                    if (facSeen[f]) continue;
                    if (--facDeg[f] <= 1) { facSeen[f] = true; q[tail++] = f; }
                }
            }
        }
        nCoreFactors = 0;
        for (int f = 0; f < nFactors; f++) if (inCore[f]) nCoreFactors++;
    }

    /**
     * Breadth-first spanning forest of the effective graph, factors in
     * discovery order. Reading the order backwards is a valid leaves-to-root
     * pass and reading it forwards a valid root-to-leaves pass: on an acyclic
     * component the two together are exact belief propagation, and on a cyclic
     * one they leave one stale edge per independent cycle, which is the
     * minimum a sequential schedule can leave (the quantity Infer.NET's
     * scheduler minimises, here obtained from the forest instead of a greedy
     * search over cycles).
     *
     * Roots are taken in posting order so the schedule is deterministic.
     */
    private void computeOrder() {
        for (int f = 0; f < nFactors; f++) facSeen[f] = false;
        for (int v = 0; v < nVars; v++) varSeen[v] = false;
        int n = 0;
        nComponents = 0;
        rootComponentEnd = -1;
        // Probe Q (amendment 13): when a preferred root variable is hinted,
        // discover its component from it first. Reading the order backwards
        // (the odd, inward sweep) then executes that component leaves-to-THIS-
        // root, so the root's incident factors read fresh messages from their
        // whole component: the collect phase for the one marginal the decision
        // consumes. Other components and the flag-off path are unchanged.
        if (minicpbp.util.BPConfig.ROOT_AT_DECISION && preferredRoot != null) {
            IntVar base = preferredRoot.getBaseVar();
            for (int v = 0; v < nVars; v++) {
                if (varOf[v] != base) continue;
                int head = n;
                varSeen[v] = true;
                for (int j = incStart[v]; j < incStart[v + 1]; j++) {
                    int g = incFactor[j];
                    if (facSeen[g]) continue;
                    facSeen[g] = true;
                    order[n++] = g;
                }
                if (n > head) {
                    nComponents++;
                    n = bfsExpand(head, n);
                    rootComponentEnd = n;
                }
                break;
            }
        }
        for (int root = 0; root < nFactors; root++) {
            if (facSeen[root]) continue;
            nComponents++;
            int head = n;
            facSeen[root] = true;
            order[n++] = root;
            n = bfsExpand(head, n);
        }
        assert n == nFactors;
        // no hint, hint off, or the hinted variable bound/absent: the "root
        // component" is the whole forest, so a consumer of the prefix loses
        // nothing relative to a full inward sweep
        if (rootComponentEnd < 0) rootComponentEnd = nFactors;
    }

    /** BFS expansion of {@link #computeOrder}: consume order[head..n) and
     *  append newly discovered factors; returns the new n. */
    private int bfsExpand(int head, int n) {
        while (head < n) {
            int f = order[head++];
            for (int k = scopeStart[f]; k < scopeStart[f + 1]; k++) {
                int v = scopeVar[k];
                if (varSeen[v]) continue;
                varSeen[v] = true;
                for (int j = incStart[v]; j < incStart[v + 1]; j++) {
                    int g = incFactor[j];
                    if (facSeen[g]) continue;
                    facSeen[g] = true;
                    order[n++] = g;
                }
            }
        }
        return n;
    }

    /**
     * Infer.NET's OptimiseForVariables, transposed: the outputs of inference
     * are the marginals of the declared branching variables, so a factor from
     * which no message can reach one of them computes nothing search can use.
     * Reachability is taken over the effective graph, which is exactly what
     * "can affect" means for belief propagation; note that a variable being a
     * non-query variable is NOT a reason to prune anything, since its messages
     * mediate between queries (BP_SCHEDULING_TODO.md).
     */
    private void prune() {
        for (int f = 0; f < nFactors; f++) facSeen[f] = false;
        for (int v = 0; v < nVars; v++) varSeen[v] = false;
        int[] q = queue;
        int head = 0, tail = 0;
        for (int v = 0; v < nVars; v++) {
            if (!varOf[v].isForBranching()) continue;
            varSeen[v] = true;
            q[tail++] = v;
        }
        while (head < tail) {
            int v = q[head++];
            for (int j = incStart[v]; j < incStart[v + 1]; j++) {
                int f = incFactor[j];
                if (facSeen[f]) continue;
                facSeen[f] = true;
                for (int k = scopeStart[f]; k < scopeStart[f + 1]; k++) {
                    int w = scopeVar[k];
                    if (varSeen[w]) continue;
                    varSeen[w] = true;
                    q[tail++] = w;
                }
            }
        }
        for (int f = 0; f < nFactors; f++) {
            pruned[f] |= !facSeen[f];
        }
    }

    /* ====================================================================== */
    /* accessors                                                               */
    /* ====================================================================== */

    int factorCount() {
        return nFactors;
    }

    Constraint factorAt(int f) {
        return factor[f];
    }

    /** factors in spanning-forest discovery order */
    int orderAt(int i) {
        return order[i];
    }

    /** see {@link #rootComponentEnd}: order[0..rootComponentEnd) is the
     *  preferred root's component, or the whole forest without a hint */
    int rootComponentEnd() {
        return rootComponentEnd;
    }

    int dynArity(int f) {
        return scopeStart[f + 1] - scopeStart[f];
    }

    boolean isFrozen(int f) {
        return frozen[f];
    }

    boolean isPruned(int f) {
        return pruned[f];
    }

    boolean isInCore(int f) {
        return inCore[f];
    }

    int componentCount() {
        return nComponents;
    }

    int coreFactorCount() {
        return nCoreFactors;
    }

    int varCount() {
        return nVars;
    }

    /* the effective graph, as adjacency ranges: the schedulers walk these */

    int scopeBegin(int f) {
        return scopeStart[f];
    }

    int scopeEnd(int f) {
        return scopeStart[f + 1];
    }

    int scopeVarAt(int k) {
        return scopeVar[k];
    }

    /** position, in the owning constraint's own scope, of the variable of entry k */
    int scopePosAt(int k) {
        return scopePos[k];
    }

    int incBegin(int v) {
        return incStart[v];
    }

    int incEnd(int v) {
        return incStart[v + 1];
    }

    int incFactorAt(int j) {
        return incFactor[j];
    }

    /* ====================================================================== */
    /* dirty tracking                                                          */
    /* ====================================================================== */

    /** every factor that can still produce a new message is dirty */
    void dirtyAll() {
        nDirty = 0;
        for (int f = 0; f < nFactors; f++) {
            dirty[f] = !pruned[f];
            if (dirty[f]) nDirty++;
        }
        BPStats.dirtySeeded += nDirty;
        BPStats.dirtySeedFactors += nFactors;
        BPStats.dirtySeedFull++;
    }

    /**
     * Seeds the dirty set from what actually changed, instead of from
     * everything (BP_WARM_START_EXPERIMENT.md section 1.3). Sound only because
     * a warm start keeps the stored messages: skipping factor F is exact iff
     * F's stored {@code localBelief} is the message F would compute from its
     * current inputs, and F's inputs are the cavities and the domains of its
     * scope. So F must be seeded iff
     * <ul>
     * <li>some scope variable changed since the last invocation on this path —
     * its domain shrank, or a factor holding it was deactivated and the entry
     * rebuild has divided that factor out of its marginal; or</li>
     * <li>the previous invocation on this path ended before executing F. That
     * set is <em>not</em> recoverable from this class: {@code rebuild}
     * renumbers everything and forgets the dirty bits, so it is kept on the
     * constraint, trailed. Omitting it would be unsound, not merely
     * imprecise.</li>
     * </ul>
     * The first invocation on a path (nothing computed yet) seeds everything.
     */
    void dirtyChanged(boolean forceFull) {
        int pathEpoch = cp.bpPathEpoch();
        if (forceFull || pathEpoch <= 0) {
            dirtyAll();
            return;
        }
        nDirty = 0;
        for (int f = 0; f < nFactors; f++) dirty[f] = false;
        for (int f = 0; f < nFactors; f++) {
            Constraint c = factor[f];
            if (c.bpStale()) {
                makeDirty(f);
                continue;
            }
            // The WHOLE declared scope, bound variables included, and not the
            // effective scope: rebuild() drops a bound variable from the graph,
            // and binding a variable is exactly what changes this factor's
            // messages to the rest of its scope. Walking incidence instead
            // would silently miss every factor of the variable the last
            // decision assigned. O(sum arity), the same order as rebuild's own
            // loop.
            IntVar[] scope = c.getScope();
            for (int i = 0; i < scope.length; i++) {
                // >= and not >: the epoch is incremented inside the invocation,
                // so the removals made by the decision and the propagation that
                // follow it carry the epoch this watermark holds
                if (scope[i].getBaseVar().bpTouchStamp() >= pathEpoch) {
                    makeDirty(f);
                    break;
                }
            }
        }
        BPStats.dirtySeeded += nDirty;
        BPStats.dirtySeedFactors += nFactors;
    }

    /**
     * Records, for the next invocation on this path, which factors this one
     * left un-executed. Called at the end of an invocation, and only under
     * incremental seeding: it is a trailed write per factor whose state
     * changed, which buys nothing when the next invocation is going to seed
     * everything anyway.
     */
    void persistStale() {
        for (int f = 0; f < nFactors; f++) {
            // a pruned factor is never executed, so it stays stale: it must run
            // if it ever stops being pruned. It also starts stale, so this
            // writes nothing for it
            factor[f].setBpStale(dirty[f] || pruned[f]);
        }
    }

    boolean isDirty(int f) {
        return dirty[f];
    }

    int dirtyCount() {
        return nDirty;
    }

    void clean(int f) {
        if (dirty[f]) {
            dirty[f] = false;
            nDirty--;
        }
    }

    void makeDirty(int f) {
        if (!dirty[f] && !pruned[f]) {
            dirty[f] = true;
            nDirty++;
        }
    }

    /**
     * Propagates the consequence of factor {@code f} having changed the message
     * it sends to the variables of its scope. A factor G is dirtied by a change
     * of variable y only if it has an unbound variable other than y: its
     * message to y is a function of its other incoming messages, never of the
     * message from y, so a frozen factor is never dirtied by anything and runs
     * exactly once per invocation.
     */
    void spreadDirty(int f, double tol) {
        Constraint c = factor[f];
        for (int k = scopeStart[f]; k < scopeStart[f + 1]; k++) {
            if (c.messageResidual(scopePos[k]) <= tol) continue; // that message did not move
            int v = scopeVar[k];
            for (int j = incStart[v]; j < incStart[v + 1]; j++) {
                int g = incFactor[j];
                if (g == f || frozen[g]) continue;
                makeDirty(g);
            }
        }
    }

    /**
     * Normalises the marginals of every unbound variable of the graph. The
     * in-place update leaves them unnormalised -- normalising once per factor
     * would mean a trailed write per value per incident factor -- so the
     * schedule does it once per sweep, which is exactly what the flooding
     * sweep does, and before the engine reads entropies.
     */
    void normalizeMarginals() {
        long t0 = System.nanoTime();
        for (int v = 0; v < nVars; v++) varOf[v].normalizeMarginals();
        BPStats.normalizeNanos += System.nanoTime() - t0;
        BPStats.normalizeCalls++;
    }

    /* ====================================================================== */
    /* exact marginal resynchronisation                                        */
    /* ====================================================================== */

    /**
     * Rebuilds a marginal as the product of the messages it currently receives,
     * which is what the flooding sweep computes at the end of every iteration.
     * Used where the incremental update cannot recover the cavity distribution
     * by division.
     */
    @Override
    public void resync(IntVar base) {
        Integer id = idOfVar.get(base);
        if (id == null || stampOfId[id] != stamp) {
            // Not a node of the current effective graph, so the product cannot
            // be rebuilt and this only normalises: the marginal is then not the
            // product of the messages it receives. Unreachable in a normal
            // invocation — resync is called on an unbound variable of an active
            // factor of this graph — but if it is reached, the invariant is
            // broken with nothing recording it, and incremental seeding would
            // skip the factors that read this variable. Record it as changed.
            if (minicpbp.util.BPConfig.INCREMENTAL_DIRTY
                    || minicpbp.util.BPConfig.DECISION_TRIGGER) base.bpTouch();
            base.normalizeMarginals();
            return;
        }
        int v = slotOfId[id];
        base.resetMarginals();
        for (int j = incStart[v]; j < incStart[v + 1]; j++) {
            Constraint c = factor[incFactor[j]];
            if (c.isActive()) c.contributeMarginal(base);
        }
        base.normalizeMarginals();
    }

    /* ====================================================================== */
    /* diagnostics                                                             */
    /* ====================================================================== */

    String describe() {
        int frozenCount = 0, prunedCount = 0, scopeSum = 0, maxDeg = 0;
        for (int f = 0; f < nFactors; f++) {
            if (frozen[f]) frozenCount++;
            if (pruned[f]) prunedCount++;
            scopeSum += dynArity(f);
        }
        for (int v = 0; v < nVars; v++) maxDeg = Math.max(maxDeg, incStart[v + 1] - incStart[v]);
        return "factors=" + nFactors + " unboundVars=" + nVars + " messageNodes=" + scopeSum
                + " components=" + nComponents + " coreFactors=" + nCoreFactors
                + " acyclicFactors=" + (nFactors - nCoreFactors)
                + " frozenFactors=" + frozenCount + " prunedFactors=" + prunedCount
                + " maxVarDegree=" + maxDeg;
    }

    /* ====================================================================== */
    /* growable arrays                                                         */
    /* ====================================================================== */

    private static int[] grow(int[] a, int n) {
        if (a.length >= n) return a;
        int[] b = new int[Math.max(n, a.length * 2)];
        System.arraycopy(a, 0, b, 0, a.length);
        return b;
    }

    private static boolean[] grow(boolean[] a, int n) {
        if (a.length >= n) return a;
        boolean[] b = new boolean[Math.max(n, a.length * 2)];
        System.arraycopy(a, 0, b, 0, a.length);
        return b;
    }

    private static Constraint[] grow(Constraint[] a, int n) {
        if (a.length >= n) return a;
        Constraint[] b = new Constraint[Math.max(n, a.length * 2)];
        System.arraycopy(a, 0, b, 0, a.length);
        return b;
    }

    private static IntVar[] grow(IntVar[] a, int n) {
        if (a.length >= n) return a;
        IntVar[] b = new IntVar[Math.max(n, a.length * 2)];
        System.arraycopy(a, 0, b, 0, a.length);
        return b;
    }
}
