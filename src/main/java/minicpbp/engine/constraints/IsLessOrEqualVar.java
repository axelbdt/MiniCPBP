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
import minicpbp.engine.core.Constraint;
import minicpbp.engine.core.IntVar;
import minicpbp.util.exception.NotImplementedException;

import static minicpbp.cp.Factory.lessOrEqual;
import static minicpbp.cp.Factory.plus;

/**
 * Reified is less or equal constraint {@code b <=> x <= y}.
 */
public class IsLessOrEqualVar extends AbstractConstraint {

    private final BoolVar b;
    private final IntVar x;
    private final IntVar y;

    private final Constraint lEqC;
    private final Constraint grC;

    /**
     * Creates a reified is less or equal constraint {@code b <=> x <= y}.
     *
     * @param b the truth value that will be set to true if {@code x <= y}, false otherwise
     * @param x left hand side of less or equal operator
     * @param y right hand side of less or equal operator
     */
    public IsLessOrEqualVar(BoolVar b, IntVar x, IntVar y) {
        super(b.getSolver(), new IntVar[]{b, x, y});
        setName("IsLessOrEqualVar");
        this.b = b;
        this.x = x;
        this.y = y;
        lEqC = lessOrEqual(x, y);
        grC = lessOrEqual(plus(y, 1), x);
        setExactWCounting(true);
    }

    @Override
    public void post() {
        switch (getSolver().getMode()) {
            case BP:
                break;
            case SP:
            case SBP:
                x.propagateOnBoundChange(this);
                y.propagateOnBoundChange(this);
		b.propagateOnBind(this);
        }
        propagate();
    }

    @Override
    public void propagate() {
        if (b.isTrue()) {
            getSolver().post(lEqC, false);
            setActive(false);
        } else if (b.isFalse()) {
            getSolver().post(grC, false);
            setActive(false);
        } else {
            if (x.max() <= y.min()) {
                b.assign(1);
                setActive(false);
            } else if (x.min() > y.max()) {
                b.assign(0);
                setActive(false);
            }
        }
    }


    @Override
    public void updateBelief() {
        // Every complement is accumulated directly as the mass of its own set
        // (P(y < vx) alongside P(y >= vx), etc.), never as 1 - p:
        // normalizeBelief rounds a dominant belief to exactly 1.0, and
        // complement(1.0) manufactures an exact zero (FIXING_ZEROS.md 3.3).
        double beliefSAT = beliefRep.zero();   // that x<=y is satisfied
        double beliefUNSAT = beliefRep.zero(); // that x>y is satisfied
        int vx, vy;
        // Treatment of x: descending scan accumulates P(y >= vx),
        // ascending scan accumulates P(y < vx). Two registers, no subtraction.
        int yMin = y.min();
        int ySpan = y.max() - yMin + 1;
        double[] geqY = new double[ySpan]; // geqY[i] = P(y >= yMin+i)
        double[] ltY = new double[ySpan];  // ltY[i]  = P(y <  yMin+i)
        double run = beliefRep.zero();
        for (int i = ySpan - 1; i >= 0; i--) {
            if (y.contains(yMin + i))
                run = beliefRep.add(run, outsideBelief(2, yMin + i));
            geqY[i] = run;
        }
        double totalY = run;
        run = beliefRep.zero();
        for (int i = 0; i < ySpan; i++) {
            ltY[i] = run;
            if (y.contains(yMin + i))
                run = beliefRep.add(run, outsideBelief(2, yMin + i));
        }
        for (vx = x.min(); vx <= x.max(); vx++) {
            if (x.contains(vx)) {
                double pGE, pLT; // P(y >= vx), P(y < vx)
                if (vx <= yMin) {
                    pGE = totalY;
                    pLT = beliefRep.zero();
                } else if (vx > y.max()) {
                    pGE = beliefRep.zero();
                    pLT = totalY;
                } else {
                    pGE = geqY[vx - yMin];
                    pLT = ltY[vx - yMin];
                }
                beliefSAT = beliefRep.add(beliefSAT, beliefRep.multiply(pGE, outsideBelief(1, vx)));
                beliefUNSAT = beliefRep.add(beliefUNSAT, beliefRep.multiply(pLT, outsideBelief(1, vx)));
                setLocalBelief(1, vx, beliefRep.add( beliefRep.multiply(pGE, outsideBelief(0,1)),
						     beliefRep.multiply(pLT, outsideBelief(0,0)) ));
            }
        }
        // Treatment of y: P(x <= vy) ascending, P(x > vy) descending.
        int xMin = x.min();
        int xSpan = x.max() - xMin + 1;
        double[] leX = new double[xSpan]; // leX[i] = P(x <= xMin+i)
        double[] gtX = new double[xSpan]; // gtX[i] = P(x >  xMin+i)
        run = beliefRep.zero();
        for (int i = 0; i < xSpan; i++) {
            if (x.contains(xMin + i))
                run = beliefRep.add(run, outsideBelief(1, xMin + i));
            leX[i] = run;
        }
        double totalX = run;
        run = beliefRep.zero();
        for (int i = xSpan - 1; i >= 0; i--) {
            gtX[i] = run;
            if (x.contains(xMin + i))
                run = beliefRep.add(run, outsideBelief(1, xMin + i));
        }
        for (vy = y.min(); vy <= y.max(); vy++) {
            if (y.contains(vy)) {
                double pLE, pGT; // P(x <= vy), P(x > vy)
                if (vy >= x.max()) {
                    pLE = totalX;
                    pGT = beliefRep.zero();
                } else if (vy < xMin) {
                    pLE = beliefRep.zero();
                    pGT = totalX;
                } else {
                    pLE = leX[vy - xMin];
                    pGT = gtX[vy - xMin];
                }
                setLocalBelief(2, vy, beliefRep.add( beliefRep.multiply(pLE, outsideBelief(0,1)),
						     beliefRep.multiply(pGT, outsideBelief(0,0)) ));
            }
        }
        // Treatment of b: both sides are accumulated sums, not 1 - the other
	    setLocalBelief(0, 1, beliefSAT);
	    setLocalBelief(0, 0, beliefUNSAT);
    }
}
