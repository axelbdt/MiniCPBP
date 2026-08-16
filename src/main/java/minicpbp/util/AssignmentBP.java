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
 */

package minicpbp.util;

public final class AssignmentBP {

    /** Largest value a mu message is allowed to take (avoids overflow). */
    private static final double MU_MAX = 1e12;
    /** Relative threshold below which a leave-one-out difference is recomputed. */
    private static final double CANCEL_REL = 1e-10;

    // CSR edge list, row major
    private int[] rowStart;
    private int[] colIdx;
    private double[] a;      // A[i][j] for the edge
    private double[] mu;
    private double[] nu;
    private double[] rowAcc; // R_i  = sum_j A[i][j] nu_{j->i}
    private double[] colAcc; // C_j  = sum_i mu_{i->j}
    private int nbEdges;
    private int m, n;

    // warm start bookkeeping
    private long signature = Long.MIN_VALUE;
    private boolean haveWarmStart = false;

    // instrumentation
    private long nbCalls;
    private long nbIterations;
    private long nbConverged;
    private long nbCancelRecomputes;
    private long nbClamps;
    private long nbNonFinite;
    private int lastIterations;
    private boolean lastConverged;

    public AssignmentBP(int maxM, int maxN) {
        rowStart = new int[maxM + 1];
        int cap = Math.max(16, maxM * maxN);
        colIdx = new int[cap];
        a = new double[cap];
        mu = new double[cap];
        nu = new double[cap];
        rowAcc = new double[maxM];
        colAcc = new double[maxN];
    }

    private void ensureEdgeCapacity(int cap) {
        if (colIdx.length >= cap) return;
        colIdx = new int[cap];
        a = new double[cap];
        mu = new double[cap];
        nu = new double[cap];
        haveWarmStart = false;
    }

    /**
     * Runs the message passing and writes nu_{j->i} into out.
     *
     * @param A        m x n nonnegative outside-belief matrix (standard representation)
     * @param m        number of free variables (rows)
     * @param n        number of free values (columns)
     * @param maxIters iteration cap
     * @param tol      convergence tolerance on max |log(nu_new/nu_old)|
     * @param out      m x n output, entries with A[i][j] == 0 are set to 0
     * @return true if the iteration converged within the cap
     */
    public boolean run(double[][] A, int m, int n, int maxIters, double tol, double[][] out) {
        this.m = m;
        this.n = n;
        nbCalls++;

        // ---- build the sparse edge list and its signature -------------
        int cap = 0;
        for (int i = 0; i < m; i++)
            for (int j = 0; j < n; j++) if (A[i][j] > 0.0) cap++;
        ensureEdgeCapacity(Math.max(cap, 1));

        long sig = 1469598103934665603L;
        sig = sig * 1099511628211L + m;
        sig = sig * 1099511628211L + n;
        int e = 0;
        for (int i = 0; i < m; i++) {
            rowStart[i] = e;
            double[] Ai = A[i];
            for (int j = 0; j < n; j++) {
                if (Ai[j] > 0.0) {
                    colIdx[e] = j;
                    a[e] = Ai[j];
                    e++;
                    sig = sig * 1099511628211L + (i * 131L + j);
                }
            }
        }
        rowStart[m] = e;
        nbEdges = e;


        boolean warm = haveWarmStart && sig == signature;
        signature = sig;
        haveWarmStart = true;
        if (!warm) {
            for (int k = 0; k < nbEdges; k++) nu[k] = 1.0;
        }

        // ---- iterate ---------------------------------------------------
        boolean converged = false;
        int iter = 0;
        for (; iter < maxIters; iter++) {
            // row pass: mu_{i->j} = A_ij / (R_i - A_ij nu_ji)
            for (int i = 0; i < m; i++) {
                double acc = 0.0;
                for (int k = rowStart[i]; k < rowStart[i + 1]; k++) acc += a[k] * nu[k];
                rowAcc[i] = acc;
            }
            for (int i = 0; i < m; i++) {
                double R = rowAcc[i];
                int s = rowStart[i], t = rowStart[i + 1];
                for (int k = s; k < t; k++) {
                    double own = a[k] * nu[k];
                    double den = R - own;
                    if (!(den > CANCEL_REL * R)) {
                        // catastrophic cancellation or genuinely tiny: recompute
                        nbCancelRecomputes++;
                        den = 0.0;
                        for (int q = s; q < t; q++) if (q != k) den += a[q] * nu[q];
                    }
                    double v;
                    if (den <= 0.0) {
                        v = MU_MAX;
                        nbClamps++;
                    } else {
                        v = a[k] / den;
                        if (!(v <= MU_MAX)) {
                            v = MU_MAX;
                            nbClamps++;
                        }
                    }
                    mu[k] = v;
                }
            }
            // column pass: nu_{j->i} = 1 / (1 + C_j - mu_ij)
            java.util.Arrays.fill(colAcc, 0, n, 0.0);
            for (int k = 0; k < nbEdges; k++) colAcc[colIdx[k]] += mu[k];
            double maxDelta = 0.0;
            for (int i = 0; i < m; i++) {
                for (int k = rowStart[i]; k < rowStart[i + 1]; k++) {
                    int j = colIdx[k];
                    double C = colAcc[j];
                    // Leave-one-out by subtraction, rescanning the column only
                    // when the difference is not trustworthy. The rescan is
                    // frequent (mostly degree-1 columns, where the difference
                    // is exactly zero) but cheap; indexing the edges by column
                    // to avoid it was measured 3-7% SLOWER on both real and
                    // synthetic matrices, so the simple scan is kept.
                    double rest = C - mu[k];
                    if (!(rest >= CANCEL_REL * C) && C > 0.0) {
                        nbCancelRecomputes++;
                        rest = 0.0;
                        for (int q = 0; q < nbEdges; q++)
                            if (colIdx[q] == j && q != k) rest += mu[q];
                    }
                    if (rest < 0.0) rest = 0.0;
                    double newNu = 1.0 / (1.0 + rest);
                    if (!(newNu > 0.0) || Double.isNaN(newNu) || Double.isInfinite(newNu)) {
                        nbNonFinite++;
                        newNu = Double.MIN_NORMAL;
                    }
                    double old = nu[k];
                    double delta = Math.abs(Math.log(newNu) - Math.log(old));
                    if (delta > maxDelta) maxDelta = delta;
                    nu[k] = newNu;
                }
            }
            nbIterations++;
            if (maxDelta <= tol) {
                converged = true;
                iter++;
                break;
            }
        }
        lastIterations = iter;
        lastConverged = converged;
        if (converged) nbConverged++;

        // ---- emit ------------------------------------------------------
        for (int i = 0; i < m; i++) {
            double[] oi = out[i];
            for (int j = 0; j < n; j++) oi[j] = 0.0;
            for (int k = rowStart[i]; k < rowStart[i + 1]; k++) oi[colIdx[k]] = nu[k];
        }
        return converged;
    }

    /** Discards the cached messages; the next run() starts from nu = 1. */
    public void invalidateWarmStart() {
        haveWarmStart = false;
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
