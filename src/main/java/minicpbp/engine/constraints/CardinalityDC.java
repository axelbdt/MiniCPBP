/*
 * mini-cpbp, replacing classic propagation by belief propagation
 *
 * Domain-consistent global cardinality constraint (gcc), 2026-08-18.
 * See GCC_EXPERIMENT.md and IMPLEMENTATION_LOG.md.
 *
 * Filtering: the flow-based domain-consistency algorithm of
 *   J.-C. Régin, "Generalized Arc Consistency for Global Cardinality
 *   Constraint", AAAI-96,
 * implemented from the algorithm description (no code ported): feasible flow
 * by two-phase augmentation (value lower bounds first, then every variable),
 * then removal of the (x_i, v) edges with zero flow whose endpoints lie in
 * different strongly connected components of the residual graph.
 *
 * Occurrence variables o_j are filtered with cheap sound bounds
 * (#vars bound to v_j <= o_j <= #vars whose domain contains v_j); the flow
 * uses [o_j.min(), o_j.max()] re-read at every propagation.
 *
 * Beliefs (updateBelief): selectable via GccConfig.BELIEF —
 *   EXACT  count-vector DP (CountVectorDP), exact messages for x and o,
 *          feasible when prod(up_j+1) <= GccConfig.MAX_STATES;
 *   BP     nested belief propagation (GccBP);
 *   AUTO   EXACT below the state budget, BP above (the production setting);
 *   UNIFORM the AbstractConstraint default (control).
 */

package minicpbp.engine.constraints;

import minicpbp.engine.core.AbstractConstraint;
import minicpbp.engine.core.IntVar;
import minicpbp.util.CountVectorDP;
import minicpbp.util.GccBP;
import minicpbp.util.GccBeliefHarvester;
import minicpbp.util.GccConfig;
import minicpbp.util.GraphUtil;
import minicpbp.util.LoBiancoBound;
import minicpbp.util.SelfRefOracle;
import minicpbp.util.GraphUtil.Graph;
import minicpbp.util.exception.InconsistencyException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

public class CardinalityDC extends AbstractConstraint {

    private final IntVar[] x;
    private final int[] vals;      // counted values, one class each
    private final IntVar[] o;      // occurrence variables, o[j] for vals[j]
    private final int n;           // number of x variables
    private final int k;           // number of counted values

    // ----- value universe (fixed at construction; domains only shrink) -----
    private final int[] valueOf;               // value node index -> actual value
    private final int m;                       // number of value nodes
    private final Map<Integer, Integer> nodeOf; // actual value -> value node index
    private final int[] classOf;               // value node index -> class j, or -1 if uncounted

    // ----- flow structures (rebuilt per propagate) -----
    private final int[] fx;        // fx[i] = value node assigned to var i, -1 if none
    private final int[] fv;        // fv[v] = number of vars assigned to value node v
    private final int[] capLow;    // per value node, current lower capacity
    private final int[] capUp;     // per value node, current upper capacity
    private final int[] bfsParent; // BFS predecessor node (graph node id)
    private final int[] bfsQueue;

    // ----- residual graph for SCC -----
    private final ArrayList<Integer>[] in;
    private final ArrayList<Integer>[] out;
    private final int nNodes;      // n vars + m values + 1 (t)
    private final Graph g = new Graph() {
        @Override
        public int n() {
            return nNodes;
        }

        @Override
        public Iterable<Integer> in(int idx) {
            return in[idx];
        }

        @Override
        public Iterable<Integer> out(int idx) {
            return out[idx];
        }
    };

