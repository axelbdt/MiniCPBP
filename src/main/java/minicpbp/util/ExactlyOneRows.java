/*
 * mini-cpbp, replacing classic propagation by belief propagation
 *
 * Candidate-interval counting for Cumulative / Disjunctive
 * (MDD_COUNTING_PLAN.md §1.1, brief §2.3 and §4).
 */

package minicpbp.util;

/**
 * The Williams &amp; Lau row kernel over a {@link CandidateTable}: the
 * exactly-one side of the candidate-interval factor graph, shared by
 * AllDifferent ({@link AtMostOnePerColumn}), Disjunctive
 * ({@link IntervalPackingDP}) and Cumulative ({@link ResourceMDD}).
 *
 * <p>Per live candidate a of job i, with weight w_a (outside belief) and the
 * resource side's odds r_a:
 *
 * <pre>
 *   lambda_a = w_a / sum_{u in row(i), u != a} w_u r_u          (row update)
 *   r        = factor.update(lambda)                             (column side)
 *   b_i(a)  ∝ w_a r_a                                            (belief)
 *   m_{C -> x_i}(s_a) ∝ r_a                                      (emitted message)
 * </pre>
 *
 * The row denominator is the row total minus the own term, guarded exactly
 * as {@link AssignmentBP} guards it: when the difference is not trustworthy
 * (below {@code CANCEL_REL} of the total) the sum over the other candidates
 * is recomputed; a zero denominator gives {@code MU_MAX}. Summation runs in
 * candidate-id order, i.e. in increasing start value within a row, which is
 * {@code AssignmentBP}'s column order, so the two agree to the last bit when
 * the column side is the at-most-one factor (test 6.1).
 *
 * <p>Stop rule: hard cap plus early exit when the max over rows of the
 * total-variation change of the normalised solver-facing belief w_a r_a is at
 * most eps, after a minimum number of sweeps — {@code AssignmentBP}'s
 * criterion. Warm start: r is kept per candidate id across calls; the caller
 * decides whether the previous state is usable ({@code warm}); a cold call
 * resets r to 1. Optional damping on r (geometric interpolation with the
 * previous r, {@code damping} in (0, 1]; 1 = none).
 */
public final class ExactlyOneRows {

    /** largest odds a message may take (shared with AssignmentBP) */
    public static final double MU_MAX = 1e12;
    /** relative threshold below which a leave-one-out difference is recomputed */
    public static final double CANCEL_REL = 1e-10;

    private double[] lambda, r, rPrev, pPrev;
    private int capacity;

    // instrumentation
    private long nbCalls, nbSweeps, nbConverged, nbCancelRecomputes, nbClamps;
    private int lastSweeps;
    private boolean lastConverged;
    private boolean haveState;

    private void ensure(int M) {
        if (lambda == null || capacity < M) {
            lambda = new double[M];
            r = new double[M];
            rPrev = new double[M];
            pPrev = new double[M];
            capacity = M;
            haveState = false;
        }
    }

    /** the current outgoing odds by candidate id (valid after run) */
    public double[] r() {
        return r;
    }

    /** the current incoming odds by candidate id (valid after run) */
    public double[] lambda() {
        return lambda;
    }

    /** forget the previous messages: the next run starts from r = 1 */
    public void invalidate() {
        haveState = false;
    }

