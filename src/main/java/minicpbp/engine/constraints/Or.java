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
import minicpbp.engine.core.BoolVar;
import minicpbp.engine.core.IntVar;
import minicpbp.state.StateInt;
import minicpbp.util.exception.NotImplementedException;

import static minicpbp.util.exception.InconsistencyException.INCONSISTENCY;

/**
 * Logical or constraint {@code  x1 or x2 or ... xn}
 */
public class Or extends AbstractConstraint { // x1 or x2 or ... xn

    private final BoolVar[] x;
    private final int n;
    private StateInt wL; // watched literal left
    private StateInt wR; // watched literal right
    // prefix/suffix products of outsideBelief(i, 0) for leave-one-out in
    // updateBelief(): computing them by DIVIDING the full product is 0/0 =
    // NaN whenever an unbound literal has zero outside mass on false
    // (observed on RamseyPartition via buildCtrNotAllEqual, 2026-08-18);
    // same division-is-not-sum-product lesson as Maximum.
    private double[] prefixFalse;
    private double[] suffixFalse;
    // prefix/suffix "or masses": prefixOr[i] = sum over j<i of
    // P(x_j=1) * prod_{k<j} P(x_k=0), i.e. the mass of "first true literal is
    // j<i"; suffixOr[i] symmetric from the right. Together they give the mass
    // of "at least one other literal is true" as a SUM, never as
    // complement(product): normalizeBelief rounds a dominant belief to exactly
    // 1.0 and complement(1.0) manufactures an exact zero (FIXING_ZEROS.md 3.3).
    private double[] prefixOr;
    private double[] suffixOr;
    private double[] fVal; // P(x_i = 0), robust to bound literals
    private double[] tVal; // P(x_i = 1)


    /**
     * Creates a logical or constraint: at least one variable is true:
     * {@code  x1 or x2 or ... xn}
     *
     * @param x the variables in the scope of the constraint
     */
    public Or(BoolVar[] x) {
        super(x[0].getSolver(), x);
        setName("Or");
        this.x = x;
        this.n = x.length;
        wL = getSolver().getStateManager().makeStateInt(0);
        wR = getSolver().getStateManager().makeStateInt(n - 1);
        prefixFalse = new double[n + 1];
        suffixFalse = new double[n + 1];
        prefixOr = new double[n + 1];
        suffixOr = new double[n + 1];
        fVal = new double[n];
        tVal = new double[n];
        setExactWCounting(true);
    }

    @Override
    public void post() {
        propagate();
    }


    @Override
    public void propagate() {
        // update watched literals
        int i = wL.value();
        while (i < n && x[i].isBound()) {
            if (x[i].isTrue()) {
                setActive(false);
                return;
            }
            i += 1;
        }
        wL.setValue(i);
        i = wR.value();
        while (i >= 0 && x[i].isBound() && i >= wL.value()) {
            if (x[i].isTrue()) {
                setActive(false);
                return;
            }
            i -= 1;
        }
        wR.setValue(i);

        if (wL.value() > wR.value()) {
            throw INCONSISTENCY;
        } else if (wL.value() == wR.value()) { // only one unassigned var
            x[wL.value()].assign(true);
            setActive(false);
        } else {
            assert (wL.value() != wR.value());
            assert (!x[wL.value()].isBound());
            assert (!x[wR.value()].isBound());
    	    switch (getSolver().getMode()) {
                case BP:
                    break;
                case SP:
                case SBP:
		            x[wL.value()].propagateOnBind(this);
		            x[wR.value()].propagateOnBind(this);
	        }
        }
    }

    @Override
    public void updateBelief() {
        int lo = wL.value(), hi = wR.value();
        // per-literal false/true masses, robust to bound literals inside
        // [lo, hi]: a bound variable's outsideBelief is only written at its
        // min, the other slot is stale
        for (int i = lo; i <= hi; i++) {
            if (x[i].isBound()) {
                fVal[i] = x[i].min() == 0 ? beliefRep.one() : beliefRep.zero();
                tVal[i] = x[i].min() == 1 ? beliefRep.one() : beliefRep.zero();
            } else {
                fVal[i] = outsideBelief(i, 0);
                tVal[i] = outsideBelief(i, 1);
            }
        }
        // leave-one-out products of P(x=0) over [lo, hi] by prefix/suffix
        // (see field comment: never by division — an unbound literal may
        // legitimately carry zero outside mass on false), and leave-one-out
        // or-masses by the same construction (never by complement).
        prefixFalse[lo] = beliefRep.one();
        prefixOr[lo] = beliefRep.zero();
        for (int i = lo; i <= hi; i++) {
            prefixFalse[i + 1] = beliefRep.multiply(prefixFalse[i], fVal[i]);
            prefixOr[i + 1] = beliefRep.add(prefixOr[i], beliefRep.multiply(tVal[i], prefixFalse[i]));
        }
        suffixFalse[hi + 1] = beliefRep.one();
        suffixOr[hi + 1] = beliefRep.zero();
        for (int i = hi; i >= lo; i--) {
            suffixFalse[i] = beliefRep.multiply(suffixFalse[i + 1], fVal[i]);
            suffixOr[i] = beliefRep.add(tVal[i], beliefRep.multiply(fVal[i], suffixOr[i + 1]));
        }
        for (int i = lo; i <= hi; i++) {
	        if (!x[i].isBound()) {
                // mass of "some other literal is true", summed directly
                double orOthers = beliefRep.add(prefixOr[i],
                        beliefRep.multiply(prefixFalse[i], suffixOr[i + 1]));
                // mass of "all other literals are false"
                double allFalseOthers = beliefRep.multiply(prefixFalse[i], suffixFalse[i + 1]);
                // x_i = 1 satisfies Or whatever the others do: total mass
                setLocalBelief(i, 1, beliefRep.add(orOthers, allFalseOthers));
                setLocalBelief(i, 0, orOthers);
	        }
	    }
    }

}
