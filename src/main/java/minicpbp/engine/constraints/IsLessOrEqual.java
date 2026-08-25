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
import minicpbp.util.exception.NotImplementedException;

/**
 * Reified less or equal constraint.
 */
public class IsLessOrEqual extends AbstractConstraint { // b <=> x <= c

    private final BoolVar b;
    private final IntVar x;
    private final int c;

    /**
     * Creates a constraint that
     * link a boolean variable representing
     * whether one variable is less or equal to the given constant.
     *
     * @param b a boolean variable that is true if and only if
     *          x takes a value less or equal to c
     * @param x the variable
     * @param c the constant
     * @see minicpbp.cp.Factory#isLessOrEqual(IntVar, int)
     */
    public IsLessOrEqual(BoolVar b, IntVar x, int c) {
        super(b.getSolver(), new IntVar[]{b, x});
        setName("IsLessOrEqual");
        this.b = b;
        this.x = x;
        this.c = c;
        setExactWCounting(true);
    }

    @Override
    public void post() {
        if (b.isTrue()) {
            x.removeAbove(c);
            setActive(false);
        } else if (b.isFalse()) {
            x.removeBelow(c + 1);
            setActive(false);
        } else if (x.max() <= c) {
            b.assign(1);
            setActive(false);
        } else if (x.min() > c) {
            b.assign(0);
            setActive(false);
        } else {
            b.whenBind(() -> {
                if (b.isTrue()) {
                    x.removeAbove(c);
                } else {
                    x.removeBelow(c + 1);
                }
		setActive(false);
            });
            x.whenBoundsChange(() -> {
                if (x.max() <= c) {
                    b.assign(1);
		    setActive(false);
                } else if (x.min() > c) {
                    b.assign(0);
		    setActive(false);
                }
            });
        }
    }

    @Override
    public void updateBelief() {
        // P(x > c) is accumulated as its own sum, never as
        // complement(P(x <= c)): normalizeBelief rounds a dominant belief to
        // exactly 1.0 and complement(1.0) manufactures an exact zero
        // (FIXING_ZEROS.md 3.3).
	    double beliefLE = beliefRep.zero(), beliefGT = beliefRep.zero();
	    int vx;
        int nVal = x.fillArray(domainValues);
	    for (int k = 0; k < nVal; k++) {
	        vx = domainValues[k];
	        if (vx <= c) {
		        beliefLE = beliefRep.add(beliefLE, outsideBelief(1, vx));
		        setLocalBelief(1, vx, outsideBelief(0, 1));
	        } else {
		        beliefGT = beliefRep.add(beliefGT, outsideBelief(1, vx));
		        setLocalBelief(1, vx, outsideBelief(0, 0));
	        }
	    }
        // Treatment of b
	    setLocalBelief(0, 1, beliefLE);
	    setLocalBelief(0, 0, beliefGT);
    }

}
