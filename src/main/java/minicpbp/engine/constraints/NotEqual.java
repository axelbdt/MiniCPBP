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

import minicpbp.util.Log;

import minicpbp.engine.core.AbstractConstraint;
import minicpbp.engine.core.IntVar;

/**
 * Not Equal constraint between two variables
 */
public class NotEqual extends AbstractConstraint {
    private final IntVar x, y;
    private final int c;

    /**
     * Creates a constraint such
     * that {@code x != y + c}
     *
     * @param x the left member
     * @param y the right memer
     * @param c the offset value on y
     * @see minicpbp.cp.Factory#notEqual(IntVar, IntVar, int)
     */
    public NotEqual(IntVar x, IntVar y, int c) { // x != y + c
        super(x.getSolver(), new IntVar[]{x, y});
        setName("NotEqual");
        this.x = x;
        this.y = y;
        this.c = c;
        setExactWCounting(true);
    }

    @Override
    public void post() {
        if (y.isBound()) {
            x.remove(y.min() + c);
            setActive(false);
        }
        else if (x.isBound()) {
            y.remove(x.min() - c);
            setActive(false);
        }
        else switch (getSolver().getMode()) {
            case BP:
                break;
            case SP:
            case SBP:
                x.propagateOnBind(this);
                y.propagateOnBind(this);
        }
    }

    @Override
    public void propagate() {
        if (y.isBound())
            x.remove(y.min() + c);
        else y.remove(x.min() - c);
        setActive(false);
    }


    /**
     * Sums of a variable's outside beliefs excluding each value, plus the total.
     * Returned array r has span+1 entries where span = s.max()-s.min()+1:
     * r[i] = sum of outside beliefs over the domain values != s.min()+i,
     * r[span] = sum over the whole domain.
     * Built from a suffix pass and a prefix pass -- additions only. Never
     * compute these as complement(p): normalizeBelief rounds a dominant belief
     * to exactly 1.0, and complement(1.0) manufactures an exact zero
     * (FIXING_ZEROS.md 3.3).
     */
    private double[] othersSum(IntVar s, int pos) {
        int sMin = s.min();
        int span = s.max() - sMin + 1;
        double[] r = new double[span + 1];
        double run = beliefRep.zero();
        for (int i = span - 1; i >= 0; i--) {
            r[i] = run; // sum over values > sMin+i
            if (s.contains(sMin + i))
                run = beliefRep.add(run, outsideBelief(pos, sMin + i));
        }
        r[span] = run; // total
        run = beliefRep.zero();
        for (int i = 0; i < span; i++) {
            r[i] = beliefRep.add(r[i], run); // += sum over values < sMin+i
            if (s.contains(sMin + i))
                run = beliefRep.add(run, outsideBelief(pos, sMin + i));
        }
        return r;
    }

    @Override
    public void updateBelief() {
        // The message to x on vx is P(y != vx - c): sum y's beliefs over its
        // other values directly, never 1 - P(y = vx - c).
        double[] othersY = othersSum(y, 1);
        int yMin = y.min();
        double totalY = othersY[othersY.length - 1];
        for (int vx = x.min(); vx <= x.max(); vx++) {
            if (x.contains(vx)) {
                if (y.contains(vx - c))
                    setLocalBelief(0, vx, othersY[vx - c - yMin]);
                else
                    setLocalBelief(0, vx, totalY);
            }
        }
        // Treatment of y
        double[] othersX = othersSum(x, 0);
        int xMin = x.min();
        double totalX = othersX[othersX.length - 1];
        for (int vy = y.min(); vy <= y.max(); vy++) {
            if (y.contains(vy)) {
                if (x.contains(vy + c))
                    setLocalBelief(1, vy, othersX[vy + c - xMin]);
                else
                    setLocalBelief(1, vy, totalX);
            }
        }
    }

    public double weightedCounting() {
        double weightedCount = beliefRep.zero();
        double[] othersY = othersSum(y, 1);
        int yMin = y.min();
        double totalY = othersY[othersY.length - 1];
        for (int vx = x.min(); vx <= x.max(); vx++) {
            if (x.contains(vx)) {
                if (y.contains(vx - c)) {
                    weightedCount = beliefRep.add(weightedCount,
                                    beliefRep.multiply(outsideBelief(0, vx), othersY[vx - c - yMin]));
                } else {
                    weightedCount = beliefRep.add(weightedCount,
                                    beliefRep.multiply(outsideBelief(0, vx), totalY));
                }
            }
        }
        Log.constraint("weighted count for "+this.getName()+" constraint: "+ weightedCount);
        return weightedCount;
    }

}
