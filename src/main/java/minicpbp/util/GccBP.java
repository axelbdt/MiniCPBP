package minicpbp.util;

/**
 * Nested belief propagation for the cardinality (gcc) constraint
 * (GCC_EXPERIMENT.md §4.2, research_plan.md §5.4).
 *
 * <p>Internal factor graph over edge indicators z_ij (variable i, counted
 * class j): per-variable "exactly one class" factors (the "other" class,
 * weight b[i], is the slack, exactly as the dummy rows are in Williams &amp;
 * Lau's alldifferent formulation), and per-class cardinality factors
 * sum_i z_ij in [low_j, up_j], weighted by the occurrence-variable beliefs
 * wOcc[j][c] when given.
 *
 * <p>Cardinality-factor messages are computed by forward/backward binary
 * convolutions over counts 0..up_j with leave-one-out taken from
 * prefix/suffix count distributions — never by dividing a factor out
 * (research_plan.md §7, deflation ban). At low = up = 1 for every class and
 * b = 0 this degenerates to Williams &amp; Lau's single-scan BP, which is the
 * unit test.
 *
 * <p>All quantities are ratios r_{j->i} = nu(z=1)/nu(z=0), so the returned
 * factor-to-variable message for class j is r_{j->i} and for the "other"
 * class 1 (up to the row normalization the caller performs). The message
 * excludes the variable's own outside belief (message, not marginal).
 *
 * <p>Cost per sweep: O(sum_j n * (up_j + 2)).
 */
public final class GccBP {

    private double[][] r;        // r[j][i], factor-to-variable ratio messages
    private double[][] mu;       // mu[j][i], variable-to-factor ratio messages
    private double[][] pre;      // prefix count distributions, (n+1) x (u+2) flattened per class use
    private double[][] suf;
    private int allocN = -1, allocK = -1, allocU = -1;
    private long iterations = 0;

    public long nbIterations() {
        return iterations;
    }

    private void ensure(int n, int k, int maxU) {
        if (allocN < n || allocK < k || allocU < maxU) {
            r = new double[k][n];
            mu = new double[k][n];
            pre = new double[n + 1][maxU + 2];
            suf = new double[n + 1][maxU + 2];
            allocN = n;
            allocK = k;
            allocU = maxU;
        }
    }

    /**
     * Runs capped-sweep nested BP.
     *
     * @param n    number of variables
     * @param k    number of counted classes
     * @param a    a[i][j] outside-belief weight of variable i on class j (0 if absent)
     * @param b    b[i] weight of the "other" class (0 if variable i must take a counted value)
     * @param low  per-class lower occurrence bounds
     * @param up   per-class upper occurrence bounds
     * @param wOcc occurrence beliefs wOcc[j][c] (length up[j]+1) or null for 0/1 indicator of [low, up]
     * @param sweeps iteration cap
     * @param msg  out: msg[i][j], j &lt; k the class message, j = k the "other" message
     * @return false if a numerical failure occurred (caller should fall back), true otherwise
     */
    public boolean run(int n, int k, double[][] a, double[] b, int[] low, int[] up,
                       double[][] wOcc, int sweeps, double[][] msg) {
        return run(n, k, a, b, low, up, wOcc, sweeps, msg, null);
    }

