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
 */
public final class BinPackingBP {

    private double[][] r;     // r[j][i], factor-to-item ratio messages
    private double[][] mu;    // mu[j][i], item-to-factor ratio messages
    private double[][] pre;   // pre[i][c], prefix load distribution (items < i)
    private double[][] bwdW;  // bwdW[i][c], weighted backward layer (items >= i)
    private int allocN = -1, allocM = -1, allocU = -1;
    private long iterations = 0;

    public long nbIterations() {
        return iterations;
    }

    private void ensure(int n, int m, int maxU) {
        if (allocN < n || allocM < m || allocU < maxU) {
            r = new double[m][n];
            mu = new double[m][n];
            pre = new double[n + 1][maxU + 1];
            bwdW = new double[n + 1][maxU + 1];
            allocN = n;
            allocM = m;
            allocU = maxU;
        }
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
        int maxU = 0;
        for (int j = 0; j < m; j++) maxU = Math.max(maxU, up[j]);
        ensure(n, m, maxU);

        for (int j = 0; j < m; j++) java.util.Arrays.fill(r[j], 0, n, 1.0);

        for (int it = 0; it < sweeps; it++) {
            iterations++;
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
        }
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