    // ----- counting structures -----
    private final double[][] a;    // n x k collapsed class weights
    private final double[] b;      // n, "other" class weight
    private final int[] low;       // k, effective occurrence lower bounds
    private final int[] up;        // k, effective occurrence upper bounds
    private final double[][] wOcc; // k, occurrence outside beliefs (index 0..up[j])
    private final double[][] msg;  // n x (k+1) factor-to-variable messages
    private final double[][] msgOcc; // k x (n+1) messages to occurrence variables
    private CountVectorDP dp;
    private GccBP bp;
    private LoBiancoBound lobianco;
    private SelfRefOracle selfDp;
    /**
     * True when some occurrence variable IS one of the x variables (e.g. the
     * magic-sequence gcc). CountVectorDP/GccBP/LoBiancoBound treat x and o as
     * disjoint, which is then a relaxation: messages remain usable for
     * ranking and a zero stays sound for pruning (relaxation-zero implies
     * true-zero), but the count is not exact. Since round 3 (2026-08-18) the
     * exact/auto routines use SelfRefOracle on such systems — the true
     * count-vector DP enforcing x_i = c_j — and exactness IS claimed there,
     * guarded by selfExactApplicable().
     */
    private final boolean selfReferential;
    /** selfIdx[j] = i if o[j] IS x[i], else -1 (harvested for the true oracle). */
    private final int[] selfIdx;

    /**
     * @param x    variables
     * @param vals counted values (distinct)
     * @param o    occurrence variables, o[j] = number of x's equal to vals[j]
     */
    public CardinalityDC(IntVar[] x, int[] vals, IntVar[] o) {
        super(x[0].getSolver(), scope(x, o));
        setName("CardinalityDC");
        this.x = x;
        this.vals = vals;
        this.o = o;
        this.n = x.length;
        this.k = vals.length;

        // value universe
        SortedSet<Integer> allVals = new TreeSet<>();
        for (IntVar var : x) {
            int s = var.fillArray(domainValues);
            for (int j = 0; j < s; j++) allVals.add(domainValues[j]);
        }
        for (int v : vals) allVals.add(v); // counted values kept even if absent from all domains
        m = allVals.size();
        valueOf = new int[m];
        nodeOf = new HashMap<>();
        int idx = 0;
        for (int v : allVals) {
            valueOf[idx] = v;
            nodeOf.put(v, idx);
            idx++;
        }
        classOf = new int[m];
        java.util.Arrays.fill(classOf, -1);
        for (int j = 0; j < k; j++) classOf[nodeOf.get(vals[j])] = j;

        fx = new int[n];
        fv = new int[m];
        capLow = new int[m];
        capUp = new int[m];
        nNodes = n + m + 1;
        bfsParent = new int[nNodes + 1]; // +1 for the source, encoded as node nNodes
        bfsQueue = new int[nNodes + 1];
        in = newLists(nNodes);
        out = newLists(nNodes);

        a = new double[n][k];
        b = new double[n];
        low = new int[k];
        up = new int[k];
        wOcc = new double[k][];
        msg = new double[n][k + 1];
        msgOcc = new double[k][n + 1];

        // occurrence variables are trivially bounded by [0, n]
        for (int j = 0; j < k; j++) {
            o[j].removeBelow(0);
            o[j].removeAbove(n);
        }
        boolean selfRef = false;
        selfIdx = new int[k];
        java.util.Arrays.fill(selfIdx, -1);
        for (int j = 0; j < k; j++)
            for (int i = 0; i < n; i++)
                if (o[j] == x[i]) { selfIdx[j] = i; selfRef = true; break; }
        selfReferential = selfRef;
        setExactWCounting(false); // refined per call in updateBelief()
    }

    /**
     * The true self-referential DP may claim exactness only when every self
     * owner's belief mass lies entirely on tracked classes (b[i] == 0): a
     * forced count value outside the tracked classes is then genuinely
     * infeasible for that variable. With b[i] > 0 the owner could take an
     * untracked value equal to its count, whose individual weight the
     * class-collapsed system cannot recover — the DP would under-count and a
     * zero could prune a true support.
     */
    private boolean selfExactApplicable() {
        for (int j = 0; j < k; j++) {
            int i = selfIdx[j];
            if (i >= 0 && b[i] != 0.0) return false;
        }
        return true;
    }

