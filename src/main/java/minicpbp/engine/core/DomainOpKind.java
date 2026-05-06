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
 * Identifies which {@link IntVar} mutator produced a domain reduction. The
 * value carried alongside the kind is the literal argument to that mutator,
 * so an event log of {@code (kind, value)} pairs replays the variable's
 * domain history without snapshotting.
 */
public enum DomainOpKind {
    /** {@link IntVar#remove(int)} — value v removed. */
    REMOVE,
    /** {@link IntVar#assign(int)} — domain reduced to the single value v. */
    ASSIGN,
    /** {@link IntVar#removeBelow(int)} — values strictly below v removed. */
    REMOVE_BELOW,
    /** {@link IntVar#removeAbove(int)} — values strictly above v removed. */
    REMOVE_ABOVE
}
