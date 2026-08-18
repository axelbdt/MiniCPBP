/*
 * mini-cpbp, replacing classic propagation by belief propagation
 *
 * bin_packing with dedicated weighted counting, 2026-08-18 (round 4).
 * See BINPACKING_EXPERIMENT.md and IMPLEMENTATION_LOG.md.
 *
 * Filtering (identical across belief arms, sound, standard bin-packing
 * propagation on the load decomposition):
 *   - per-bin load bounds: sum of sizes of items bound to j <= l_j <=
 *     sum of sizes of items whose domain contains j;
 *   - total-sum channeling: sum_j l_j = sum_i size_i (bounds form);
 *   - item commitment: j is removed from D(b_i) when the committed load of j
 *     plus size_i exceeds l_j.max(); b_i is bound to j when the possible load
 *     of j without item i falls below l_j.min().
 *
 * Beliefs (updateBelief): selectable via BinPackingConfig.BELIEF —
 *   EXACT   load-vector DP (BinLoadDP), exact messages for b and l,
 *           feasible when prod(up_j+1) <= BinPackingConfig.MAX_STATES;
 *   BP      nested belief propagation (BinPackingBP): per-item exactly-one
 *           factors + per-bin knapsack factors;
 *   AUTO    EXACT below the ops budget, BP above (the production setting);
 *   UNIFORM the AbstractConstraint default (control).
 */

package minicpbp.engine.constraints;

import minicpbp.engine.core.AbstractConstraint;
import minicpbp.engine.core.IntVar;
import minicpbp.util.BinLoadDP;
import minicpbp.util.BinPackingBP;
import minicpbp.util.BinPackingBeliefHarvester;
import minicpbp.util.BinPackingConfig;

public class BinPackingCounting extends AbstractConstraint {

    private final IntVar[] b;      // item -> bin
    private final int[] size;      // fixed item sizes
    private final IntVar[] l;      // per-bin loads
    private final int n;           // items
    private final int m;           // bins
    private final int total;       // sum of sizes

    // ----- filtering scratch -----
    private final int[] committed; // per bin, sum of sizes of bound items
    private final int[] possible;  // per bin, sum of sizes of items that may go there

    // ----- counting structures -----
    private final double[][] a;    // n x m collapsed weights
    private final int[] low;       // m, effective load lower bounds
    private final int[] up;        // m, effective load upper bounds
    private final double[][] wLoad; // m, load outside beliefs (index 0..up[j])
    private final double[][] msg;  // n x m factor-to-item messages
    private final double[][] msgLoad; // m x (total+1) messages to load variables
    private BinLoadDP dp;
    private BinPackingBP bp;
    /**
     * True when the exactly-one internal factorization is a relaxation:
     * duplicate item variables, or a load variable aliased to an item
     * variable or to another load variable. Messages stay usable for ranking
     * (a zero stays sound for pruning under setExactWCounting(false)), but
     * exactness is never claimed.
     */
    private final boolean aliased;

    /**
     * @param b    the bin into which each item is put
     * @param size the (fixed, nonnegative) size of each item
     * @param l    the load of each bin
     */
    public BinPackingCounting(IntVar[] b, int[] size, IntVar[] l) {
        super(b[0].getSolver(), scope(b, l));
        setName("BinPackingCounting");
        this.b = b;
        this.size = size;
        this.l = l;
        this.n = b.length;
        this.m = l.length;
        assert size.length == n;
        int t = 0;
        for (int s : size) {
            assert s >= 0;
            t += s;
        }
        this.total = t;

        committed = new int[m];
        possible = new int[m];
        a = new double[n][m];
        low = new int[m];
        up = new int[m];
        wLoad = new double[m][];
        msg = new double[n][m];
        msgLoad = new double[m][total + 1];

        boolean alias = false;
        for (int i = 0; i < n && !alias; i++)
            for (int i2 = i + 1; i2 < n; i2++)
                if (b[i] == b[i2]) { alias = true; break; }
        for (int j = 0; j < m && !alias; j++) {
            for (int i = 0; i < n; i++)
                if (l[j] == b[i]) { alias = true; break; }
            for (int j2 = j + 1; j2 < m && !alias; j2++)
                if (l[j] == l[j2]) alias = true;
        }
        aliased = alias;
        setExactWCounting(false); // refined per call in updateBelief()
    }

    private static IntVar[] scope(IntVar[] b, IntVar[] l) {
        IntVar[] vars = new IntVar[b.length + l.length];
        System.arraycopy(b, 0, vars, 0, b.length);
        System.arraycopy(l, 0, vars, b.length, l.length);
        return vars;
    }

    @Override
    public void post() {
        for (int i = 0; i < n; i++) {
            b[i].removeBelow(0);
            b[i].removeAbove(m - 1);
        }
        for (int j = 0; j < m; j++) {
            l[j].removeBelow(0);
            l[j].removeAbove(total);
        }
        switch (getSolver().getMode()) {
            case BP:
                break;
            case SP:
            case SBP:
                for (IntVar var : b) var.propagateOnDomainChange(this);
                for (IntVar var : l) var.propagateOnBoundChange(this);
        }
        propagate();
    }

