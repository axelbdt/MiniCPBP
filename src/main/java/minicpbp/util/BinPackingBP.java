package minicpbp.util;

/**
 * Nested belief propagation for the bin_packing constraint
 * (BINPACKING_EXPERIMENT.md §4.2, research_plan.md §6.2).
 *
 * <p>Internal factor graph over edge indicators z_ij (item i, bin j):
 * per-item "exactly one bin" factors (Williams &amp; Lau's exactly-one factor,
 * as in {@link GccBP} with an empty "other" class), and per-bin knapsack
 * factors sum_i size[i] * z_ij in [low_j, up_j], weighted by the
 * load-variable beliefs wLoad[j][c] when given.
 *
 * <p>Knapsack-factor messages are computed by forward/backward passes over
 * the load axis: a forward prefix distribution pre[i][c] of the partial load
 * of items &lt; i, and a backward weighted layer bwdW[i][c] = the acceptance
 * mass of items &ge; i given a partial load of c (the load beliefs folded
 * into the terminal layer). The leave-one-out numerator/denominator for item
 * i combine pre[i] and bwdW[i+1] directly — never by dividing a factor out
 * of the full convolution (research_plan.md §7, deflation ban). This is the
 * O(n·u_j) per-bin-per-sweep analogue of GccBP's prefix/suffix scheme.
 *
 * <p>At all sizes = 1 the knapsack factor degenerates to GccBP's cardinality
 * factor, which is the unit test (exp.BinPackingPhase0).
 *
 * <p>All quantities are ratios r_{j-&gt;i} = nu(z=1)/nu(z=0); the returned
 * factor-to-variable message for bin j is r_{j-&gt;i} up to the caller's row
 * normalization, and excludes the item's own outside belief (message, not
 * marginal — checked by the slope-0 regression of the battery).
 *
 * <p>Cost per sweep: O(sum_j n * (up_j + 1)).
 *
 * <p>Stop criterion (2026-08-24): a hard cap of `sweeps` sweeps, with early
 * stopping on the stability of the SOLVER-FACING beliefs rather than of the
 * raw messages, as in {@link AssignmentBP} and {@link GccBP}. After sweep t,
 * for every variable i,
 *
 * <pre>
 *   p_i^{(t)}(v) = theta_i(v) m_i^{(t)}(v) / sum_u theta_i(u) m_i^{(t)}(u)
 * </pre>
 *
 * with theta the outside belief and m the cavity message — for the bin
 * variable b_i the weights a[i][j] r_{j-&gt;i} over its bins, for the load
 * variable l_j the weights wLoad[j][c] msgLoad[j][c] over c in [low_j, up_j]
 * (msgLoad being the full forward convolution pre[n], already computed by
 * the sweep). With TV(p, q) = 1/2 sum_v |p(v) - q(v)| and
 * R_t = max_i TV(p_i^{(t)}, p_i^{(t-1)}), the sweeps stop once R_t &lt;= eps,
 * subject to a minimum sweep count (2; this class always cold-starts from
 * r = 1, which is p^{(0)}). This asks whether another sweep would materially
 * move the beliefs the solver uses, not whether the messages have reached a
 * numerical fixed point.
 */
public final class BinPackingBP {

    /** Default minimum number of sweeps before the stability test may fire. */
    public static final int DEFAULT_MIN_SWEEPS = 2;

    private double[][] r;     // r[j][i], factor-to-item ratio messages
    private double[][] mu;    // mu[j][i], item-to-factor ratio messages
    private double[][] pre;   // pre[i][c], prefix load distribution (items < i)
    private double[][] bwdW;  // bwdW[i][c], weighted backward layer (items >= i)
    private double[][] pItem; // pItem[i][j], previous sweep's p_{b_i}(j)
    private double[][] pLoad; // pLoad[j][c], previous sweep's p_{l_j}(c)
    private boolean havePLoad; // pLoad holds a previous sweep (it is filled during a sweep)
    private int allocN = -1, allocM = -1, allocU = -1;
    private long iterations = 0;

    // instrumentation
    private long nbCalls;
    private long nbConverged;
    private int lastIterations;
    private boolean lastConverged;

    public long nbIterations() {
        return iterations;
    }

    public long nbCalls() {
        return nbCalls;
    }

    /** Number of calls that stopped early on the stability test. */
    public long nbConverged() {
        return nbConverged;
    }

    public int lastIterations() {
        return lastIterations;
    }

    public boolean lastConverged() {
        return lastConverged;
    }

    private void ensure(int n, int m, int maxU) {
        if (allocN < n || allocM < m || allocU < maxU) {
            r = new double[m][n];
            mu = new double[m][n];
            pre = new double[n + 1][maxU + 1];
            bwdW = new double[n + 1][maxU + 1];
            pItem = new double[n][m];
            pLoad = new double[m][maxU + 1];
            allocN = n;
            allocM = m;
            allocU = maxU;
        }
    }