    private static IntVar[] scope(IntVar[] x, IntVar[] o) {
        IntVar[] vars = new IntVar[x.length + o.length];
        System.arraycopy(x, 0, vars, 0, x.length);
        System.arraycopy(o, 0, vars, x.length, o.length);
        return vars;
    }

    @SuppressWarnings("unchecked")
    private static ArrayList<Integer>[] newLists(int n) {
        ArrayList<Integer>[] l = new ArrayList[n];
        for (int i = 0; i < n; i++) l[i] = new ArrayList<>();
        return l;
    }

    @Override
    public void post() {
        switch (getSolver().getMode()) {
            case BP:
                break;
            case SP:
            case SBP:
                for (IntVar var : x) var.propagateOnDomainChange(this);
                for (IntVar var : o) var.propagateOnBoundChange(this);
        }
        propagate();
    }

    @Override
    public void propagate() {
        // ---- occurrence bounds from simple counting (sound, cheap) ----
        for (int j = 0; j < k; j++) {
            int bound = 0, possible = 0;
            for (int i = 0; i < n; i++) {
                if (x[i].contains(vals[j])) {
                    possible++;
                    if (x[i].isBound()) bound++;
                }
            }
            o[j].removeBelow(bound);
            o[j].removeAbove(possible);
        }
        // ---- capacities ----
        for (int v = 0; v < m; v++) {
            int j = classOf[v];
            if (j >= 0) {
                capLow[v] = Math.max(0, o[j].min());
                capUp[v] = Math.min(n, o[j].max());
            } else {
                capLow[v] = 0;
                capUp[v] = n;
            }
        }
        // ---- feasible flow ----
        java.util.Arrays.fill(fx, -1);
        java.util.Arrays.fill(fv, 0, m, 0);
        // phase 1: saturate lower bounds (max-flow with caps = capLow)
        int need = 0;
        for (int v = 0; v < m; v++) need += capLow[v];
        int got = 0;
        while (got < need && augment(capLow)) got++;
        if (got < need) throw new InconsistencyException();
        // phase 2: route every variable (caps = capUp)
        int assigned = got;
        while (assigned < n && augment(capUp)) assigned++;
        if (assigned < n) throw new InconsistencyException();
        // ---- residual graph, SCCs, filtering ----
        buildResidual();
        int[] scc = GraphUtil.stronglyConnectedComponents(g);
        for (int i = 0; i < n; i++) {
            int s = x[i].fillArray(domainValues);
            for (int j2 = 0; j2 < s; j2++) {
                int val = domainValues[j2];
                int v = nodeOf.get(val);
                if (fx[i] != v && scc[i] != scc[n + v]) {
                    x[i].remove(val);
                }
            }
        }
    }

    /**
     * One BFS augmentation from the source (unassigned variables) to the sink
     * (value nodes below their capacity cap[]), through the residual graph.
     * Updates fx/fv along the path. Returns false if no augmenting path exists.
     */
    private boolean augment(int[] cap) {
        final int S = nNodes; // virtual source id in bfsParent
        final int T = n + m;  // sink node id (same as residual t)
        java.util.Arrays.fill(bfsParent, 0, nNodes + 1, -2); // -2 = unvisited
        int head = 0, tail = 0;
        for (int i = 0; i < n; i++) {
            if (fx[i] == -1) {
                bfsParent[i] = S;
                bfsQueue[tail++] = i;
            }
        }
        int reached = -1;
        while (head < tail && reached < 0) {
            int u = bfsQueue[head++];
            if (u < n) { // variable node: arcs to values in its domain not currently assigned
                int s = x[u].fillArray(domainValues);
                for (int j2 = 0; j2 < s; j2++) {
                    int v = nodeOf.get(domainValues[j2]);
                    if (fx[u] != v && bfsParent[n + v] == -2) {
                        bfsParent[n + v] = u;
                        if (fv[v] < cap[v]) {
                            reached = v;
                            break;
                        }
                        bfsQueue[tail++] = n + v;
                    }
                }
            } else { // value node: arcs back to variables currently assigned to it
                int v = u - n;
                for (int i = 0; i < n; i++) {
                    if (fx[i] == v && bfsParent[i] == -2) {
                        bfsParent[i] = u;
                        bfsQueue[tail++] = i;
                    }
                }
            }
        }
        if (reached < 0) return false;
        // walk back the alternating path, flipping var->value assignments
        int v = reached;
        fv[v]++;
        int node = bfsParent[n + v]; // a variable
        while (true) {
            int i = node;
            int prev = bfsParent[i];
            fx[i] = v;
            if (prev == S) break;
            // prev is a value node the variable was assigned to; its flow count
            // is conserved (one var out, one var in), continue up the path
            v = prev - n;
            node = bfsParent[prev];
        }
        return true;
    }

