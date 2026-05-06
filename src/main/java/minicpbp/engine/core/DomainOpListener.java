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
 * Listener notified each time a variable's domain is actually reduced by one
 * of the {@link IntVar} mutators. Calls that have no effect on the domain do
 * not fire this listener.
 *
 * @see Solver#onDomainOp(DomainOpListener)
 */
@FunctionalInterface
public interface DomainOpListener {

    /**
     * @param x     the variable whose domain was reduced
     * @param kind  which {@link IntVar} mutator produced the reduction
     * @param value the integer argument that was passed to the mutator
     */
    void onOp(IntVar x, DomainOpKind kind, int value);
}
