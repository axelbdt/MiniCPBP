/*
 * mini-cp is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License  v3
 * as published by the Free Software Foundation.
 *
 * mini-cp is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY.
 * See the GNU Lesser General Public License  for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with mini-cp. If not, see http://www.gnu.org/licenses/lgpl-3.0.en.html
 *
 * Copyright (c)  2018. by Laurent Michel, Pierre Schaus, Pascal Van Hentenryck
 *
 * mini-cpbp, replacing classic propagation by belief propagation
 * Copyright (c)  2019. by Gilles Pesant
 */

package minicpbp.engine.constraints;

import minicpbp.engine.core.AbstractConstraint;
import minicpbp.engine.core.IntVar;
import minicpbp.util.Log;

import static minicpbp.util.exception.InconsistencyException.INCONSISTENCY;

/**
 * Not-all-equal over a set of variables: the assignment is forbidden only when
 * every variable takes the same value.
 *
 * Why this exists as one factor (2026-08-25, GCC_EXPERIMENT.md §14). XCSP's
 * {@code notAllEqual} (and {@code nValues > 1}, which the parser canonises to
 * it) was posted as the decomposition
 * {@code b_i = isNotEqual(x_i, x_0); or(b_1..b_{n-1})}. That is sound, but for
 * belief propagation it routes the whole relation through n-1 reified booleans:
 * each {@code IsEqualVar} marginalises a pair of variables down to one bit, the
 * {@code Or} sees only the bits, and the joint structure of the relation is gone
 * before any message crosses it. On RamseyPartition — 132 ternary
 * notAllEqual per instance, 97.8% of the factor-graph edges in arity <= 3
 * factors — that decomposition is the whole model. This class posts the
 * relation as a single factor over x_0..x_{n-1}, so BP evaluates it exactly.
 *
 * Filtering is domain consistent, and it is cheap because the constraint has
 * exactly one forbidden shape: it can prune only when n-1 variables are bound
 * to the same value, and it is entailed as soon as two bound variables differ
 * or a bound value is missing from another domain.
 *
 * Counting is exact and runs in O(n * span) per invocation, span being the
 * width of the union of the domains. The message to x_k on value v is the mass
 * of "at least one other variable differs from v" — because, given x_k = v, the
 * assignment is forbidden exactly when every other variable also takes v.
 *
 * Subtraction-free by construction, per FIXING_ZEROS.md: that mass is
 * accumulated as a sum of disjoint "the first differing variable is i" terms
 * and never as {@code total - product}. The product of the other variables'
 * beliefs on v routinely rounds to 1.0 after normalisation, and complementing
 * 1.0 manufactures an exact zero — a value declared impossible that is not.
 */
public class NotAllEqual extends AbstractConstraint {

    private final IntVar[] x;
    private final int n;
    private final int globalMin, globalSpan;

    /* per-position, per-invocation scratch */
    private final double[] total;      // sum of the position's outside beliefs
    private final double[][] others;   // others[i][v - min_i] = sum over the position's OTHER values
    private final double[] e;          // e[i]  = mass of "x_i = v", for the v being processed
    private final double[] q;          // q[i]  = mass of "x_i != v"
    private final double[] z;          // z[i]  = e[i] + q[i], the position's total
    /* prefixE[i]  = prod_{t<i} e[t]                  ("everyone before takes v")
     * prefixNE[i] = mass over t<i of "some t differs from v"
     * suffixZ[i]  = prod_{t>=i} z[t]
     * suffixNE[i] = mass over t>=i of "some t differs from v" */
    private final double[] prefixE, prefixNE, suffixZ, suffixNE;

    /**
     * Creates a not-all-equal constraint: the variables are not all assigned
     * the same value.
     *
     * @param x the variables in the scope of the constraint
     * @see minicpbp.cp.Factory#notAllEqual(IntVar[])
     */
    public NotAllEqual(IntVar[] x) {
        super(x[0].getSolver(), x);
        setName("NotAllEqual");
        this.x = x;
        this.n = x.length;
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (IntVar v : x) {
            lo = Math.min(lo, v.min());
            hi = Math.max(hi, v.max());
        }
        globalMin = lo;
        globalSpan = hi - lo + 1;
        total = new double[n];
        others = new double[n][];
        for (int i = 0; i < n; i++) others[i] = new double[x[i].max() - x[i].min() + 1];
        e = new double[n];
        q = new double[n];
        z = new double[n];
        prefixE = new double[n + 1];
        prefixNE = new double[n + 1];
        suffixZ = new double[n + 1];
        suffixNE = new double[n + 1];
        setExactWCounting(true);
    }

    @Override
    public void post() {
        if (n < 2) throw INCONSISTENCY;   // a single variable is always "all equal"
        propagate();
    }

