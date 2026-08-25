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

import java.util.ArrayList;
import java.util.Iterator;

/**
 * Generic Stack that can be saved and restored through
 * the {@link StateManager#saveState()} / {@link StateManager#restoreState()}
 * methods.
 */
public class StateStack<E> {

    private StateInt size;
    private ArrayList<E> stack;

    /**
     * Creates a restorable stack.
     * @param sm the state manager that saves/restores the stack
     *         when {@link StateManager#saveState()} / {@link StateManager#restoreState()}
     *         methods are called.
     */
    public StateStack(StateManager sm) {
        size = sm.makeStateInt(0);
        stack = new ArrayList<E>();
    }

    public void push(E elem) {
        int n = size.value();
        // Overwrite rather than insert. The entries at and beyond n were popped
        // by a backtrack and are unreachable, so shifting them right only made
        // the list grow without bound and scrambled the order of what remained.
        if (n < stack.size()) stack.set(n, elem);
        else stack.add(elem);
        size.increment();
    }

    public int size() {
        return size.value();
    }

    public E get(int index) {
        return stack.get(index);
    }

    /**
     * Iterates the {@code size()} live entries.
     * <p>
     * 2026-08-25: this used to return {@code stack.iterator()}, which walks the
     * backing list and so yields every element ever pushed anywhere in the
     * search tree, including those a backtrack has popped. The size is
     * reversible; the list is not. Consequences, all of them real:
     * <ul>
     * <li>{@code BPGraph.rebuild}, {@code MiniCP.coldReset} and
     * {@code MiniCP.warmEntry} iterate {@code getConstraints()}, so belief
     * propagation ran factors that the current path never posted. Combined with
     * the reversible {@code active} flag being restored to true on backtrack, a
     * constraint posted once in some subtree stayed an active BP factor for the
     * rest of the search. Measured on RamseyPartition-3-24, whose model is
     * notAllEqual -&gt; Or(not isEqual(x_i, x_0)) -&gt; 264 IsEqualVar, each of
     * which eagerly constructs Equal(x,y) and NotEqual(x,y): post() ran on those
     * 36 226 times and every single time with the partner already bound, so each
     * filtered and deactivated at once -- yet updateBelief ran 55 691 841 times,
     * always active. BP was asserting disequalities the search had not imposed,
     * which is where that instance's mass of zero beliefs came from.</li>
     * <li>{@code IntVarImpl.scheduleAll} iterates {@code onDomain},
     * {@code onBind} and {@code onBounds}, so a propagator registered by a
     * constraint posted inside a subtree stayed registered after the backtrack.
     * Constraints posted while building the model register at level 0 and are
     * unaffected; the ones posted during search (IsEqualVar's two, and
     * AllDifferentBinary, Disjunctive) are not.</li>
     * </ul>
     */
    public Iterator<E> iterator() {
        final int n = size.value();
        return new Iterator<E>() {
            private int i = 0;

            @Override
            public boolean hasNext() {
                return i < n;
            }

            @Override
            public E next() {
                if (i >= n) throw new java.util.NoSuchElementException();
                return stack.get(i++);
            }
        };
    }
}
