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
 * Domain listeners are passed as argument
 * to the {@link IntDomain} modifier methods.
 */
public interface DomainListener {

    /**
     * Called whenever the domain becomes empty.
     */
    void empty();

    /**
     * Called whenever the domain becomes a single value.
     */
    void bind();

    /**
     * Called whenever the domain loses a value.
     */
    void change();

    /**
     * Called whenever the maximum value of the domain is lost.
     */
    void changeMin();

    /**
     * Called whenever the minmum value of the domain is lost.
     */
    void changeMax();

    /**
     * Called whenever a domain reduction actually shrinks the domain,
     * carrying the mutator that produced the reduction and its argument.
     * Fires after {@link #change()} (and the bound/bind callbacks where
     * applicable). The default implementation is a no-op so existing
     * listeners need not be modified.
     *
     * @param kind  which {@link IntVar} mutator produced the reduction
     * @param value the integer argument that was passed to the mutator
     */
    default void op(DomainOpKind kind, int value) {}
}