    /**
     * R over the item variables: max_i TV(p_i^{(t)}, p_i^{(t-1)}) of the
     * normalised solver-facing beliefs a[i][j] r_{j-&gt;i}. Overwrites pItem
     * with p^{(t)}; called once before the first sweep to seed it, its result
     * then being meaningless and discarded.
     */
    private double itemBeliefChange(int n, int m, double[][] a) {
        double maxTv = 0.0;
        for (int i = 0; i < n; i++) {
            double[] ai = a[i];
            double[] pi = pItem[i];
            double tot = 0.0;
            for (int j = 0; j < m; j++) tot += ai[j] * r[j][i];
            double tv = 0.0;
            for (int j = 0; j < m; j++) {
                double pj = (tot > 0.0) ? ai[j] * r[j][i] / tot : 0.0;
                tv += Math.abs(pj - pi[j]);
                pi[j] = pj;
            }
            tv *= 0.5;
            if (tv > maxTv) maxTv = tv;
        }
        return maxTv;
    }

    /**
     * TV of one load variable's belief, wLoad[j][c] dist[c] normalised over
     * c in [low, up] — the range BinPackingCounting reads. Overwrites
     * pLoad[j]; the caller discards the result of the first sweep, which has
     * no predecessor to compare against.
     */
    private double loadBeliefChange(int j, int low, int up, double[] w, double[] dist) {
        double[] pj = pLoad[j];
        double z = 0.0;
        for (int c = low; c <= up; c++) z += ((w != null) ? w[c] : 1.0) * dist[c];
        double tv = 0.0;
        for (int c = 0; c <= up; c++) {
            double pc = (z > 0.0 && c >= low)
                    ? ((w != null) ? w[c] : 1.0) * dist[c] / z : 0.0;
            tv += Math.abs(pc - pj[c]);
            pj[c] = pc;
        }
        return 0.5 * tv;
    }

    /**
     * Runs capped-sweep nested BP.
     *
     * @param n     number of items
     * @param m     number of bins
     * @param size  size[i] fixed integer size of item i (&ge; 0)
     * @param a     a[i][j] outside-belief weight of item i on bin j (0 if j not in D(b_i))
     * @param low   per-bin lower load bounds
     * @param up    per-bin upper load bounds
     * @param wLoad load beliefs wLoad[j][c] (length up[j]+1) or null for the 0/1 indicator of [low, up]
     * @param sweeps iteration cap
     * @param msg   out: msg[i][j], the ratio message of item i for bin j
     * @param msgLoad out (may be null): msgLoad[j][c] = cavity load distribution of bin j
     *                (excludes l_j's own belief by construction)
     * @return false on numerical failure (caller should fall back), true otherwise
     */
    public boolean run(int n, int m, int[] size, double[][] a, int[] low, int[] up,
                       double[][] wLoad, int sweeps, double[][] msg, double[][] msgLoad) {
        return run(n, m, size, a, low, up, wLoad, sweeps, msg, msgLoad, 0.0, DEFAULT_MIN_SWEEPS);
    }