    @Override
    public void propagate() {
        // ---- per-bin committed / possible loads ----
        for (int j = 0; j < m; j++) {
            committed[j] = 0;
            possible[j] = 0;
        }
        for (int i = 0; i < n; i++) {
            if (b[i].isBound()) {
                int j = b[i].min();
                committed[j] += size[i];
                possible[j] += size[i];
            } else {
                int s = b[i].fillArray(domainValues);
                for (int t = 0; t < s; t++) possible[domainValues[t]] += size[i];
            }
        }
        for (int j = 0; j < m; j++) {
            l[j].removeBelow(committed[j]);
            l[j].removeAbove(possible[j]);
        }
        // ---- total-sum channeling (bounds) ----
        int sumMin = 0, sumMax = 0;
        for (int j = 0; j < m; j++) {
            sumMin += l[j].min();
            sumMax += l[j].max();
        }
        for (int j = 0; j < m; j++) {
            l[j].removeBelow(total - (sumMax - l[j].max()));
            l[j].removeAbove(total - (sumMin - l[j].min()));
        }
        // ---- item filtering ----
        for (int i = 0; i < n; i++) {
            if (b[i].isBound()) continue;
            int s = b[i].fillArray(domainValues);
            for (int t = 0; t < s; t++) {
                int j = domainValues[t];
                if (committed[j] + size[i] > l[j].max()) {
                    b[i].remove(j);
                } else if (possible[j] - size[i] < l[j].min()) {
                    // without item i, bin j cannot reach its minimum load
                    b[i].assign(j);
                    break;
                }
            }
        }
    }

    // =====================================================================
    // beliefs
    // =====================================================================

    @Override
    public void updateBelief() {
        BinPackingConfig.BeliefRoutine routine = BinPackingConfig.BELIEF;
        if (routine == BinPackingConfig.BeliefRoutine.UNIFORM) {
            super.updateBelief();
            return;
        }
        // ---- collapse ----
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) a[i][j] = 0.0;
            int s = b[i].fillArray(domainValues);
            for (int t = 0; t < s; t++) {
                int j = domainValues[t];
                a[i][j] = beliefRep.rep2std(outsideBelief(i, j));
            }
        }
        for (int j = 0; j < m; j++) {
            low[j] = Math.max(0, l[j].min());
            up[j] = Math.min(total, l[j].max());
            double[] w = new double[up[j] + 1];
            for (int c = low[j]; c <= up[j]; c++) {
                if (l[j].contains(c)) w[c] = beliefRep.rep2std(outsideBelief(n + j, c));
            }
            wLoad[j] = w;
        }
        if (BinPackingConfig.harvesting())
            BinPackingBeliefHarvester.offer(n, m, size, a, low, up, wLoad);

        boolean exact = false;
        long states = BinLoadDP.stateCount(up, BinPackingConfig.MAX_STATES);
        boolean withinBudget = states > 0
                && (routine == BinPackingConfig.BeliefRoutine.EXACT   // EXACT: memory cap only
                    || states * (m + 1) * (long) n <= BinPackingConfig.OPS_BUDGET); // AUTO: ops budget
        if ((routine == BinPackingConfig.BeliefRoutine.EXACT || routine == BinPackingConfig.BeliefRoutine.AUTO)
                && withinBudget) {
            for (int j = 0; j < m; j++) java.util.Arrays.fill(msgLoad[j], 0, up[j] + 1, 0.0);
            if (dp == null) dp = new BinLoadDP(BinPackingConfig.MAX_STATES);
            double z = dp.run(n, m, size, a, low, up, wLoad, msg, msgLoad);
            exact = z >= 0;
        }
        if (!exact) {
            if (bp == null) bp = new BinPackingBP();
            for (int j = 0; j < m; j++) java.util.Arrays.fill(msgLoad[j], 0, up[j] + 1, 0.0);
            if (!bp.run(n, m, size, a, low, up, wLoad, BinPackingConfig.BP_ITERS, msg, msgLoad)) {
                super.updateBelief(); // numerical failure: uniform fallback
                return;
            }
        }
        setExactWCounting(exact && !aliased);
        // ---- emit messages for b ----
        for (int i = 0; i < n; i++) {
            int s = b[i].fillArray(domainValues);
            for (int t = 0; t < s; t++) {
                int j = domainValues[t];
                setLocalBelief(i, j, beliefRep.std2rep(msg[i][j]));
            }
        }
        // ---- emit messages for l (exact: DP leave-one-out; BP: cavity load distribution) ----
        for (int j = 0; j < m; j++) {
            int s = l[j].fillArray(domainValues);
            for (int c2 = 0; c2 < s; c2++) {
                int c = domainValues[c2];
                double v = (c >= low[j] && c <= up[j]) ? msgLoad[j][c] : 0.0;
                setLocalBelief(n + j, c, beliefRep.std2rep(v));
            }
        }
    }
}
