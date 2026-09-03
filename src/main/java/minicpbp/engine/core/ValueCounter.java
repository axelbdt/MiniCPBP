/*
 * mini-cpbp, replacing classic propagation by belief propagation
 *
 * Amendment A5 (MDD_COUNTING_PLAN.md §6.3): count-driven value ordering
 * without the BP engine.
 */

package minicpbp.engine.core;

/**
 * A constraint that can rank the values of one of its variables by a
 * weighted count of its own solutions, with uniform incoming messages: the
 * solution density of counting-based search (Pesant, Quimper &amp; Zanarini,
 * JAIR 2012) restricted to this constraint. Used by
 * {@code BranchingScheme.domWdegCountValue} in SP mode, where no marginal is
 * maintained and the BP engine never runs: the price per node is the count
 * of the constraints incident to the branching variable, nothing else.
 */
public interface ValueCounter {

    /**
     * Relative scores of the current domain values of {@code x} (a variable of
     * this constraint's scope, possibly through a view), in the order
     * {@code x.fillArray} returns them.
     *
     * @return false if the constraint has no opinion (scores untouched)
     */
    boolean valueScores(IntVar x, int[] values, int n, double[] scores);
}