    /**
     * As {@link #run(int, int, int[], double[][], int[], int[], double[][], int, double[][], double[][])},
     * with the belief-stability stop criterion.
     *
     * @param eps       early-exit threshold on R_t, the max over variables of
     *                  the total-variation distance between the normalised
     *                  solver-facing beliefs of two consecutive sweeps;
     *                  0 or less disables early exit, restoring fixed sweeps
     * @param minSweeps minimum sweeps before the test may fire (this class
     *                  always cold-starts, so the cold-start minimum applies)
     */
    public boolean run(int n, int m, int[] size, double[][] a, int[] low, int[] up,
                       double[][] wLoad, int sweeps, double[][] msg, double[][] msgLoad,
                       double eps, int minSweeps) {
        nbCalls++;
        int maxU = 0;
        for (int j = 0; j < m; j++) maxU = Math.max(maxU, up[j]);
        ensure(n, m, maxU);

        for (int j = 0; j < m; j++) java.util.Arrays.fill(r[j], 0, n, 1.0);
        int minIt = Math.max(1, minSweeps);
        itemBeliefChange(n, m, a); // p^{(0)}, read off the starting messages r = 1
        havePLoad = false;         // the load distributions only exist once a sweep has run

        boolean converged = false;
        int it = 0;
        for (; it < sweeps; it++) {
            iterations++;
            double loadTv = 0.0;
            // item -> factor messages (ratio of "i goes to bin j" to "i does not")
            for (int i = 0; i < n; i++) {
                double tot = 0.0;
                for (int j = 0; j < m; j++) tot += a[i][j] * r[j][i];
                for (int j = 0; j < m; j++) {
                    double excl = tot - a[i][j] * r[j][i];
                    if (excl < 1e-12 * tot) { // catastrophic cancellation guard: recompute
                        excl = 0.0;
                        for (int j2 = 0; j2 < m; j2++) if (j2 != j) excl += a[i][j2] * r[j2][i];
                    }
                    mu[j][i] = (excl > 0) ? (a[i][j] / excl) : (a[i][j] > 0 ? 1e12 : 0.0);
                    if (mu[j][i] > 1e12) mu[j][i] = 1e12;
                }
            }
            // factor -> item messages, per bin: forward prefix + weighted backward
            for (int j = 0; j < m; j++) {
                int u = up[j];
                double[] w = (wLoad == null) ? null : wLoad[j];
                // forward: pre[i][c] = load distribution of z_{i'<i, j}; overflow
                // (> u) is dropped — it can only reach zero-acceptance states
                double[] p0 = pre[0];
                java.util.Arrays.fill(p0, 0, u + 1, 0.0);
                p0[0] = 1.0;
                for (int i = 0; i < n; i++) {
                    double[] cur = pre[i];
                    double[] nxt = pre[i + 1];
                    double mm = mu[j][i];
                    double norm = 1.0 + mm;
                    double q0 = 1.0 / norm, q1 = mm / norm; // normalized Bernoulli
                    int sz = size[i];
                    if (sz == 0) {
                        // size-0 item: load unchanged either way
                        for (int c = 0; c <= u; c++) nxt[c] = cur[c];
                    } else {
                        for (int c = 0; c <= u; c++) {
                            double v = cur[c] * q0;
                            if (c >= sz) v += cur[c - sz] * q1;
                            nxt[c] = v;
                        }
                    }
                }
                // pre[n] is the cavity load distribution of bin j for this
                // sweep -- exactly what the post-loop pass emits into
                // msgLoad from the same mu -- so the load-belief change is
                // read off here at no extra cost
                double tvj = loadBeliefChange(j, low[j], u, w, pre[n]);
                if (tvj > loadTv) loadTv = tvj;
                // backward weighted: bwdW[n][c] = w(c) (or the [low, up] indicator);
                // bwdW[i][c] = q0_i * bwdW[i+1][c] + q1_i * bwdW[i+1][c + sz_i]
                double[] bn = bwdW[n];
                for (int c = 0; c <= u; c++) bn[c] = (w != null) ? w[c] : ((c >= low[j]) ? 1.0 : 0.0);
                for (int i = n - 1; i >= 0; i--) {
                    double[] cur = bwdW[i + 1];
                    double[] nxt = bwdW[i];
                    double mm = mu[j][i];
                    double norm = 1.0 + mm;
                    double q0 = 1.0 / norm, q1 = mm / norm;
                    int sz = size[i];
                    if (sz == 0) {
                        for (int c = 0; c <= u; c++) nxt[c] = cur[c];
                    } else {
                        for (int c = 0; c <= u; c++) {
                            double v = q0 * cur[c];
                            if (c + sz <= u) v += q1 * cur[c + sz];
                            nxt[c] = v;
                        }
                    }
                }
                // leave-one-out: num/den for item i from pre[i] (items < i) and
                // bwdW[i+1] (items > i, acceptance folded); item i itself excluded
                for (int i = 0; i < n; i++) {
                    double[] pi = pre[i];
                    double[] bi = bwdW[i + 1];
                    int sz = size[i];
                    double num = 0.0, den = 0.0;
                    for (int c = 0; c <= u; c++) {
                        double pc = pi[c];
                        if (pc == 0.0) continue;
                        den += pc * bi[c];                      // z_ij = 0
                        if (c + sz <= u) num += pc * bi[c + sz]; // z_ij = 1
                    }
                    double ratio;
                    if (den > 0) ratio = num / den;
                    else if (num > 0) ratio = 1e12;
                    else ratio = 0.0;
                    if (Double.isNaN(ratio) || Double.isInfinite(ratio)) return false;
                    if (ratio > 1e12) ratio = 1e12;
                    r[j][i] = ratio;
                }
            }
            // stability of the solver-facing beliefs, not of the messages
            double R = itemBeliefChange(n, m, a);
            if (havePLoad && loadTv > R) R = loadTv;
            havePLoad = true;
            if (eps > 0.0 && it + 1 >= minIt && R <= eps) {
                converged = true;
                it++;
                break;
            }
        }
        lastIterations = it;
        lastConverged = converged;
        if (converged) nbConverged++;
        // emit load-variable messages from the final full forward convolutions
        if (msgLoad != null) {
            for (int j = 0; j < m; j++) {
                int u = up[j];
                double[] p0 = pre[0];
                java.util.Arrays.fill(p0, 0, u + 1, 0.0);
                p0[0] = 1.0;
                for (int i = 0; i < n; i++) {
                    double[] cur = pre[i];
                    double[] nxt = pre[i + 1];
                    double mm = mu[j][i];
                    double norm = 1.0 + mm;
                    double q0 = 1.0 / norm, q1 = mm / norm;
                    int sz = size[i];
                    if (sz == 0) {
                        for (int c = 0; c <= u; c++) nxt[c] = cur[c];
                    } else {
                        for (int c = 0; c <= u; c++) {
                            double v = cur[c] * q0;
                            if (c >= sz) v += cur[c - sz] * q1;
                            nxt[c] = v;
                        }
                    }
                }
                for (int c = 0; c <= u && c < msgLoad[j].length; c++) msgLoad[j][c] = pre[n][c];
            }
        }
        // emit messages
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < m; j++) {
                double v = r[j][i];
                if (Double.isNaN(v) || Double.isInfinite(v) || v < 0) return false;
                msg[i][j] = v;
            }
        }
        return true;
    }
}
