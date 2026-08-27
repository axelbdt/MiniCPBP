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

package minicpbp.state;

/**
 * Implementation of {@link StateDoubleArray} with trail strategy: the first
 * {@link #update()} per {@link Trailer} magic pushes one entry holding a clone
 * of the row; restore swaps the clone back in. Same copy-on-first-write
 * discipline as {@link TrailDouble}, at row granularity.
 *
 * @see Trailer
 * @see StateManager#makeStateDoubleArray(int, double)
 */
public class TrailDoubleArray implements StateDoubleArray {

    class StateEntryArray implements StateEntry {
        private final double[] saved;

        StateEntryArray(double[] saved) {
            this.saved = saved;
        }

        @Override
        public void restore() {
            TrailDoubleArray.this.v = saved;
        }
    }

    private final Trailer trail;
    private double[] v;
    private long lastMagic;

    protected TrailDoubleArray(Trailer trail, int size, double initValue) {
        this.trail = trail;
        v = new double[size];
        java.util.Arrays.fill(v, initValue);
        lastMagic = trail.getMagic() - 1;
    }

    @Override
    public double[] read() {
        return v;
    }

    @Override
    public double[] update() {
        long trailMagic = trail.getMagic();
        if (lastMagic != trailMagic) {
            lastMagic = trailMagic;
            trail.pushState(new StateEntryArray(v.clone()));
        }
        return v;
    }

    @Override
    public int length() {
        return v.length;
    }
}
