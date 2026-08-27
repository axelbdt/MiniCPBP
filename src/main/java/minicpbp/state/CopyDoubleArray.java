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
 * Implementation of {@link StateDoubleArray} for the {@link Copier} strategy:
 * every {@code saveState()} snapshots the whole row, as {@link CopyDouble}
 * does for a scalar.
 *
 * @see Copier
 * @see StateManager#makeStateDoubleArray(int, double)
 */
public class CopyDoubleArray implements StateDoubleArray, Storage {

    class StateEntryArray implements StateEntry {
        private final double[] saved;

        StateEntryArray(double[] saved) {
            this.saved = saved;
        }

        @Override
        public void restore() {
            CopyDoubleArray.this.v = saved;
        }
    }

    private double[] v;

    protected CopyDoubleArray(int size, double initValue) {
        v = new double[size];
        java.util.Arrays.fill(v, initValue);
    }

    @Override
    public double[] read() {
        return v;
    }

    @Override
    public double[] update() {
        return v; // save() snapshots unconditionally at every saveState()
    }

    @Override
    public int length() {
        return v.length;
    }

    @Override
    public StateEntry save() {
        return new StateEntryArray(v.clone());
    }
}