    /**
     * Runs the inner message passing.
     *
     * @param t         the refreshed table (live set, weights)
     * @param factor    the resource side
     * @param maxSweeps hard cap (≥ 1)
     * @param eps       stability threshold (≤ 0 disables the early exit)
     * @param minSweeps minimum sweeps before the early exit may fire
     * @param warm      reuse the previous r (if any) instead of r = 1
     * @param damping   in (0, 1]; 1 means none
     * @return true if the run stopped on the stability test; false if it ran
     * to the cap. Numerical failure is signalled by {@link #lastFailed()}.
     */
    public boolean run(CandidateTable t, PackingFactor factor, int maxSweeps, double eps, int minSweeps,
                       boolean warm, double damping) {
        ensure(t.M);
        nbCalls++;
        lastFailed = false;
        lastSweeps = 0;
        lastConverged = false;
        boolean[] live = t.live;
        double[] w = t.weight;
        int M = t.M;
        if (!(warm && haveState)) {
            for (int a = 0; a < M; a++) r[a] = 1.0;
        }
        haveState = true;
        for (int a = 0; a < M; a++) if (!live[a]) { r[a] = 1.0; lambda[a] = 0.0; }
        beliefChange(t, pPrev); // seed p^(0)
        int minIt = Math.max(1, minSweeps);
        boolean converged = false;
        int it = 0;
        for (; it < maxSweeps; it++) {
            // ---- row pass ----
            for (int i = 0; i < t.n; i++) {
                if (t.fixed[i] || t.inert[i]) continue;
                int b = t.jobBegin[i], e = t.jobEnd[i];
                double R = 0.0;
                for (int a = b; a < e; a++) if (live[a]) R += w[a] * r[a];
                for (int a = b; a < e; a++) {
                    if (!live[a]) continue;
                    double own = w[a] * r[a];
                    double den = R - own;
                    if (!(den > CANCEL_REL * R)) {
                        nbCancelRecomputes++;
                        den = 0.0;
                        for (int q = b; q < e; q++) if (q != a && live[q]) den += w[q] * r[q];
                    }
                    double v;
                    if (den <= 0.0) {
                        v = MU_MAX;
                        nbClamps++;
                    } else {
                        v = w[a] / den;
                        if (!(v <= MU_MAX)) {
                            v = MU_MAX;
                            nbClamps++;
                        }
                    }
                    lambda[a] = v;
                }
            }
            // ---- column side ----
            if (damping < 1.0) System.arraycopy(r, 0, rPrev, 0, M);
            if (!factor.update(t, lambda, r)) {
                lastFailed = true;
                break;
            }
            if (damping < 1.0) {
                for (int a = 0; a < M; a++) {
                    if (!live[a]) continue;
                    double nv = r[a], ov = rPrev[a];
                    if (nv > 0.0 && ov > 0.0) r[a] = Math.exp(damping * Math.log(nv) + (1.0 - damping) * Math.log(ov));
                }
            }
            for (int a = 0; a < M; a++) {
                if (!live[a]) continue;
                double v = r[a];
                if (Double.isNaN(v) || Double.isInfinite(v) || v < 0.0) {
                    lastFailed = true;
                    break;
                }
            }
            if (lastFailed) break;
            nbSweeps++;
            double change = beliefChange(t, pPrev);
            if (eps > 0.0 && it + 1 >= minIt && change <= eps) {
                converged = true;
                it++;
                break;
            }
        }
        lastSweeps = it;
        lastConverged = converged;
        if (converged) nbConverged++;
        return converged;
    }

    private boolean lastFailed;

    /** whether the last run met NaN/Inf (its r must not be used) */
    public boolean lastFailed() {
        return lastFailed;
    }

    /**
     * max over rows of TV(p_i^(t), p_i^(t-1)) with p_i(a) = w_a r_a / sum, and
     * overwrites prev with p^(t)
     */
    private double beliefChange(CandidateTable t, double[] prev) {
        double maxTv = 0.0;
        boolean[] live = t.live;
        double[] w = t.weight;
        for (int i = 0; i < t.n; i++) {
            if (t.fixed[i] || t.inert[i]) continue;
            int b = t.jobBegin[i], e = t.jobEnd[i];
            double z = 0.0;
            for (int a = b; a < e; a++) if (live[a]) z += w[a] * r[a];
            double tv = 0.0;
            for (int a = b; a < e; a++) {
                if (!live[a]) continue;
                double pk = (z > 0.0) ? w[a] * r[a] / z : 0.0;
                tv += Math.abs(pk - prev[a]);
                prev[a] = pk;
            }
            tv *= 0.5;
            if (tv > maxTv) maxTv = tv;
        }
        return maxTv;
    }

    public int lastSweeps() {
        return lastSweeps;
    }

    public boolean lastConverged() {
        return lastConverged;
    }

    public long nbCalls() {
        return nbCalls;
    }

    public long nbSweeps() {
        return nbSweeps;
    }

    public long nbConverged() {
        return nbConverged;
    }

    public long nbCancelRecomputes() {
        return nbCancelRecomputes;
    }

    public long nbClamps() {
        return nbClamps;
    }
}
