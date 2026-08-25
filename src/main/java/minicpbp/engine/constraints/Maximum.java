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
 */


package minicpbp.engine.constraints;

import minicpbp.engine.core.AbstractConstraint;
import minicpbp.engine.core.IntVar;
import minicpbp.util.ArrayUtil;

import java.util.Arrays;

/**
 * Maximum Constraint
 */
public class Maximum extends AbstractConstraint {

    private final IntVar[] x;
    private final IntVar y;
    private int n;
    private double[][] beliefLessOrEqual;
    private int offset;
    /*
     * 2026-08-25 (FIXING_ZEROS.md 3.4): the old circuit telescoped with SIGNED
     * differences -- yBeliefDifference[v] = ob_y(v) - ob_y(v+1) can be
     * negative, the running sum accumulated signed terms, and y's message was
     * a literal difference of adjacent CDF products. Catastrophic cancellation
     * turned tiny true masses into exact zeros (and small negatives that
     * setLocalBelief clamps). Everything below is sums and products only,
     * built from the "first variable attaining the maximum" decomposition:
     *   B(w)   = mass(all x <= w and some x = w)
     *          = sum_j P(x_j = w) * prod_{k<j} P(x_k < w) * prod_{k>j} P(x_k <= w)
     * with prefix/suffix registers giving the same leave-one-out masses
     * D_i(w) (over j != i) in O(n) per value -- same total O(n * range) as the
     * old code. Leave-one-out by division stays banned (0/0 = NaN whenever a
     * variable has zero mass below a value; observed on DeBruijn 2026-08-18).
     */
    private double[] pF, pG, pOr;   // prefix: all-below, all-at-most, first-max-in-prefix
    private double[] sG, sOr;       // suffix: all-at-most, first-max-in-suffix
    private double[] tw, fw, gw;    // per-variable masses at the current value
    private double[] runningTail;   // sum over w > v of ob_y(w) * D_i(w)

    /**
     * Creates the maximum constraint y = maximum(x[0],x[1],...,x[n])
     *
     * @param x the variable on which the maximum is to be found
     * @param y the variable that is equal to the maximum on x
     */
    public Maximum(IntVar[] x, IntVar y) {
        super(x[0].getSolver(), ArrayUtil.append(x, y));
        setName("Maximum");
        n = x.length;
        assert (n > 0);
        this.x = x;
        this.y = y;
        setExactWCounting(true);
    }

