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
        // leave-one-out products of outsideBelief(., 0) over [lo, hi] by
        // prefix/suffix (see field comment: never by division — an unbound
        // literal may legitimately carry zero outside mass on false).
        prefixFalse[lo] = beliefRep.one();
        for (int i = lo; i <= hi; i++)
            prefixFalse[i + 1] = beliefRep.multiply(prefixFalse[i], outsideBelief(i, 0));
        suffixFalse[hi + 1] = beliefRep.one();
        for (int i = hi; i >= lo; i--)
            suffixFalse[i] = beliefRep.multiply(suffixFalse[i + 1], outsideBelief(i, 0));
        for (int i = lo; i <= hi; i++) {
	        if (!x[i].isBound()) {
		        // will be normalized
		        setLocalBelief(i, 1, beliefRep.one());
		        setLocalBelief(i, 0, beliefRep.complement(
		                beliefRep.multiply(prefixFalse[i], suffixFalse[i + 1])));
	        }
	    }
    }

}
