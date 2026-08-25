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
import minicpbp.util.ArrayUtil;
import minicpbp.util.exception.NotImplementedException;

/**
 * Reified logical or constraint
 */
public class IsOr extends AbstractConstraint { // b <=> x1 or x2 or ... xn

    private final BoolVar b;
    private final BoolVar[] x;
    private final int n;

    private int[] unBounds;
    private StateInt nUnBounds;

    private final Or or;

    // prefix/suffix over the unBounds list for leave-one-out masses in
    // updateBelief(), computed as SUMS AND PRODUCTS ONLY: the old circuit used
    // complement(divide(allFalse, f_i)), which is 0/0 = NaN on a zero divisor
    // and manufactures an exact zero whenever normalizeBelief rounded a
    // dominant belief to 1.0 (FIXING_ZEROS.md 3.3). prefixOr[i] is the mass of
    // "first true literal is at list position < i"; suffixOr[i] symmetric.
    private double[] prefixFalse;
    private double[] suffixFalse;
    private double[] prefixOr;
    private double[] suffixOr;
    private double[] fVal; // P(x_idx = 0), robust to bound literals
    private double[] tVal; // P(x_idx = 1)

    /**
     * Creates a constraint such that
     * the boolean b is true if and only if
     * at least one variable in x is true.
     *
     * @param b the boolean that is true if at least one variable in x is true
     * @param x a non empty array of variables
     */
    public IsOr(BoolVar b, BoolVar[] x) {
        super(b.getSolver(), ArrayUtil.append(b,x));
        setName("IsOr");
        this.b = b;
        this.x = x;
        this.n = x.length;
        or = new Or(x);

        nUnBounds = getSolver().getStateManager().makeStateInt(n);
        unBounds = new int[n];
        for (int i = 0; i < n; i++) {
            unBounds[i] = i;
        }
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
	    switch (getSolver().getMode()) {
	    case BP:
	        break;
	    case SP:
	    case SBP:
	        if (isActive()) {
		        b.propagateOnBind(this);
		        for (BoolVar xi : x) {
		            xi.propagateOnBind(this);
		        }
	        }
	    }
    }

    @Override
    public void propagate() {
        if (b.isTrue()) {
            setActive(false);
            getSolver().post(or, false);
        } else if (b.isFalse()) {
            for (BoolVar xi : x) {
                xi.assign(false);
            }
            setActive(false);
        } else {
            int nU = nUnBounds.value();
            for (int i = nU - 1; i >= 0; i--) {
                int idx = unBounds[i];
                BoolVar y = x[idx];
                if (y.isBound()) {
                    if (y.isTrue()) {
                        b.assign(true);
                        setActive(false);
                        return;
                    }
                    // Swap the variable
                    unBounds[i] = unBounds[nU - 1];
                    unBounds[nU - 1] = idx;
                    nU--;
                }
            }
            if (nU == 0) {
                b.assign(false);
                setActive(false);
            }
            nUnBounds.setValue(nU);
        }
    }

    @Override
    public void updateBelief() {
        int nU = nUnBounds.value();
        // per-literal false/true masses, robust to literals of the list that
        // are bound in BP mode: a bound variable's outsideBelief is only
        // written at its min, the other slot is stale
        for (int i = 0; i < nU; i++) {
            BoolVar xi = x[unBounds[i]];
            if (xi.isBound()) {
                fVal[i] = xi.min() == 0 ? beliefRep.one() : beliefRep.zero();
                tVal[i] = xi.min() == 1 ? beliefRep.one() : beliefRep.zero();
            } else {
                fVal[i] = outsideBelief(1 + unBounds[i], 0);
                tVal[i] = outsideBelief(1 + unBounds[i], 1);
            }
        }
        // leave-one-out all-false products and or-masses by prefix/suffix:
        // sums and products only, no division, no complement
        prefixFalse[0] = beliefRep.one();
        prefixOr[0] = beliefRep.zero();
        for (int i = 0; i < nU; i++) {
            prefixFalse[i + 1] = beliefRep.multiply(prefixFalse[i], fVal[i]);
            prefixOr[i + 1] = beliefRep.add(prefixOr[i], beliefRep.multiply(tVal[i], prefixFalse[i]));
        }
        suffixFalse[nU] = beliefRep.one();
        suffixOr[nU] = beliefRep.zero();
        for (int i = nU - 1; i >= 0; i--) {
            suffixFalse[i] = beliefRep.multiply(suffixFalse[i + 1], fVal[i]);
            suffixOr[i] = beliefRep.add(tVal[i], beliefRep.multiply(fVal[i], suffixOr[i + 1]));
        }
        // Treatment of x
	    for (int i = 0; i < nU; i++) {
	        int idx = unBounds[i];
	        if (!x[idx].isBound()) { // in case of BP mode
                // mass of "some other literal is true" / "all others false"
                double orOthers = beliefRep.add(prefixOr[i],
                        beliefRep.multiply(prefixFalse[i], suffixOr[i + 1]));
                double allFalseOthers = beliefRep.multiply(prefixFalse[i], suffixFalse[i + 1]);
                // x_idx = 1 forces the or true, so b must be 1; others free
                setLocalBelief(1+idx, 1, beliefRep.multiply(outsideBelief(0,1),
                        beliefRep.add(orOthers, allFalseOthers)));
                // x_idx = 0: b=1 needs another true literal, b=0 needs all
                // others false (the old circuit dropped the allFalseOthers
                // factor on the b=0 term)
                setLocalBelief(1+idx, 0, beliefRep.add(
                        beliefRep.multiply(outsideBelief(0,1), orOthers),
                        beliefRep.multiply(outsideBelief(0,0), allFalseOthers)));
	        }
	    }
        // Treatment of b: the or-mass is a sum, not complement(product)
	    setLocalBelief(0, 1, prefixOr[nU]);
	    setLocalBelief(0, 0, prefixFalse[nU]);
    }

}
