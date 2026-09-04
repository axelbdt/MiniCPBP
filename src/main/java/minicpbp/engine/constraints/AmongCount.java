/*
 * mini-cpbp, replacing classic propagation by belief propagation
 *
 * AmongCount: direct among/count constraint (WP2 of AMONG_GCC_OPTIMIZATION_PLAN.md).
 *
 * Holds iff  (x[0] in V) + ... + (x[n-1] in V) == o.
 *
 * Filtering: the bounds-consistent occurrence reasoning of AmongVarBC
 * (witness-based decided/undecided partition; the occurrence variable is
 * pruned to [nInside, nInside+nUndecided], which is domain consistent because
 * every count in that interval is reachable).
 *
 * Beliefs: the exact count DP of CountVectorDP at k = 1, on the two-class
 * collapse a_i = sum_{v in V} theta_i(v), b_i = sum_{v not in V} theta_i(v).
 * The message to o is the full convolution; the message to x_i takes two
 * distinct values only (inside V, outside V).
 *
 * This replaces the AmongVar + Sum decomposition: no indicator variables y are
 * materialized, so the factor graph loses n Boolean variables and one hop per
 * BP iteration per among constraint. The BP fixed point is unchanged
 * (AMONG_GCC_OPTIMIZATION_PLAN.md T3/F3: Fig. 5, Fig. 6 and the direct graph
 * have identical fixed points; only the schedule length differs).
 */

package minicpbp.engine.constraints;

import minicpbp.engine.core.AbstractConstraint;
import minicpbp.engine.core.IntVar;
import minicpbp.state.StateInt;
import minicpbp.util.CountVectorDP;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.stream.IntStream;

public class AmongCount extends AbstractConstraint {

    private final IntVar[] x;
    private final IntVar o;
    private final Set<Integer> V;
    private final int n;

    // filtering state (same scheme as AmongVarBC)
    private final int[] undecided;
    private final StateInt nUndecided;
    private final StateInt[] witnessInsideV;
    private final StateInt[] witnessOutsideV;
    private final StateInt nInside;
    private final StateInt nOutside;

    // counting scratch
    private final double[][] a;      // n x 1
    private final double[] b;        // n
    private final int[] low = new int[1];
    private final int[] up = new int[1];
    private final double[][] wOcc = new double[1][];
    private final double[][] msg;    // n x 2
    private final double[][] msgOcc; // 1 x (n+1)
    private CountVectorDP dp;

    private static IntVar[] scope(IntVar[] x, IntVar o) {
        IntVar[] s = Arrays.copyOf(x, x.length + 1);
        s[x.length] = o;
        return s;
    }

    /** true iff o is (reference-)one of the x variables; then the DP is a relaxation. */
    public static boolean selfReferential(IntVar[] x, IntVar o) {
        for (IntVar xi : x) if (xi == o) return true;
        return false;
    }

    public AmongCount(IntVar[] x, int[] V, IntVar o) {
        super(x[0].getSolver(), scope(x, o));
        setName("AmongCount");
        this.x = x;
        this.o = o;
        this.n = x.length;
        this.V = new HashSet<Integer>();
        for (int v : V) this.V.add(v);

        o.removeBelow(0);
        o.removeAbove(n);

        nUndecided = getSolver().getStateManager().makeStateInt(n);
        undecided = IntStream.range(0, n).toArray();
        witnessInsideV = new StateInt[n];
        witnessOutsideV = new StateInt[n];
        for (int i = 0; i < n; i++) {
            assert !x[i].contains(Integer.MIN_VALUE) : "AmongCount: x[" + i + "] has Integer.MIN_VALUE in its domain!";
            witnessInsideV[i] = getSolver().getStateManager().makeStateInt(Integer.MIN_VALUE);
            witnessOutsideV[i] = getSolver().getStateManager().makeStateInt(Integer.MIN_VALUE);
        }
        nInside = getSolver().getStateManager().makeStateInt(0);
        nOutside = getSolver().getStateManager().makeStateInt(0);

        a = new double[n][1];
        b = new double[n];
        msg = new double[n][2];
        msgOcc = new double[1][n + 1];

        // exact weighted counting, except when o is itself one of the counted
        // variables (the DP treats x and o as disjoint, which is then a relaxation)
        setExactWCounting(!selfReferential(x, o));
    }

    @Override
    public void post() {
        switch (getSolver().getMode()) {
            case BP:
                break;
            case SP:
            case SBP:
                for (IntVar var : x)
                    var.propagateOnDomainChange(this);
                o.propagateOnBoundChange(this);
        }
        propagate();
    }