    private void buildResidual() {
        final int T = n + m;
        for (int i = 0; i < nNodes; i++) {
            in[i].clear();
            out[i].clear();
        }
        for (int i = 0; i < n; i++) {
            int s = x[i].fillArray(domainValues);
            for (int j2 = 0; j2 < s; j2++) {
                int v = nodeOf.get(domainValues[j2]);
                if (fx[i] == v) {
                    // flow 1: residual arc value -> var
                    out[n + v].add(i);
                    in[i].add(n + v);
                } else {
                    // flow 0: residual arc var -> value
                    out[i].add(n + v);
                    in[n + v].add(i);
                }
            }
        }
        for (int v = 0; v < m; v++) {
            if (fv[v] < capUp[v]) {
                out[n + v].add(T);
                in[T].add(n + v);
            }
            if (fv[v] > capLow[v]) {
                out[T].add(n + v);
                in[n + v].add(T);
            }
        }
    }

    // =====================================================================
    // beliefs
    // =====================================================================

    @Override
    public void updateBelief() {
        GccConfig.BeliefRoutine routine = GccConfig.BELIEF;
        if (routine == GccConfig.BeliefRoutine.UNIFORM) {
            super.updateBelief();
            return;
        }
        // ---- collapse to classes ----
        for (int i = 0; i < n; i++) {
            double other = beliefRep.zero();
            for (int j = 0; j < k; j++) a[i][j] = 0.0;
            int s = x[i].fillArray(domainValues);
            for (int j2 = 0; j2 < s; j2++) {
                int val = domainValues[j2];
                int j = classOf[nodeOf.get(val)];
                if (j >= 0) a[i][j] = beliefRep.rep2std(outsideBelief(i, val));
                else other = beliefRep.add(other, outsideBelief(i, val));
            }
            b[i] = beliefRep.rep2std(other);
        }
        for (int j = 0; j < k; j++) {
            low[j] = Math.max(0, o[j].min());
            up[j] = Math.min(n, o[j].max());
            double[] w = new double[up[j] + 1];
            for (int c = low[j]; c <= up[j]; c++) {
                if (o[j].contains(c)) w[c] = beliefRep.rep2std(outsideBelief(n + j, c));
            }
            wOcc[j] = w;
        }
        if (GccConfig.harvesting())
            GccBeliefHarvester.offer(n, k, a, b, low, up, wOcc, vals, selfIdx);

        if (routine == GccConfig.BeliefRoutine.LOBIANCO) {
            // Phase 3 arm 2: the closed-form column (Lo Bianco et al., JAIR 66
            // §4, Soules-weighted; ESTIMATOR, not a bound — see LoBiancoBound).
            // The closed form ignores occurrence beliefs beyond [low, up] and
            // has no count channel: occurrence variables get uniform messages.
            if (lobianco == null) lobianco = new LoBiancoBound(Math.max(320, n + 2));
            if (!lobianco.messages(n, k, a, b, low, up, msg)) {
                super.updateBelief(); // system exceeds the closed form's dims: uniform fallback
                return;
            }
            setExactWCounting(false);
            for (int i = 0; i < n; i++) {
                int s = x[i].fillArray(domainValues);
                for (int j2 = 0; j2 < s; j2++) {
                    int val = domainValues[j2];
                    int j = classOf[nodeOf.get(val)];
                    setLocalBelief(i, val, beliefRep.std2rep(j >= 0 ? msg[i][j] : msg[i][k]));
                }
            }
            for (int j = 0; j < k; j++) {
                int s = o[j].fillArray(domainValues);
                for (int c2 = 0; c2 < s; c2++) {
                    int c = domainValues[c2];
                    double v = (c >= low[j] && c <= up[j]) ? 1.0 : 0.0;
                    setLocalBelief(n + j, c, beliefRep.std2rep(v));
                }
            }
            return;
        }

        boolean exact = false;
        boolean trueExact = false; // exact for the FULL constraint (incl. self-reference)
        long states = CountVectorDP.stateCount(up, GccConfig.MAX_STATES);
        boolean withinBudget = states > 0
                && (routine == GccConfig.BeliefRoutine.EXACT   // EXACT: memory cap only
                    || states * (k + 1) * (long) n <= GccConfig.OPS_BUDGET); // AUTO: ops budget
        if ((routine == GccConfig.BeliefRoutine.EXACT || routine == GccConfig.BeliefRoutine.AUTO)
                && withinBudget) {
            for (int j = 0; j < k; j++) java.util.Arrays.fill(msgOcc[j], 0, up[j] + 1, 0.0);
            if (selfReferential && selfExactApplicable()) {
                // Round 3 (2026-08-18): the TRUE self-referential count-vector
                // DP — enforces x_i = c_j when o_j IS x_i — at the same
                // O(n·S·k) cost as the relaxed DP. Sound to claim exactness
                // only under selfExactApplicable() (every self owner's domain
                // fully tracked: a forced count value outside the tracked
                // classes then really is infeasible, never an aggregated
                // "other" value the DP cannot weight). See SelfRefOracle.
                if (selfDp == null) selfDp = new SelfRefOracle(GccConfig.MAX_STATES);
                double z = selfDp.run(n, k, a, b, low, up, wOcc, vals, selfIdx, msg, msgOcc);
                exact = z >= 0;
                trueExact = exact;
            } else {
                if (dp == null) dp = new CountVectorDP(GccConfig.MAX_STATES);
                double z = dp.run(n, k, a, b, low, up, wOcc, msg, msgOcc);
                exact = z >= 0;
                trueExact = exact && !selfReferential;
            }
        }
        if (!exact) {
            if (bp == null) bp = new GccBP();
            for (int j = 0; j < k; j++) java.util.Arrays.fill(msgOcc[j], 0, up[j] + 1, 0.0);
            if (!bp.run(n, k, a, b, low, up, wOcc, GccConfig.BP_ITERS, msg, msgOcc,
                    GccConfig.BP_EPS, GccConfig.BP_WARM,
                    GccConfig.BP_MIN_COLD, GccConfig.BP_MIN_WARM)) {
                super.updateBelief(); // numerical failure: uniform fallback
                return;
            }
        }
        setExactWCounting(trueExact);
        // ---- emit messages for x ----
        for (int i = 0; i < n; i++) {
            int s = x[i].fillArray(domainValues);
            for (int j2 = 0; j2 < s; j2++) {
                int val = domainValues[j2];
                int j = classOf[nodeOf.get(val)];
                setLocalBelief(i, val, beliefRep.std2rep(j >= 0 ? msg[i][j] : msg[i][k]));
            }
        }
        // ---- emit messages for o (exact: DP leave-one-out; BP: cavity count distribution) ----
        for (int j = 0; j < k; j++) {
            int s = o[j].fillArray(domainValues);
            for (int c2 = 0; c2 < s; c2++) {
                int c = domainValues[c2];
                double v = (c >= 0 && c <= up[j] && c >= low[j]) ? msgOcc[j][c] : 0.0;
                setLocalBelief(n + j, c, beliefRep.std2rep(v));
            }
        }
    }
}
