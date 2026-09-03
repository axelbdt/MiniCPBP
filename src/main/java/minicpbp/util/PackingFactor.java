/*
 * mini-cpbp, replacing classic propagation by belief propagation
 *
 * Candidate-interval counting for Cumulative / Disjunctive
 * (MDD_COUNTING_PLAN.md §1.1, brief MDD_COUNTING_BRIEF.md §4).
 */

package minicpbp.util;

/**
 * The resource ("column") side of the candidate-interval factor graph.
 *
 * <p>The graph has one Boolean z_a per candidate a = (job i, start s), one
 * exactly-one factor E_i per job over its candidates, and one factor R over
 * every candidate encoding the resource. {@link ExactlyOneRows} owns the E_i
 * side (Williams &amp; Lau's row update) and calls {@code update} once per inner
 * sweep with the incoming odds lambda_a = m_{E_i -> z_a}(1) / m_{E_i -> z_a}(0);
 * the implementation writes the outgoing odds r_a = m_{R -> z_a}(1) /
 * m_{R -> z_a}(0) for every live candidate of the table.
 *
 * <p>Contract: only candidates with {@code table.live[a]} are read or written;
 * lambda is in [0, MU_MAX]; r must be finite, non-negative and at most
 * {@link ExactlyOneRows#MU_MAX} (a forced candidate, m(0) = 0, is capped there,
 * never infinite); a candidate with no feasible completion gets exactly 0.
 */
public interface PackingFactor {

    /** short name for statistics */
    String name();

    /**
     * Maps incoming odds to outgoing odds over the live candidates of the
     * table. The table's live set is fixed for the duration of one
     * {@link ExactlyOneRows#run} call.
     *
     * @return false on a numerical failure (NaN/Inf met); r is then unusable
     */
    boolean update(CandidateTable table, double[] lambda, double[] r);

    /** structural width of the last update (max nodes in a layer), or 1 */
    default int lastWidth() {
        return 1;
    }

    /** whether the last update merged states beyond the width cap */
    default boolean lastRelaxed() {
        return false;
    }
}
