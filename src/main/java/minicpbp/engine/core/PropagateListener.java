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

package minicpbp.engine.core;

/**
 * Listener notified immediately before each constraint's
 * {@link Constraint#propagate()} is invoked from inside the fixPoint loop.
 *
 * @see Solver#onPropagateConstraint(PropagateListener)
 */
@FunctionalInterface
public interface PropagateListener {

    /**
     * @param c the constraint whose {@code propagate()} is about to be called
     */
    void onPropagate(Constraint c);
}
