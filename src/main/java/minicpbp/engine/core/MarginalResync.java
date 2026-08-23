/*
 * mini-cpbp: belief-propagation scheduling (BP_SCHEDULING.md)
 */

package minicpbp.engine.core;

/**
 * Recomputes a variable's marginal as the exact product of the messages it
 * currently receives. Implemented by the schedulers, which hold the incidence
 * lists; used by {@link AbstractConstraint#updateMessagesInPlace} where the
 * incremental quotient is not defined.
 */
public interface MarginalResync {
    void resync(IntVar base);
}