    /**
     * As {@link #run(int, int, double[][], double[], int[], int[], double[][], int, double[][])},
     * additionally emitting the message to each occurrence variable when
     * msgOcc is non-null: msgOcc[j][c] = the cavity count distribution of
     * class j (the full convolution of the variable-to-factor messages,
     * which excludes o_j's own belief by construction).
     */
    public boolean run(int n, int k, double[][] a, double[] b, int[] low, int[] up,
                       double[][] wOcc, int sweeps, double[][] msg, double[][] msgOcc) {
        int maxU = 0;
        for (int j = 0; j < k; j++) maxU = Math.max(maxU, up[j]);
        ensure(n, k, maxU);

        for (int j = 0; j < k; j++) java.util.Arrays.fill(r[j], 0, n, 1.0);

        for (int it = 0; it < sweeps; it++) {
            iterations++;
            // variable -> factor messages (ratio of "i takes class j" to "i does not")
            for (int i = 0; i < n; i++) {
                double tot = b[i];
                for (int j = 0; j < k; j++) tot += a[i][j] * r[j][i];
                for (int j = 0; j < k; j++) {
                    double excl = tot - a[i][j] * r[j][i];
                    if (excl < 1e-12 * tot) { // catastrophic cancellation guard: recompute
                        excl = b[i];
                        for (int j2 = 0; j2 < k; j2++) if (j2 != j) excl += a[i][j2] * r[j2][i];
                    }
                    mu[j][i] = (excl > 0) ? (a[i][j] / excl) : (a[i][j] > 0 ? 1e12 : 0.0);
                    if (mu[j][i] > 1e12) mu[j][i] = 1e12;
                }
            }
            // factor -> variable messages by prefix/suffix convolution per class
            for (int j = 0; j < k; j++) {
                int u = up[j];
                int bins = u + 2; // counts 0..u and an overflow bin at u+1 (kept for normalization only)
                // prefix: pre[i] = count distribution of z_{0..i-1, j}
                double[] p0 = pre[0];
                java.util.Arrays.fill(p0, 0, bins, 0.0);
                p0[0] = 1.0;
                for (int i = 0; i < n; i++) {
                    double[] cur = pre[i];
                    double[] nxt = pre[i + 1];
                    double m = mu[j][i];
                    double norm = 1.0 + m;
                    double q0 = 1.0 / norm, q1 = m / norm; // normalized Bernoulli, keeps numbers in range
                    nxt[0] = cur[0] * q0;
                    for (int c = 1; c <= u; c++) nxt[c] = cur[c] * q0 + cur[c - 1] * q1;
                    nxt[u + 1] = cur[u + 1] + cur[u] * q1; // overflow absorbs
                    // note: overflow bin uses q0+q1=1 mass conservation: cur[u+1]*(q0+q1)=cur[u+1]
                }
                // suffix
                double[] s0 = suf[n];
                java.util.Arrays.fill(s0, 0, bins, 0.0);
                s0[0] = 1.0;
                for (int i = n - 1; i >= 0; i--) {
                    double[] cur = suf[i + 1];
                    double[] nxt = suf[i];
                    double m = mu[j][i];
                    double norm = 1.0 + m;
                    double q0 = 1.0 / norm, q1 = m / norm;
                    nxt[0] = cur[0] * q0;
                    for (int c = 1; c <= u; c++) nxt[c] = cur[c] * q0 + cur[c - 1] * q1;
                    nxt[u + 1] = cur[u + 1] + cur[u] * q1;
                }
                // leave-one-out: distribution of sum_{i' != i} z_{i'j} = pre[i] (x) suf[i+1]
                double[] w = (wOcc == null) ? null : wOcc[j];
                for (int i = 0; i < n; i++) {
                    double[] pi = pre[i];
                    double[] si = suf[i + 1];
                    double num = 0.0, den = 0.0;
                    // P_{-i}(c) = sum_{c1+c2=c} pi[c1]*si[c2], c <= u needed
                    for (int c = 0; c <= u; c++) {
                        double pc = 0.0;
                        for (int c1 = 0; c1 <= c; c1++) pc += pi[c1] * si[c - c1];
                        double wc = (w != null) ? w[c] : ((c >= low[j]) ? 1.0 : 0.0);
                        if (wc != 0.0) den += wc * pc;      // z_ij = 0: total count c
                        if (c >= 1) {
                            double wc1 = wc;                 // z_ij = 1: total count c, others c-1
                            if (wc1 != 0.0) {
                                double pc1 = 0.0;
                                for (int c1 = 0; c1 <= c - 1; c1++) pc1 += pi[c1] * si[c - 1 - c1];
                                num += wc1 * pc1;
                            }
                        }
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
        // emit occurrence-variable messages from the final full convolutions
        if (msgOcc != null) {
            for (int j = 0; j < k; j++) {
                int u = up[j];
                double[] p0 = pre[0];
                java.util.Arrays.fill(p0, 0, u + 2, 0.0);
                p0[0] = 1.0;
                for (int i = 0; i < n; i++) {
                    double[] cur = pre[i];
                    double[] nxt = pre[i + 1];
                    double m2 = mu[j][i];
                    double norm = 1.0 + m2;
                    double q0 = 1.0 / norm, q1 = m2 / norm;
                    nxt[0] = cur[0] * q0;
                    for (int c = 1; c <= u; c++) nxt[c] = cur[c] * q0 + cur[c - 1] * q1;
                    nxt[u + 1] = cur[u + 1] + cur[u] * q1;
                }
                for (int c = 0; c <= u && c < msgOcc[j].length; c++) msgOcc[j][c] = pre[n][c];
            }
        }
        // emit messages
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < k; j++) {
                double v = r[j][i];
                if (Double.isNaN(v) || Double.isInfinite(v) || v < 0) return false;
                msg[i][j] = v;
            }
            msg[i][k] = 1.0;
        }
        return true;
    }
}