    @Override
    public void propagate() {
        // The only forbidden assignment is the constant one, so the state of
        // this constraint is fully described by the bound variables.
        int bound = 0, common = 0, free = -1;
        for (int i = 0; i < n; i++) {
            if (x[i].isBound()) {
                int v = x[i].min();
                if (bound == 0) common = v;
                else if (v != common) {          // two bound variables already differ
                    setActive(false);
                    return;
                }
                bound++;
            } else {
                free = i;
            }
        }
        if (bound == 0) {
            registerOnBind();
            return;
        }
        // entailed as soon as some variable cannot take the common value
        for (int i = 0; i < n; i++) {
            if (!x[i].isBound() && !x[i].contains(common)) {
                setActive(false);
                return;
            }
        }
        if (bound == n) throw INCONSISTENCY;     // all bound, all equal
        if (bound == n - 1) {                    // one left: it must differ
            x[free].remove(common);
            setActive(false);
            return;
        }
        registerOnBind();
    }

    private void registerOnBind() {
        switch (getSolver().getMode()) {
            case BP:
                break;
            case SP:
            case SBP:
                for (int i = 0; i < n; i++)
                    if (!x[i].isBound()) x[i].propagateOnBind(this);
        }
    }

    /**
     * Fills {@code total[i]} and {@code others[i][.]} for every position:
     * {@code others[i][v - min_i]} is the sum of the outside beliefs of x_i over
     * its values OTHER than v. Built by a suffix pass then a prefix pass, so
     * every entry is a sum of beliefs and never {@code total - belief(v)}.
     * A bound variable is handled by its value alone: its outside belief is
     * only maintained at its min, the other slots are stale.
     */
    private void collect() {
        for (int i = 0; i < n; i++) {
            int min = x[i].min(), span = x[i].max() - min + 1;
            double[] o = others[i];
            if (x[i].isBound()) {
                for (int j = 0; j < span; j++) o[j] = beliefRep.zero();
                total[i] = beliefRep.one();
                continue;
            }
            double run = beliefRep.zero();
            for (int j = span - 1; j >= 0; j--) {
                o[j] = run;                                   // sum over values > min+j
                if (x[i].contains(min + j)) run = beliefRep.add(run, outsideBelief(i, min + j));
            }
            total[i] = run;
            run = beliefRep.zero();
            for (int j = 0; j < span; j++) {
                o[j] = beliefRep.add(o[j], run);              // += sum over values < min+j
                if (x[i].contains(min + j)) run = beliefRep.add(run, outsideBelief(i, min + j));
            }
        }
    }

    /** Loads e[], q[], z[] for one value and runs the prefix/suffix passes. */
    private void sweep(int v) {
        for (int i = 0; i < n; i++) {
            if (x[i].isBound()) {
                boolean at = x[i].min() == v;
                e[i] = at ? beliefRep.one() : beliefRep.zero();
                q[i] = at ? beliefRep.zero() : beliefRep.one();
            } else if (x[i].contains(v)) {
                e[i] = outsideBelief(i, v);
                q[i] = others[i][v - x[i].min()];
            } else {
                e[i] = beliefRep.zero();
                q[i] = total[i];
            }
            z[i] = beliefRep.add(e[i], q[i]);
        }
        prefixE[0] = beliefRep.one();
        prefixNE[0] = beliefRep.zero();
        for (int i = 0; i < n; i++) {
            prefixNE[i + 1] = beliefRep.add(beliefRep.multiply(prefixNE[i], z[i]),
                    beliefRep.multiply(prefixE[i], q[i]));
            prefixE[i + 1] = beliefRep.multiply(prefixE[i], e[i]);
        }
        suffixZ[n] = beliefRep.one();
        suffixNE[n] = beliefRep.zero();
        for (int i = n - 1; i >= 0; i--) {
            suffixZ[i] = beliefRep.multiply(z[i], suffixZ[i + 1]);
            suffixNE[i] = beliefRep.add(beliefRep.multiply(q[i], suffixZ[i + 1]),
                    beliefRep.multiply(e[i], suffixNE[i + 1]));
        }
    }

    @Override
    public void updateBelief() {
        collect();
        for (int v = globalMin; v < globalMin + globalSpan; v++) {
            boolean needed = false;
            for (int i = 0; i < n && !needed; i++)
                if (!x[i].isBound() && x[i].contains(v)) needed = true;
            if (!needed) continue;
            sweep(v);
            for (int i = 0; i < n; i++) {
                if (x[i].isBound() || !x[i].contains(v)) continue;
                // "some OTHER variable differs from v": either one before i does
                // (the rest is unconstrained), or none before does and one after
                // i does. Disjoint, so a sum -- never total minus product.
                double m = beliefRep.add(beliefRep.multiply(prefixNE[i], suffixZ[i + 1]),
                        beliefRep.multiply(prefixE[i], suffixNE[i + 1]));
                setLocalBelief(i, v, m);
            }
        }
    }

    @Override
    protected double weightedCounting() {
        collect();
        // partition on the value of x_0: mass(x_0 = v) * mass(some other differs from v)
        double wc = beliefRep.zero();
        for (int v = globalMin; v < globalMin + globalSpan; v++) {
            if (!x[0].isBound() && !x[0].contains(v)) continue;
            if (x[0].isBound() && x[0].min() != v) continue;
            sweep(v);
            wc = beliefRep.add(wc, beliefRep.multiply(e[0], suffixNE[1]));
        }
        Log.constraint("weighted count for " + this.getName() + " constraint: " + wc);
        return wc;
    }
}
