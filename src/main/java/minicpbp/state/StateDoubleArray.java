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
 * A restorable array of doubles with one reversibility action per array per
 * level, instead of one per cell (BP_COST_PROFILE.md Part 4 item 5).
 * <p>
 * {@code StateDouble[]} pays a {@code StateEntryDouble} allocation and a
 * virtual call per cell write, plus a double indirection per read. BP rewrites
 * whole rows (one row = one scope position's beliefs over its domain) many
 * times per search node, so the natural unit of reversibility is the row: the
 * first write since the last {@link StateManager#saveState()} snapshots the
 * row once, and every later read or write inside that level is a plain array
 * access.
 * <p>
 * Contract: {@link #read()} returns the live array and the caller must not
 * write through it; {@link #update()} guarantees the pre-write snapshot has
 * been taken for the current level and returns the same live array, which the
 * caller may then write freely until the next {@code saveState()}/
 * {@code restoreState()}. Never cache the returned reference across a
 * save or restore.
 */
public interface StateDoubleArray {

    /**
     * @return the live array, for reading only
     */
    double[] read();

    /**
     * Ensures the current content is restorable to this point, then returns
     * the live array for writing.
     *
     * @return the live array, writable until the next state operation
     */
    double[] update();

    /**
     * @return the array length
     */
    int length();
}
