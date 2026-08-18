/*
 * mini-cpbp, replacing classic propagation by belief propagation
 *
 * tree constraint (succ encoding) with exact regime-2 weighted counting,
 * round 5, 2026-08-18. See TREE_GAC_DESIGN.md, TREE_EXPERIMENT.md and
 * IMPLEMENTATION_LOG.md.
 *
 * Semantics: tree(succ[0..n-1], ntrees). succ_i = j (j != i) means node i's
 * parent is j; succ_i = i means i is a root. A full assignment is a solution
 * iff the functional graph contains no cycle other than root self-loops
 * (i.e. it is a forest of anti-arborescences) and ntrees equals the number
 * of roots.
 *
 * Filtering (identical across belief arms) — the complete algorithm of
 * Fages & Lorca, "Revisiting the tree constraint", CP 2011 (verified
 * against the stored paper, TREE_GAC_DESIGN.md):
 *   - feasibility: every node reaches a potential root in the envelope
 *     (checked as reachability from the super node of the reversed flow
 *     graph); D(ntrees) pruned to [MINTREE, MAXTREE] with MINTREE = number
 *     of sink SCCs and MAXTREE = number of potential roots;
 *   - bound filtering: ntrees instantiated to MINTREE => potential roots in
 *     non-sink SCCs lose their self-loop; instantiated to MAXTREE => every
 *     potential root is assigned as a root;
 *   - GAC arc filtering (paper Prop. 3): arc (x,y), x != y, removed iff x
 *     dominates y in the reversed flow graph G^-1_ES rooted at the super
 *     node s (arcs s->r for potential roots r) — one dominator tree
 *     (iterative Cooper-Harvey-Kennedy) per propagation call.
 *
 * Beliefs (updateBelief): TreeConfig.BELIEF —
 *   EXACT   directed Matrix-Tree / Matrix-Forest determinant
 *           (TreeMatrixDP). One real adjugate when exactly one potential
 *           root exists (every solution then has ntrees = 1); the complex
 *           DFT count-resolved path when several roots are possible and
 *           n <= TreeConfig.NTREES_MAX_N (also yields the exact belief over
 *           ntrees). Uniform fallback (setExactWCounting(false), never
 *           prunes) above the caps or on numerical failure.
 *   UNIFORM the AbstractConstraint default (control arm).
 */

package minicpbp.engine.constraints;

import minicpbp.engine.core.AbstractConstraint;
import minicpbp.engine.core.IntVar;
import minicpbp.util.TreeConfig;
import minicpbp.util.TreeMatrixDP;
import minicpbp.util.exception.InconsistencyException;

public class Tree extends AbstractConstraint {

    private final IntVar[] succ;
    private final IntVar ntrees;
    private final int n;
    /** fixed root, or -1: when >= 0, post() binds succ[root] = root and
     *  removes every other self-loop (single-arborescence variant). */
    private final int fixedRoot;

    // ----- filtering scratch (n nodes + super node s = n) -----
    // envelope forward adjacency (self-loops excluded)
    private final int[][] adjOut;
    private final int[] adjCnt;
    private final boolean[] rootSnap; // potential roots of the snapshot
    // envelope out-adjacency of the REVERSED flow graph: fgOut[u] holds v
    // for each envelope arc (v,u); fgOut[s] holds the potential roots
    private final int[][] fgOut;
    private final int[] fgCnt;
    // Tarjan SCC (iterative) over the envelope graph
    private final int[] comp, tLow, tNum, tStack, tFrame, tIter;
    private final boolean[] onStack, compSink;
    // dominators (Cooper-Harvey-Kennedy) + dominator-tree Euler intervals
    private final int[] post, rpon, idom, tin, tout, childHead, childNext, euler;

    // ----- counting structures -----
    private final double[][] a;      // n x n collapsed weights
    private final double[][] msg;    // n x n messages
    private final double[] wN;       // ntrees outside belief, 0..n
    private final double[] msgK;     // ntrees messages (coefficients c_k)
    private TreeMatrixDP dp;
    /** duplicate succ vars, or ntrees aliased to a succ var: the counting
     *  factorization is a relaxation; exactness is never claimed. */
    private final boolean aliased;

    public Tree(IntVar[] succ, IntVar ntrees) {
        this(succ, ntrees, -1);
    }

