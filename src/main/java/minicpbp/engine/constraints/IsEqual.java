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


/**
 * Reified equality constraint
 *
 * @see minicpbp.cp.Factory#isEqual(IntVar, int)
 */
public class IsEqual extends AbstractConstraint { // b <=> x == c

    private final BoolVar b;
    private final IntVar x;
    private final int c;

    /**
     * Returns a boolean variable representing
     * whether one variable is equal to the given constant.
     *
     * @param x the variable
     * @param c the constant
     * @param b the boolean variable that is set to true
     *          if and only if x takes the value c
     * @see minicpbp.cp.Factory#isEqual(IntVar, int)
     */
    public IsEqual(BoolVar b, IntVar x, int c) {
        super(b.getSolver(), new IntVar[]{b, x});
        setName("IsEqual");
        this.b = b;
        this.x = x;
        this.c = c;
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
		        x.propagateOnDomainChange(this);
		        b.propagateOnBind(this);
	        }
	    }
    }

    @Override
    public void propagate() {
        if (b.isTrue()) {
            x.assign(c);
            setActive(false);
        } else if (b.isFalse()) {
            x.remove(c);
            setActive(false);
        } else if (!x.contains(c)) {
            b.assign(false);
            setActive(false);
        } else if (x.isBound()) {
            b.assign(true);
            setActive(false);
        }
    }

    @Override
    public void updateBelief() {
        // P(x != c) is accumulated as the sum of x's other beliefs, never as
        // complement(P(x = c)): normalizeBelief rounds a dominant belief to
        // exactly 1.0 and complement(1.0) manufactures an exact zero
        // (FIXING_ZEROS.md 3.3).
        double pEq = beliefRep.zero(), pNeq = beliefRep.zero();
        int nVal = x.fillArray(domainValues);
	    for (int k = 0; k < nVal; k++) {
	        int v = domainValues[k];
	        if (v == c) {
	            pEq = outsideBelief(1, c);
	            setLocalBelief(1, v, outsideBelief(0, 1));
	        } else {
	            pNeq = beliefRep.add(pNeq, outsideBelief(1, v));
	            setLocalBelief(1, v, outsideBelief(0, 0));
	        }
	    }
        // Treatment of b (pEq stays zero when c is outside x's domain,
        // instead of reading a possibly stale belief slot)
	    setLocalBelief(0, 1, pEq);
	    setLocalBelief(0, 0, pNeq);
    }

}
