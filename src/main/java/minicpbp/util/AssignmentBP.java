/*
 * mini-cpbp, replacing classic propagation by belief propagation
 *
 * Loopy belief propagation on the assignment (bipartite matching) factor
 * graph, in the single-scan formulation of
 *
 *   Jason L. Williams, Roslyn A. Lau (2014)
 *   "Approximate evaluation of marginal association probabilities with
 *    belief propagation", arXiv:1209.6299
 *
 * Added 2026-08-16 for the "BP marginals vs. Soules U^3" experiment
 * (see IMPLEMENTATION_LOG.md, 2026-08-16, phase 2).
 *
 * Since 2026-09-03 (MDD_COUNTING_PLAN.md §1.1) this class is a thin adapter
 * over the shared kernel: the exactly-one rows are ExactlyOneRows, the
 * at-most-one columns are AtMostOnePerColumn, over a CandidateTable whose
 * candidates are the positive edges of A in row-major order. The arithmetic,
 * its order, the guards and the constants are those of the previous inline
 * implementation, and exp.PackingCheck ("delegation") asserts that the two
 * agree to the last bit iterate by iterate against the frozen copy
 * exp.AssignmentBPLegacy.
 *
 * Model
 * -----
 * Rows i in [0,m) are the free variables of an alldifferent constraint,
 * columns j in [0,n) its free values, m <= n. A[i][j] >= 0 is the outside
 * belief of value j for variable i (0 when j is not in the domain). The
 * distribution being approximated is
 *     P(phi) proportional to prod_i A[i][phi(i)]   over injections phi.
 *
 * Factor graph: one variable node per row (exactly one column), one factor
 * node per column (at most one row). Messages are parameterised by ratios,
 * normalising the column-to-row message so that its "row i does not take
 * column j" component is 1:
 *
 *     mu_{i->j} = A[i][j] / sum_{j' != j} A[i][j'] nu_{j'->i}
 *     nu_{j->i} = 1 / (1 + sum_{i' != i} mu_{i'->j})
 *
 * Dummy rows / clutter slack
 * --------------------------
 * The "1" in the denominator of nu is exactly the slack for "column j is left
 * unused", i.e. it plays the role of MiniCPBP's dummy rows. Because every row
 * takes exactly one column and there are m rows and n columns, exactly n-m
 * columns are always unused, so the per-unused-column weight (v = 1/nbVal in
 * AllDifferentDC) contributes a factor v^{n-m} that is the same for every
 * (i,j) and therefore cancels in the per-variable normalisation performed by
 * AbstractConstraint.sendMessages(). No dummy rows are materialised here.
 *
 * What is returned
 * ----------------
 * out[i][j] = nu_{j->i}, NOT the marginal beta_ij = A[i][j] nu_{j->i}.
 * setLocalBelief() expects the factor-to-variable message perm(A^{ij}), and
 * beta_ij = A_ij perm(A^{ij}) / perm(A), so nu is the right object: it equals
 * perm(A^{ij}) up to a factor that depends on i only, which the row-wise
 * normalisation in sendMessages() removes. Returning beta would multiply the
 * outside belief in a second time.
 *
 * Stop criterion (2026-08-24)
 * ---------------------------
 * A hard cap of maxIters sweeps, with early stopping on the stability of the
 * SOLVER-FACING beliefs rather than of the raw messages (ExactlyOneRows):
 * R_t = max_i TV(p_i^{(t)}, p_i^{(t-1)}) with p_i(v) = A_iv nu_vi / sum, stop
 * once R_t <= eps after a minimum sweep count (2 cold, 1 warm).
 */

package minicpbp.util;

public final class AssignmentBP {

    /** Largest value a mu message is allowed to take (avoids overflow). */
    private static final double MU_MAX = ExactlyOneRows.MU_MAX;
    /** Default minimum number of sweeps before the stability test may fire. */
    public static final int DEFAULT_MIN_SWEEPS_COLD = 2;
    public static final int DEFAULT_MIN_SWEEPS_WARM = 1;

    private final ExactlyOneRows rows = new ExactlyOneRows();
    private final AtMostOnePerColumn columns = new AtMostOnePerColumn();
    private CandidateTable table;

    // table inputs, reused across calls
    private int[][] dom = new int[0][];
    private int[] size = new int[0];
    private double[][] w = new double[0][];
    private int[] ones = new int[0];
    private int nbEdges;

    // warm start bookkeeping
    private long signature = Long.MIN_VALUE;
    private boolean haveWarmStart = false;

    // instrumentation
    private long nbCalls;
    private long nbConverged;
    private int lastIterations;
    private boolean lastConverged;
    private long prevKernelSweeps, prevKernelCancel, prevColCancel, prevKernelClamps, prevColNonFinite;
    private long nbIterations, nbCancelRecomputes, nbClamps, nbNonFinite;