    public Tree(IntVar[] succ, IntVar ntrees, int fixedRoot) {
        super(succ[0].getSolver(), scope(succ, ntrees));
        this.fixedRoot = fixedRoot;
        setName("Tree");
        this.succ = succ;
        this.ntrees = ntrees;
        this.n = succ.length;

        adjOut = new int[n][];
        for (int i = 0; i < n; i++) adjOut[i] = new int[n];
        adjCnt = new int[n];
        rootSnap = new boolean[n];
        fgOut = new int[n + 1][];
        for (int i = 0; i <= n; i++) fgOut[i] = new int[n];
        fgCnt = new int[n + 1];
        comp = new int[n];
        tLow = new int[n];
        tNum = new int[n];
        tStack = new int[n];
        tFrame = new int[n + 1];
        tIter = new int[n + 1];
        onStack = new boolean[n];
        compSink = new boolean[n];
        post = new int[n + 1];
        rpon = new int[n + 1];
        idom = new int[n + 1];
        tin = new int[n + 1];
        tout = new int[n + 1];
        childHead = new int[n + 1];
        childNext = new int[n + 1];
        euler = new int[n + 1];
        a = new double[n][n];
        msg = new double[n][n];
        wN = new double[n + 1];
        msgK = new double[n + 1];

        boolean alias = false;
        for (int i = 0; i < n && !alias; i++) {
            if (succ[i] == ntrees) alias = true;
            for (int i2 = i + 1; i2 < n; i2++)
                if (succ[i] == succ[i2]) { alias = true; break; }
        }
        aliased = alias;
        setExactWCounting(false); // refined per call in updateBelief()
    }

    private static IntVar[] scope(IntVar[] succ, IntVar ntrees) {
        IntVar[] vars = new IntVar[succ.length + 1];
        System.arraycopy(succ, 0, vars, 0, succ.length);
        vars[succ.length] = ntrees;
        return vars;
    }

    @Override
    public void post() {
        for (int i = 0; i < n; i++) {
            succ[i].removeBelow(0);
            succ[i].removeAbove(n - 1);
        }
        ntrees.removeBelow(1); // any functional graph has a cycle; valid => at least one root
        ntrees.removeAbove(n);
        if (fixedRoot >= 0) {
            succ[fixedRoot].assign(fixedRoot);
            for (int i = 0; i < n; i++)
                if (i != fixedRoot && succ[i].contains(i)) succ[i].remove(i);
            ntrees.assign(1);
        }
        switch (getSolver().getMode()) {
            case BP:
                break;
            case SP:
            case SBP:
                for (IntVar var : succ) var.propagateOnDomainChange(this);
                ntrees.propagateOnDomainChange(this);
        }
        propagate();
    }

