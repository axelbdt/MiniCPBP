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

package minicpbp.engine.core;

import minicpbp.state.StateManager;

/**
 * Interface implemented by every Constraint
 * @see AbstractConstraint
 */
public interface Constraint {

    /**
     * Initializes the constraint when it is posted to the solver.
     */
    void post();

    /**
     * Propagates the constraint.
     */
    void propagate();

    /**
     * Set the status of the constraint as
     * scheduled to be propagated by the fix-point.
     * This method Called by the solver when the constraint
     * is enqueued in the propagation queue and is not
     * intended to be called by the user.
     *
     * @param scheduled a value that is true when the constraint
     *                  is enqueued in the propagation queue,
     *                  false when dequeued
     * @see Solver#fixPoint()
     */
    void setScheduled(boolean scheduled);

    /**
     * Returns the schedule status in the fix-point.
     * @return the last {@link #setScheduled(boolean)} given to setScheduled
     */
    boolean isScheduled();

    /**
     * Activates or deactivates the constraint such that it is not scheduled any more.
     * <p>Typically called by the Constraint to let the solver know
     * it should not be scheduled any more when it is subsumed.
     * <p>By default the constraint is active.
     * @param active the status to be set,
     *               this state is reversible and unset
     *               on state restoration {@link StateManager#restoreState()}
     *
     */
    void setActive(boolean active);

    /**
     * Returns the active status of the constraint.
     * @return the last setValue passed to {{@link #setActive(boolean)}
     *         in this state frame {@link StateManager#restoreState()}.
     */
    boolean isActive();

    String getName();
    void setName(String name);

    /**
     * Diverts this constraint's scheduling away from the solver's global
     * propagation queue: when set, {@link Solver#schedule(Constraint)} hands
     * the constraint to the given consumer instead of enqueueing it. Used by
     * encapsulating constraints (e.g.
     * {@link minicpbp.engine.constraints.Intension}) whose internal
     * constraints must reach a local fixpoint inside the owner's
     * {@code propagate()} rather than appear in the outer network.
     *
     * @param s the local scheduler, or null to restore global scheduling
     */
    default void setLocalScheduler(java.util.function.Consumer<Constraint> s) {
        throw new minicpbp.util.exception.NotImplementedException("setLocalScheduler");
    }

    /**
     * @return the local scheduler set by {@link #setLocalScheduler}, or null
     * if this constraint is scheduled in the solver's global queue
     */
    default java.util.function.Consumer<Constraint> localScheduler() {
        return null;
    }

    /************* BP services *************/

    /**
     * Collects messages (outside beliefs) from the variables in its scope.
     */
    void receiveMessages();

    /**
     * Updates its local belief (given the outside beliefs) and sends it 
     * as messages to the variables in its scope.
     */
    void sendMessages();

    /**
     * In-place counterpart of {@code receiveMessages()} then
     * {@code sendMessages()}: the new outgoing messages are published into the
     * marginals of the scope at once, so a factor executed later in the same
     * sweep reads them (Gauss-Seidel rather than Jacobi).
     *
     * @param resync rebuilds a marginal from all the messages it receives,
     *               for the cases where the incremental update cannot
     *               reconstruct it; may be null
     * @return the largest absolute change of any message sent, in standard
     * representation
     */
    double updateMessagesInPlace(MarginalResync resync);

    /**
     * How far the message last sent to the variable at scope position
     * {@code i} moved, in standard representation.
     */
    double messageResidual(int i);

    /**
     * Multiplies the current local belief into the marginals of the scope
     * variables whose base variable is {@code base}: the send half of
     * {@code sendMessages()}, without the weighted counting.
     */
    void contributeMarginal(IntVar base);

    /**
     * Multiplies the current local belief into the marginals of every unbound
     * variable of the scope: {@code contributeMarginal} for all of them at
     * once. Used to re-establish {@code b(v) = prod_c local_c(v)} at the entry
     * of a warm-started invocation, over the factors that are still active
     * (BP_WARM_START_EXPERIMENT.md F1).
     */
    void contributeMarginals();

    /**
     * True while the message this constraint has stored is not the message it
     * would compute from its current inputs — because the previous invocation
     * ended before executing it, or because it has never run. Trailed, so it
     * describes the current path. Only read under incremental dirty seeding
     * (BP_WARM_START_EXPERIMENT.md section 1.3), where forgetting it would be
     * unsound rather than merely imprecise.
     */
    boolean bpStale();

    /** @see #bpStale() */
    void setBpStale(boolean stale);

    /**
     * Sets the local belief to uniform distribution.
     */
    void resetLocalBelief();

    /**
     * Sets the outside belief to uniform distribution.
     */
    void resetOutsideBelief();

    /**
     * Sets the constraint's weight to a nonnegative value.
     * w > 1 amplifies deviations from the uniform belief;
     * w < 1 dampens deviations from the uniform belief.
     */
    void setWeight(double w);

    /**
     * Returns the constraint's weight
     */
    double weight();

    /**
     * @return the variables in the scope of the constraint
     */
    IntVar[] getScope();

    /**
     * @return the constraint's arity
     */
    int arity();
    /**
     * @return the constraint's dynamic arity, i.e. the number of unbound variables in its scope
     */
    int dynamicArity();

    int getFailureCount();
    void incrementFailureCount();

    /**
     * Collects messages (outside beliefs) from the variables in its scope.
     * Used to compute a loss function via weighted counting
     */
    void receiveMessagesWCounting();

    /**
     * Optionally computes and sets the marginals of auxiliary variables created in the constraint's implementation.
     */
    void setAuxVarsMarginalsWCounting();

    /**
     * @return semantic loss, computed using weighted model counting.
     */
    double loss();

    /**
     * Computes gradients for variable/value pairs from the constraints given outside beliefs.
     */
    void gradients();

}