    @Override
    public void propagate() {
        int nU = nUndecided.value();
        for (int i = nU - 1; i >= 0; i--) {
            int idx = undecided[i];
            switch (isDecided(x[idx], idx)) {
                case 0:
                    continue;
                case 1:
                    nInside.setValue(nInside.value() + 1);
                    break;
                case -1:
                    nOutside.setValue(nOutside.value() + 1);
                    break;
            }
            undecided[i] = undecided[nU - 1];
            undecided[nU - 1] = idx;
            nU--;
        }
        nUndecided.setValue(nU);

        int nI = nInside.value();

        o.removeBelow(nI);
        o.removeAbove(nI + nU);

        if (nI + nU == o.min()) { // force all undecided vars inside V
            for (int i = nU - 1; i >= 0; i--) {
                int idx = undecided[i];
                int s = x[idx].fillArray(domainValues);
                for (int j = 0; j < s; j++) {
                    int v = domainValues[j];
                    if (!V.contains(v)) x[idx].remove(v);
                }
            }
        }
        if (nI == o.max()) { // force all undecided vars outside V
            for (int i = nU - 1; i >= 0; i--) {
                int idx = undecided[i];
                int s = x[idx].fillArray(domainValues);
                for (int j = 0; j < s; j++) {
                    int v = domainValues[j];
                    if (V.contains(v)) x[idx].remove(v);
                }
            }
        }
    }

    // 0: can still take a value inside and outside V; 1: only inside; -1: only outside
    private int isDecided(IntVar var, int i) {
        int j;
        if (!var.contains(witnessInsideV[i].value())) {
            if (var.size() < V.size()) {
                int s = var.fillArray(domainValues);
                for (j = 0; j < s; j++) {
                    int v = domainValues[j];
                    if (V.contains(v)) {
                        witnessInsideV[i].setValue(v);
                        break;
                    }
                }
                if (j == s) return -1;
            } else {
                Iterator<Integer> itr = V.iterator();
                while (itr.hasNext()) {
                    int val = itr.next();
                    if (var.contains(val)) {
                        witnessInsideV[i].setValue(val);
                        break;
                    }
                }
                if (!var.contains(witnessInsideV[i].value())) return -1;
            }
        }
        if (!var.contains(witnessOutsideV[i].value())) {
            int s = var.fillArray(domainValues);
            for (j = 0; j < s; j++) {
                int v = domainValues[j];
                if (!V.contains(v)) {
                    witnessOutsideV[i].setValue(v);
                    break;
                }
            }
            if (j == s) return 1;
        }
        return 0;
    }

    // =====================================================================
    // beliefs
    // =====================================================================

    /** collapses the outside beliefs into the two-class weights a[i][0], b[i] and the bounds. */
    private void collapse() {
        for (int i = 0; i < n; i++) {
            double in = beliefRep.zero();
            double out = beliefRep.zero();
            int s = x[i].fillArray(domainValues);
            for (int j = 0; j < s; j++) {
                int v = domainValues[j];
                if (V.contains(v)) in = beliefRep.add(in, outsideBelief(i, v));
                else out = beliefRep.add(out, outsideBelief(i, v));
            }
            a[i][0] = beliefRep.rep2std(in);
            b[i] = beliefRep.rep2std(out);
        }
        low[0] = Math.max(0, o.min());
        up[0] = Math.min(n, o.max());
        double[] w = new double[up[0] + 1];
        for (int c = low[0]; c <= up[0]; c++) {
            if (o.contains(c)) w[c] = beliefRep.rep2std(outsideBelief(n, c));
        }
        wOcc[0] = w;
    }

    @Override
    public void updateBelief() {
        collapse();
        Arrays.fill(msgOcc[0], 0, up[0] + 1, 0.0);
        if (dp == null) dp = new CountVectorDP(n + 1);
        double z = dp.run(n, 1, a, b, low, up, wOcc, msg, msgOcc);
        if (z < 0 || Double.isNaN(z)) {
            super.updateBelief(); // state cap exceeded / numerical failure: uniform fallback
            return;
        }
        for (int i = 0; i < n; i++) {
            double mIn = beliefRep.std2rep(msg[i][0]);
            double mOut = beliefRep.std2rep(msg[i][1]);
            int s = x[i].fillArray(domainValues);
            for (int j = 0; j < s; j++) {
                int v = domainValues[j];
                setLocalBelief(i, v, V.contains(v) ? mIn : mOut);
            }
        }
        int s = o.fillArray(domainValues);
        for (int j = 0; j < s; j++) {
            int c = domainValues[j];
            double v = (c >= low[0] && c <= up[0]) ? msgOcc[0][c] : 0.0;
            setLocalBelief(n + 0, c, beliefRep.std2rep(v));
        }
    }

    @Override
    public double weightedCounting() {
        collapse();
        if (dp == null) dp = new CountVectorDP(n + 1);
        double z = dp.run(n, 1, a, b, low, up, wOcc, msg, null);
        if (z < 0) return beliefRep.one();
        double total = z * Math.exp(dp.logScale());
        if (Double.isInfinite(total) || Double.isNaN(total)) return beliefRep.one();
        return beliefRep.std2rep(total);
    }
}