    /**
     * The complete filtering of Fages & Lorca CP 2011 (TREE_GAC_DESIGN.md).
     * All rule decisions are made against ONE envelope snapshot (the domains
     * at entry); each removal is individually justified on that snapshot, so
     * applying them in sequence is sound, and the solver re-runs propagate()
     * to fixpoint on the shrunk domains.
     */
    @Override
    public void propagate() {
        // ---- envelope snapshot ----
        int nRoots = 0;
        java.util.Arrays.fill(fgCnt, 0, n + 1, 0);
        for (int i = 0; i < n; i++) {
            adjCnt[i] = 0;
            rootSnap[i] = false;
            int s = succ[i].fillArray(domainValues);
            for (int t = 0; t < s; t++) {
                int j = domainValues[t];
                if (j == i) {
                    rootSnap[i] = true;
                    fgOut[n][fgCnt[n]++] = i; // super node s -> potential root
                    nRoots++;
                } else {
                    adjOut[i][adjCnt[i]++] = j;
                    fgOut[j][fgCnt[j]++] = i; // reversed arc of (i, j)
                }
            }
        }
        if (nRoots == 0) throw InconsistencyException.INCONSISTENCY;

        // ---- Tarjan SCC over the envelope (iterative; self-loops excluded) ----
        int nComp = tarjan();
        java.util.Arrays.fill(compSink, 0, nComp, true);
        for (int i = 0; i < n; i++)
            for (int t = 0; t < adjCnt[i]; t++)
                if (comp[i] != comp[adjOut[i][t]]) compSink[comp[i]] = false;
        int minTree = 0;
        for (int c = 0; c < nComp; c++) if (compSink[c]) minTree++;
        int maxTree = nRoots;

        // ---- ntrees window [MINTREE, MAXTREE] ----
        ntrees.removeBelow(minTree);
        ntrees.removeAbove(maxTree);

        // ---- dominators of the reversed flow graph rooted at s = n ----
        // postorder DFS from s; a node not reached cannot reach any potential
        // root in the envelope: infeasible (paper Prop. 2 condition 2)
        java.util.Arrays.fill(rpon, 0, n + 1, -1);
        int cnt = 0;
        int fp = 0;
        tFrame[0] = n;
        tIter[0] = 0;
        rpon[n] = -2;
        while (fp >= 0) {
            int v = tFrame[fp];
            if (tIter[fp] < fgCnt[v]) {
                int w = fgOut[v][tIter[fp]++];
                if (rpon[w] == -1) {
                    rpon[w] = -2;
                    tFrame[++fp] = w;
                    tIter[fp] = 0;
                }
            } else {
                post[cnt++] = v;
                fp--;
            }
        }
        if (cnt < n + 1) throw InconsistencyException.INCONSISTENCY;
        for (int k = 0; k < cnt; k++) rpon[post[k]] = cnt - 1 - k;
        // Cooper-Harvey-Kennedy fixpoint. FG-predecessors of v are exactly
        // D(succ_v) \ {v}, plus s when v is a potential root.
        java.util.Arrays.fill(idom, 0, n + 1, -1);
        idom[n] = n;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int k = cnt - 2; k >= 0; k--) { // reverse postorder, s skipped
                int v = post[k];
                int newIdom = rootSnap[v] ? n : -1;
                int s = succ[v].fillArray(domainValues);
                for (int t = 0; t < s; t++) {
                    int u = domainValues[t];
                    if (u == v || idom[u] == -1) continue;
                    newIdom = (newIdom == -1) ? u : intersect(u, newIdom);
                }
                if (newIdom != -1 && idom[v] != newIdom) {
                    idom[v] = newIdom;
                    changed = true;
                }
            }
        }
        // dominator tree -> Euler intervals for O(1) ancestor tests
        java.util.Arrays.fill(childHead, 0, n + 1, -1);
        for (int k = 0; k < cnt; k++) {
            int v = post[k];
            if (v == n) continue;
            childNext[v] = childHead[idom[v]];
            childHead[idom[v]] = v;
        }
        int timer = 0;
        fp = 0;
        tFrame[0] = n;
        euler[n] = childHead[n];
        tin[n] = timer++;
        while (fp >= 0) {
            int v = tFrame[fp];
            int c = euler[v];
            if (c != -1) {
                euler[v] = childNext[c];
                tin[c] = timer++;
                euler[c] = childHead[c];
                tFrame[++fp] = c;
            } else {
                tout[v] = timer++;
                fp--;
            }
        }

        // ---- GAC arc filtering (paper Prop. 3): remove (x, y), x != y, iff
        // x is a proper ancestor of y in the dominator tree ----
        for (int x = 0; x < n; x++) {
            int s = succ[x].fillArray(domainValues);
            for (int t = 0; t < s; t++) {
                int y = domainValues[t];
                if (y != x && tin[x] < tin[y] && tout[y] < tout[x]) succ[x].remove(y);
            }
        }

        // ---- extremal bound rules (decisions from the snapshot) ----
        if (ntrees.isBound()) {
            int L = ntrees.min();
            if (L == minTree) {
                // no room for extra trees: a potential root in a non-sink SCC
                // cannot be a root
                for (int r = 0; r < n; r++)
                    if (rootSnap[r] && !compSink[comp[r]]) succ[r].remove(r);
            }
            if (L == maxTree) {
                // every potential root is needed as a root
                for (int r = 0; r < n; r++)
                    if (rootSnap[r]) succ[r].assign(r);
            }
        }
    }

    /** CHK intersect: walk both nodes up the partial dominator tree until
     *  they meet; smaller reverse-postorder index = closer to the root. */
    private int intersect(int a, int b) {
        while (a != b) {
            while (rpon[a] > rpon[b]) a = idom[a];
            while (rpon[b] > rpon[a]) b = idom[b];
        }
        return a;
    }

    /** Iterative Tarjan over adjOut/adjCnt; fills comp; returns #components. */
    private int tarjan() {
        java.util.Arrays.fill(tNum, 0, n, -1);
        int nComp = 0, timer = 0, sp = 0;
        for (int s0 = 0; s0 < n; s0++) {
            if (tNum[s0] >= 0) continue;
            int fp = 0;
            tFrame[0] = s0;
            tIter[0] = 0;
            tNum[s0] = tLow[s0] = timer++;
            onStack[s0] = true;
            tStack[sp++] = s0;
            while (fp >= 0) {
                int v = tFrame[fp];
                if (tIter[fp] < adjCnt[v]) {
                    int w = adjOut[v][tIter[fp]++];
                    if (tNum[w] < 0) {
                        tNum[w] = tLow[w] = timer++;
                        onStack[w] = true;
                        tStack[sp++] = w;
                        tFrame[++fp] = w;
                        tIter[fp] = 0;
                    } else if (onStack[w] && tNum[w] < tLow[v]) {
                        tLow[v] = tNum[w];
                    }
                } else {
                    if (tLow[v] == tNum[v]) {
                        int w;
                        do {
                            w = tStack[--sp];
                            onStack[w] = false;
                            comp[w] = nComp;
                        } while (w != v);
                        nComp++;
                    }
                    fp--;
                    if (fp >= 0) {
                        int p = tFrame[fp];
                        if (tLow[v] < tLow[p]) tLow[p] = tLow[v];
                    }
                }
            }
        }
        return nComp;
    }

    // =====================================================================
    // beliefs
    // =====================================================================

    @Override
    public void updateBelief() {
        if (TreeConfig.BELIEF == TreeConfig.BeliefRoutine.UNIFORM || n > TreeConfig.MAX_N) {
            setExactWCounting(false);
            super.updateBelief();
            return;
        }
        // ---- collapse ----
        int nPotential = 0;
        for (int i = 0; i < n; i++) {
            java.util.Arrays.fill(a[i], 0, n, 0.0);
            int s = succ[i].fillArray(domainValues);
            for (int t = 0; t < s; t++) {
                int j = domainValues[t];
                a[i][j] = beliefRep.rep2std(outsideBelief(i, j));
            }
            if (succ[i].contains(i)) nPotential++;
        }
        java.util.Arrays.fill(wN, 0, n + 1, 0.0);
        int sN = ntrees.fillArray(domainValues);
        for (int t = 0; t < sN; t++) {
            int k = domainValues[t];
            if (k >= 0 && k <= n) wN[k] = beliefRep.rep2std(outsideBelief(n, k));
        }

        if (dp == null) dp = new TreeMatrixDP(Math.min(n, TreeConfig.MAX_N), TreeConfig.NTREES_MAX_N);

        double z = -1;
        boolean singleRoot = nPotential == 1;
        if (singleRoot) {
            // every solution has exactly one root: one real adjugate suffices,
            // and the ntrees message is all mass on k = 1
            z = dp.runForest(n, a, msg);
            if (z >= 0) {
                java.util.Arrays.fill(msgK, 0, n + 1, 0.0);
                msgK[1] = z;
            }
        } else if (n <= TreeConfig.NTREES_MAX_N) {
            z = dp.runWithCount(n, a, wN, msg, msgK);
        }
        if (z < 0) {
            if (System.getProperty("minicpbp.tree.debug") != null) {
                System.err.printf("TreeDBG fallback: n=%d nPotential=%d singleRoot=%b a00=%.6g a01=%.6g wN1=%.6g wN2=%.6g%n",
                        n, nPotential, singleRoot, a[0][0], a[0].length > 1 ? a[0][1] : -1, wN[1], n >= 2 ? wN[2] : -1);
            }
            setExactWCounting(false);
            super.updateBelief();
            return;
        }
        setExactWCounting(!aliased);
        // ---- emit succ messages (normalized per variable: BP uses ratios) ----
        for (int i = 0; i < n; i++) {
            int s = succ[i].fillArray(domainValues);
            double sum = 0;
            for (int t = 0; t < s; t++) sum += msg[i][domainValues[t]];
            double inv = sum > 0 ? 1.0 / sum : 0.0;
            for (int t = 0; t < s; t++) {
                int j = domainValues[t];
                setLocalBelief(i, j, beliefRep.std2rep(msg[i][j] * inv));
            }
        }
        // ---- emit ntrees message (the exact belief over the counting variable) ----
        double sumK = 0;
        int s2 = ntrees.fillArray(domainValues);
        for (int t = 0; t < s2; t++) {
            int k = domainValues[t];
            if (k >= 0 && k <= n) sumK += msgK[k];
        }
        double invK = sumK > 0 ? 1.0 / sumK : 0.0;
        for (int t = 0; t < s2; t++) {
            int k = domainValues[t];
            double v = (k >= 0 && k <= n) ? msgK[k] * invK : 0.0;
            setLocalBelief(n, k, beliefRep.std2rep(v));
        }
    }
}
