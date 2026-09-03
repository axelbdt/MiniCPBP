/*
 * mini-cpbp, replacing classic propagation by belief propagation
 *
 * Candidate-interval counting (MDD_COUNTING_PLAN.md §1.1): the AllDifferent
 * column side, i.e. Williams & Lau's at-most-one measurement factor.
 */

package minicpbp.util;

/**
 * At most one candidate per start value: the column factor of the assignment
 * graph, which is what the resource factor R collapses to when every duration
 * is 1 and the capacity is 1 (brief §2.3, the unit-duration sanity check).
 *
 * <pre>
 *   r_a = 1 / (1 + sum_{b in column(a), b != a} lambda_b)
 * </pre>
 *
 * The "1" is the slack of an unused column (a value taken by no job), exactly
 * as in {@link AssignmentBP}. Leave-one-out by subtraction from the column
 * total, guarded as there: when the difference is not trustworthy the column
 * is rescanned; a non-finite or non-positive result is floored at
 * {@code Double.MIN_NORMAL}. Column totals accumulate in candidate-id order
 * (row-major), {@code AssignmentBP}'s edge order.
 *
 * <p>Fixed jobs are outside the factor (their value is in the table's fixed
 * profile, and with Cap = 1 every other candidate at that value is not live),
 * so with no fixed job this reproduces {@code AssignmentBP} on the same
 * matrix to the last bit (test 6.1).
 */
public final class AtMostOnePerColumn implements PackingFactor {

    private double[] colAcc = new double[0];
    private int[] col = new int[0];
    private int colBase;
    private long nbCancelRecomputes, nbNonFinite;

    @Override
    public String name() {
        return "atmostone";
    }

    @Override
    public boolean update(CandidateTable t, double[] lambda, double[] r) {
        int M = t.M;
        int span = t.tmax - t.tmin + 1;
        if (colAcc.length < span) colAcc = new double[span];
        if (col.length < M) col = new int[M];
        colBase = t.tmin;
        java.util.Arrays.fill(colAcc, 0, span, 0.0);
        boolean[] live = t.live;
        for (int a = 0; a < M; a++) {
            if (!live[a]) continue;
            int c = t.start[a] - colBase;
            col[a] = c;
            colAcc[c] += lambda[a];
        }
        for (int a = 0; a < M; a++) {
            if (!live[a]) continue;
            int c = col[a];
            double C = colAcc[c];
            double rest = C - lambda[a];
            if (!(rest >= ExactlyOneRows.CANCEL_REL * C) && C > 0.0) {
                nbCancelRecomputes++;
                rest = 0.0;
                for (int q = 0; q < M; q++)
                    if (live[q] && q != a && col[q] == c) rest += lambda[q];
            }
            if (rest < 0.0) rest = 0.0;
            double nv = 1.0 / (1.0 + rest);
            if (!(nv > 0.0) || Double.isNaN(nv) || Double.isInfinite(nv)) {
                nbNonFinite++;
                nv = Double.MIN_NORMAL;
            }
            r[a] = nv;
        }
        return true;
    }

    public long nbCancelRecomputes() {
        return nbCancelRecomputes;
    }

    public long nbNonFinite() {
        return nbNonFinite;
    }
}