    public AssignmentBP(int maxM, int maxN) {
        ensureRows(maxM, maxN);
    }

    private void ensureRows(int m, int n) {
        if (dom.length < m) {
            dom = new int[m][];
            w = new double[m][];
            size = new int[m];
        }
        for (int i = 0; i < m; i++) {
            if (dom[i] == null || dom[i].length < n) {
                dom[i] = new int[n];
                w[i] = new double[n];
            }
        }
        if (ones.length < m) {
            ones = new int[m];
            java.util.Arrays.fill(ones, 1);
        }
    }

    /**
     * Runs the message passing and writes nu_{j->i} into out.
     *
     * @param A        m x n nonnegative outside-belief matrix (standard representation)
     * @param m        number of free variables (rows)
     * @param n        number of free values (columns)
     * @param maxIters sweep cap
     * @param eps      stability threshold on R_t (0 or less disables early stopping)
     * @param out      m x n output, entries with A[i][j] == 0 are set to 0
     * @return true if the iteration stopped early on the stability test
     */
    public boolean run(double[][] A, int m, int n, int maxIters, double eps, double[][] out) {
        return run(A, m, n, maxIters, eps, DEFAULT_MIN_SWEEPS_COLD, DEFAULT_MIN_SWEEPS_WARM, out);
    }

    /**
     * As {@link #run(double[][], int, int, int, double, double[][])}, with the
     * minimum sweep counts spelled out.
     *
     * @param minCold minimum sweeps when the messages start from nu = 1
     * @param minWarm minimum sweeps when the previous call's messages are reused
     */
    public boolean run(double[][] A, int m, int n, int maxIters, double eps,
                       int minCold, int minWarm, double[][] out) {
        nbCalls++;
        ensureRows(m, n);
        // ---- the positive edges, row-major, and their signature -------
        long sig = 1469598103934665603L;
        sig = sig * 1099511628211L + m;
        sig = sig * 1099511628211L + n;
        int e = 0;
        for (int i = 0; i < m; i++) {
            double[] Ai = A[i];
            int s = 0;
            for (int j = 0; j < n; j++) {
                if (Ai[j] > 0.0) {
                    dom[i][s] = j;
                    w[i][s] = Ai[j];
                    s++;
                    sig = sig * 1099511628211L + (i * 131L + j);
                }
            }
            size[i] = s;
            e += s;
        }
        nbEdges = e;
        boolean warm = haveWarmStart && sig == signature;
        if (!warm || table == null) {
            // the edge set changed: candidate ids change, so the table is rebuilt and the
            // messages restart from nu = 1 (exactly the previous behaviour)
            table = new CandidateTable(m, dom, size, ones, ones, 1);
            table.fixSingletons = false;
            rows.invalidate();
        }
        signature = sig;
        haveWarmStart = true;
        table.refresh(dom, size, w);
        int minSweeps = Math.max(1, warm ? minWarm : minCold);

        boolean converged = rows.run(table, columns, maxIters, eps, minSweeps, warm, 1.0);
        int it = rows.lastSweeps();
        lastIterations = it;
        lastConverged = converged;
        if (converged) nbConverged++;
        nbIterations += rows.nbSweeps() - prevKernelSweeps;
        prevKernelSweeps = rows.nbSweeps();
        nbCancelRecomputes += (rows.nbCancelRecomputes() - prevKernelCancel) + (columns.nbCancelRecomputes() - prevColCancel);
        prevKernelCancel = rows.nbCancelRecomputes();
        prevColCancel = columns.nbCancelRecomputes();
        nbClamps += rows.nbClamps() - prevKernelClamps;
        prevKernelClamps = rows.nbClamps();
        nbNonFinite += columns.nbNonFinite() - prevColNonFinite;
        prevColNonFinite = columns.nbNonFinite();

        // ---- emit ------------------------------------------------------
        double[] r = rows.r();
        for (int i = 0; i < m; i++) {
            double[] oi = out[i];
            for (int j = 0; j < n; j++) oi[j] = 0.0;
            for (int c = table.jobBegin[i]; c < table.jobEnd[i]; c++) oi[table.start[c]] = r[c];
        }
        return converged;
    }

    /** Discards the cached messages; the next run() starts from nu = 1. */
    public void invalidateWarmStart() {
        haveWarmStart = false;
        rows.invalidate();
    }

    public int lastIterations() {
        return lastIterations;
    }

    public boolean lastConverged() {
        return lastConverged;
    }

    public long nbCalls() {
        return nbCalls;
    }

    public long nbIterations() {
        return nbIterations;
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

    public long nbNonFinite() {
        return nbNonFinite;
    }

    public int nbEdges() {
        return nbEdges;
    }
}