    @Override
    public void post() {
        for (IntVar xi : x) {
            xi.propagateOnBoundChange(this);
        }
        y.propagateOnBoundChange(this);
        propagate();
        // determine range of domain values; sufficient to look at x[i]'s because y was narrowed to [max(min),..,max(max)] in propagate()
        // TODO: give up on updateBellief() if range is too large?
        int max = Integer.MIN_VALUE;
        int min = Integer.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            if (x[i].max() > max) {
                max = x[i].max();
            }
            if (x[i].min() < min) {
                min = x[i].min();
            }
        }
        beliefLessOrEqual = new double[n][max-min+1];
        offset = min;
        pF = new double[n + 1];
        pG = new double[n + 1];
        pOr = new double[n + 1];
        sG = new double[n + 1];
        sOr = new double[n + 1];
        tw = new double[n];
        fw = new double[n];
        gw = new double[n];
        runningTail = new double[n];
    }


    @Override
    public void propagate() {
        int max = Integer.MIN_VALUE;
        int min = Integer.MIN_VALUE;
        int nSupport = 0;
        int supportIdx = -1;
        for (int i = 0; i < n; i++) {
            x[i].removeAbove(y.max());

            if (x[i].max() > max) {
                max = x[i].max();
            }
            if (x[i].min() > min) {
                min = x[i].min();
            }

            if (x[i].max() >= y.min()) {
                nSupport += 1;
                supportIdx = i;
            }
        }
        if (nSupport == 1) {
            x[supportIdx].removeBelow(y.min());
        }
        y.removeAbove(max);
        y.removeBelow(min);
    }

    public void updateBelief() {
        // CDFs: beliefLessOrEqual[i][j] = P(x_i <= j+offset)
        int rangeLen = beliefLessOrEqual[0].length;
        for (int j = 0; j < rangeLen; j++) {
            int v = j+offset;
            for (int i = 0; i < n; i++) {
                beliefLessOrEqual[i][j] = (j==0? beliefRep.zero() : beliefLessOrEqual[i][j-1]);
                if (x[i].contains(v)) {
                    beliefLessOrEqual[i][j] = beliefRep.add( beliefLessOrEqual[i][j], outsideBelief(i, v));
                }
            }
        }
        int yMin = y.min(), yMax = y.max();
        // x values above y.max() cannot be the maximum: true zeros (the old
        // code left those local beliefs stale)
        for (int i = 0; i < n; i++) {
            for (int v = x[i].max(); v > yMax; v--) {
                if (x[i].contains(v)) {
                    setLocalBelief(i, v, beliefRep.zero());
                }
            }
        }
        for (int i = 0; i < n; i++) {
            runningTail[i] = beliefRep.zero();
        }
        // one descending sweep over the range of y
        for (int w = yMax; w >= yMin; w--) {
            int j = w - offset;
            double obY = (y.contains(w) ? outsideBelief(n, w) : beliefRep.zero());
            // per-variable masses at w
            for (int i = 0; i < n; i++) {
                tw[i] = (x[i].contains(w) ? outsideBelief(i, w) : beliefRep.zero()); // P(x_i = w)
                fw[i] = (j == 0 ? beliefRep.zero() : beliefLessOrEqual[i][j-1]);     // P(x_i < w)
                gw[i] = beliefLessOrEqual[i][j];                                     // P(x_i <= w)
            }
            // prefix/suffix registers of the first-max decomposition
            pF[0] = beliefRep.one();
            pG[0] = beliefRep.one();
            pOr[0] = beliefRep.zero();
            for (int i = 0; i < n; i++) {
                pOr[i+1] = beliefRep.add(beliefRep.multiply(pOr[i], gw[i]), beliefRep.multiply(tw[i], pF[i]));
                pF[i+1] = beliefRep.multiply(pF[i], fw[i]);
                pG[i+1] = beliefRep.multiply(pG[i], gw[i]);
            }
            sG[n] = beliefRep.one();
            sOr[n] = beliefRep.zero();
            for (int i = n - 1; i >= 0; i--) {
                sOr[i] = beliefRep.add(beliefRep.multiply(tw[i], sG[i+1]), beliefRep.multiply(fw[i], sOr[i+1]));
                sG[i] = beliefRep.multiply(gw[i], sG[i+1]);
            }
            // belief for y=w: mass(all x <= w and some x = w), summed directly
            if (y.contains(w)) {
                setLocalBelief(n, w, pOr[n]);
            }
            // beliefs for the x[i] at w, and the tails for the values below
            for (int i = 0; i < n; i++) {
                // all others <= w
                double othersAtMost = beliefRep.multiply(pG[i], sG[i+1]);
                // others <= w and some other = w
                double othersMaxIs = beliefRep.add(beliefRep.multiply(pOr[i], sG[i+1]),
                                                   beliefRep.multiply(pF[i], sOr[i+1]));
                if (x[i].contains(w)) {
                    // belief for x[i]=w is (y=w and all other x[j]<=w)
                    // + (y=w'>w, all other x[j]<=w', some other x[k]=w'): the tail
                    setLocalBelief(i, w, beliefRep.add(beliefRep.multiply(obY, othersAtMost), runningTail[i]));
                }
                runningTail[i] = beliefRep.add(runningTail[i], beliefRep.multiply(obY, othersMaxIs));
            }
        }
        // belief for x[i]=v<y.min() is (y=w, all other x[j]<=w, some other x[k]=w)
        // summed over y's whole range: the full tail
        for (int i = 0; i < n; i++) {
            for (int v = yMin - 1; v >= x[i].min(); v--) {
                if (x[i].contains(v)) {
                    setLocalBelief(i, v, runningTail[i]);
                }
            }
        }
    }

}
