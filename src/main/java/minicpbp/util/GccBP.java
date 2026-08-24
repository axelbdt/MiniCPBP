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
 * (research_plan.md §7, deflation ban). Degenerations at b = 0, asserted in
 * exp.GccBPEquivCheck.matchingDegenerations(): at low = 0, up = 1 the column
 * update is Williams &amp; Lau's AT-MOST-one message
 * nu = 1/(1 + sum_{l != i} mu_l) — identical to AssignmentBP, whose "+1" is
 * the value-left-unused slack — while at low = up = 1 it is the EXACTLY-one
 * update nu = 1/(sum_{l != i} mu_l), no "+1", i.e. the perfect-matching /
 * permanent SPA (Huang &amp; Jebara, arXiv:0908.1769). The two are distinct
 * local updates even on square systems where their global feasible sets
 * coincide.
 *
 * <p>All quantities are ratios r_{j->i} = nu(z=1)/nu(z=0), so the returned
 * factor-to-variable message for class j is r_{j->i} and for the "other"
 * class 1 (up to the row normalization the caller performs). The message
 * excludes the variable's own outside belief (message, not marginal).
 *
 * <p>Sparse edge lists (2026-08-24): a variable with a[i][j] == 0 has
 * mu_{i->j} = 0, i.e. an exact identity step (q0 = 1, q1 = 0) in the count
 * convolutions, and its leave-one-out set is the FULL edge set of class j —
 * the same for every such variable. The sweeps therefore visit only the
 * E = #{(i,j) : a[i][j] > 0} edges (per-class variable lists for the column
 * pass, per-variable class lists for the row pass), and one shared
 * "outsider" cavity ratio r0[j] per class, computed as the leave-one-out at
 * the phantom position D_j, is emitted for every non-edge pair. Edge
 * messages are bit-identical to the dense sweep (identity steps are exact);
 * non-edge messages agree up to summation-order rounding
 * (exp.GccBPEquivCheck vs the frozen exp.GccBPDenseLinearRef).
 *
 * <p>Cost per sweep: O(E + sum_j D_j * (min(up_j, D_j - cmin_j + 1) + 2))
 * with D_j the number of variables whose domain contains class j and cmin_j
 * the smallest count with nonzero potential weight (capped at low_j), plus
 * O(n*k) once per call to build the edge lists from the dense input matrix.
 * The min is the complement side (2026-08-24): selecting c successes among
 * D_j edges = selecting D_j - c failures, so a class whose feasible counts
 * sit near D_j (e.g. exact cardinality c_j > D_j/2) runs the count DP on
 * the failure side — O(D_j * min(c_j, D_j - c_j)) for exact cardinalities.
 * Dense wide-interval case: identical to the previous
 * O(sum_j n * (up_j + 2)); sparse domains (deep in search) no longer pay n
 * per class.
 *
 * <p>Convergence and warm start (2026-08-24, plan steps 2-3): the full
 * overload takes a warm flag that reuses the previous call's messages when
 * an FNV signature of the structure (n, k, low, up, edge set) matches — the
 * AssignmentBP scheme. Outside beliefs a/b/wOcc are deliberately NOT in the
 * signature: they change between BP iterations at a fixed search node, and
 * the stale fixed point of the neighbouring system is exactly the intended
 * starting point. The short overloads keep the historical semantics
 * (eps = 0, cold start) so the frozen-reference equivalence tests stay
 * meaningful.
 *
 * <p>Stop criterion (2026-08-24): a hard cap of `sweeps` sweeps, with early
 * stopping on the stability of the SOLVER-FACING beliefs rather than of the
 * raw messages. After sweep t, for every variable i,
 *
 * <pre>
 *   p_i^{(t)}(v) = theta_i(v) m_i^{(t)}(v) / sum_u theta_i(u) m_i^{(t)}(u)
 * </pre>
 *
 * with theta the outside belief and m the cavity message — for x_i the
 * weights a[i][j] r_{j->i} over its classes plus b[i] for "other", for the
 * occurrence variable o_j the weights wOcc[j][c] msgOcc[j][c] over
 * c in [low_j, up_j]. Since every value of a class shares one message and
 * its share theta_v / a_ij of the class weight is constant across sweeps,
 * the class-level TV equals the value-level TV, so collapsing loses
 * nothing. With TV(p, q) = 1/2 sum_v |p(v) - q(v)| and
 * R_t = max_i TV(p_i^{(t)}, p_i^{(t-1)}), the sweeps stop once
 * R_t &lt;= eps, subject to a minimum sweep count (2 cold, 1 warm; p^{(0)}
 * is read off the starting messages, which are r = 1 when cold). This asks
 * whether another sweep would materially move the beliefs the solver uses,
 * not whether the messages have reached a numerical fixed point.
 */
public final class GccBP {

    /** Default minimum number of sweeps before the stability test may fire. */
    public static final int DEFAULT_MIN_SWEEPS_COLD = 2;
    public static final int DEFAULT_MIN_SWEEPS_WARM = 1;

    private double[][] r;       // r[j][i], factor-to-variable ratio messages (edges only)
    private double[][] mu;       // mu[j][i], variable-to-factor ratio messages (edges only)
    private double[][] pre;      // prefix count distributions, (D+1) rows x (u+2) bins per class use
    private double[][] suf;
    private double[] r0;         // r0[j], cavity ratio shared by all non-edge variables of class j
    private int[] rowStart;      // n+1, per-variable slice of rowCls
    private int[] rowCls;        // E, classes j with a[i][j] > 0, i-major, j ascending
    private int[] colStart;      // k+1, per-class slice of colVar
    private int[] colVar;        // E, variables i with a[i][j] > 0, j-major, i ascending
    private int[] colFill;       // k, scratch cursor for building colVar
    private double[] pEdge;      // previous sweep's p_i(class j) on the edges, rowCls layout
    private double[] pOther;     // n, previous sweep's p_i(other)
    private double[][] pOcc;     // k x (maxU+2), previous sweep's p_{o_j}(c)
    private boolean havePOcc;    // pOcc holds a previous sweep (msgOcc is written during a sweep)
    private int allocN = -1, allocK = -1, allocU = -1;
    private long iterations = 0;

    // warm start bookkeeping
    private long signature = Long.MIN_VALUE;
    private boolean haveWarmStart = false;

    // instrumentation
    private long nbCalls;
    private long nbConverged;
    private long nbWarmStarts;
    private int lastIterations;
    private boolean lastConverged;

    public long nbIterations() {
        return iterations;
    }

    public long nbCalls() {
        return nbCalls;
    }

    public long nbConverged() {
        return nbConverged;
    }

    public long nbWarmStarts() {
        return nbWarmStarts;
    }

    public int lastIterations() {
        return lastIterations;
    }

    public boolean lastConverged() {
        return lastConverged;
    }

    /** Discards the cached messages; the next warm run() starts from r = 1. */
    public void invalidateWarmStart() {
        haveWarmStart = false;
    }

    private void ensure(int n, int k, int maxU) {
        if (allocN < n || allocK < k || allocU < maxU) {
            r = new double[k][n];
            mu = new double[k][n];
            pre = new double[n + 1][maxU + 2];
            suf = new double[n + 1][maxU + 2];
            r0 = new double[k];
            rowStart = new int[n + 1];
            colStart = new int[k + 1];
            colFill = new int[k];
            pOther = new double[n];
            pOcc = new double[k][maxU + 2];
            allocN = n;
            allocK = k;
            allocU = maxU;
            haveWarmStart = false; // messages live in r/r0
        }
    }

    private void ensureEdges(int e) {
        if (rowCls == null || rowCls.length < e) {
            int cap = Math.max(16, e);
            rowCls = new int[cap];
            colVar = new int[cap];
            pEdge = new double[cap];
        }
    }

    /**
     * R over the x variables: max_i TV(p_i^{(t)}, p_i^{(t-1)}) of the
     * normalised solver-facing beliefs, weights a[i][j] r_{j->i} on the
     * classes and b[i] on "other". Overwrites pEdge/pOther with p^{(t)};
     * called once before the first sweep to seed them, its result then being
     * meaningless and discarded.
     */
    private double rowBeliefChange(int n, double[][] a, double[] b) {
        double maxTv = 0.0;
        for (int i = 0; i < n; i++) {
            int rs = rowStart[i], re = rowStart[i + 1];
            double[] ai = a[i];
            double tot = b[i];
            for (int q = rs; q < re; q++) {
                int j = rowCls[q];
                tot += ai[j] * r[j][i];
            }
            double tv;
            if (tot > 0.0) {
                double po = b[i] / tot;
                tv = Math.abs(po - pOther[i]);
                pOther[i] = po;
                for (int q = rs; q < re; q++) {
                    int j = rowCls[q];
                    double pq = ai[j] * r[j][i] / tot;
                    tv += Math.abs(pq - pEdge[q]);
                    pEdge[q] = pq;
                }
            } else {
                tv = Math.abs(pOther[i]);
                pOther[i] = 0.0;
                for (int q = rs; q < re; q++) {
                    tv += Math.abs(pEdge[q]);
                    pEdge[q] = 0.0;
                }
            }
            tv *= 0.5;
            if (tv > maxTv) maxTv = tv;
        }
        return maxTv;
    }

    /**
     * R over the occurrence variables: the same quantity for the beliefs
     * wOcc[j][c] msgOcc[j][c] normalised over c in [low_j, up_j], the range
     * CardinalityDC reads. Overwrites pOcc; the caller discards the result
     * of the first sweep, which has no predecessor to compare against.
     */
    private double occBeliefChange(int k, int[] low, int[] up, double[][] wOcc, double[][] msgOcc) {
        double maxTv = 0.0;
        for (int j = 0; j < k; j++) {
            double[] w = (wOcc == null) ? null : wOcc[j];
            double[] oj = msgOcc[j];
            double[] pj = pOcc[j];
            int lo = low[j], uP = Math.min(up[j], oj.length - 1);
            double z = 0.0;
            for (int c = lo; c <= uP; c++) z += weightAt(w, lo, up[j], c) * oj[c];
            double tv = 0.0;
            for (int c = 0; c < pj.length; c++) {
                double pc = (z > 0.0 && c >= lo && c <= uP)
                        ? weightAt(w, lo, up[j], c) * oj[c] / z : 0.0;
                tv += Math.abs(pc - pj[c]);
                pj[c] = pc;
            }
            tv *= 0.5;
            if (tv > maxTv) maxTv = tv;
        }
        return maxTv;
    }

    /**
     * Cardinality-potential weight at primal count c: wOcc[j][c] when given
     * (its array covers 0..up), else the [low, up] indicator; 0 outside
     * [0, up]. Total-range safe so the complement pass can probe shifted
     * counts (c = D - d + 1 may exceed up or reach -1) without guards.
     */
    private static double weightAt(double[] w, int low, int up, int c) {
        if (c < 0 || c > up) return 0.0;
        return (w != null) ? w[c] : (c >= low ? 1.0 : 0.0);
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
        return run(n, k, a, b, low, up, wOcc, sweeps, msg, null, 0.0, false);
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
        return run(n, k, a, b, low, up, wOcc, sweeps, msg, msgOcc, 0.0, false);
    }

    /**
     * Full form with the belief-stability stop criterion and warm start.
     *
     * @param eps  early-exit threshold on R_t, the max over variables of the
     *             total-variation distance between the normalised
     *             solver-facing beliefs of two consecutive sweeps; 0 or less
     *             disables early exit, restoring fixed sweeps
     * @param warm reuse the previous call's messages as the starting point
     *             when the structure signature (n, k, low, up, edge set)
     *             matches; outside beliefs are intentionally not part of
     *             the signature
     */
    public boolean run(int n, int k, double[][] a, double[] b, int[] low, int[] up,
                       double[][] wOcc, int sweeps, double[][] msg, double[][] msgOcc,
                       double eps, boolean warm) {
        return run(n, k, a, b, low, up, wOcc, sweeps, msg, msgOcc, eps, warm,
                DEFAULT_MIN_SWEEPS_COLD, DEFAULT_MIN_SWEEPS_WARM);
    }

    /**
     * As {@link #run(int, int, double[][], double[], int[], int[], double[][], int, double[][], double[][], double, boolean)},
     * with the minimum sweep counts spelled out.
     *
     * @param minCold minimum sweeps when the messages start from r = 1
     * @param minWarm minimum sweeps when the previous call's messages are reused
     */
    public boolean run(int n, int k, double[][] a, double[] b, int[] low, int[] up,
                       double[][] wOcc, int sweeps, double[][] msg, double[][] msgOcc,
                       double eps, boolean warm, int minCold, int minWarm) {
        nbCalls++;
        int maxU = 0;
        for (int j = 0; j < k; j++) maxU = Math.max(maxU, up[j]);
        ensure(n, k, maxU);

        // ---- edge lists (per-variable classes, per-class variables) ----
        java.util.Arrays.fill(colStart, 0, k + 1, 0);
        int nbEdges = 0;
        for (int i = 0; i < n; i++) {
            double[] ai = a[i];
            for (int j = 0; j < k; j++) {
                if (ai[j] > 0.0) {
                    colStart[j + 1]++;
                    nbEdges++;
                }
            }
        }
        ensureEdges(nbEdges);
        for (int j = 0; j < k; j++) colStart[j + 1] += colStart[j];
        System.arraycopy(colStart, 0, colFill, 0, k);
        long sig = 1469598103934665603L;
        sig = sig * 1099511628211L + n;
        sig = sig * 1099511628211L + k;
        for (int j = 0; j < k; j++) {
            sig = sig * 1099511628211L + low[j];
            sig = sig * 1099511628211L + up[j];
        }
        int e = 0;
        for (int i = 0; i < n; i++) {
            rowStart[i] = e;
            double[] ai = a[i];
            for (int j = 0; j < k; j++) {
                if (ai[j] > 0.0) {
                    rowCls[e++] = j;
                    colVar[colFill[j]++] = i; // i ascending per class: dense sweep order
                    sig = sig * 1099511628211L + (i * 131L + j);
                }
            }
        }
        rowStart[n] = e;

        boolean warmed = warm && haveWarmStart && sig == signature;
        signature = sig;
        haveWarmStart = true;
        if (warmed) {
            nbWarmStarts++;
        } else {
            for (int j = 0; j < k; j++) {
                java.util.Arrays.fill(r[j], 0, n, 1.0);
                r0[j] = 1.0;
            }
        }

        int minSweeps = Math.max(1, warmed ? minWarm : minCold);
        rowBeliefChange(n, a, b); // p^{(0)}, read off the starting messages
        havePOcc = false;         // msgOcc only exists once a sweep has run

        boolean converged = false;
        int it = 0;
        for (; it < sweeps; it++) {
            iterations++;
            // variable -> factor messages (ratio of "i takes class j" to "i does not")
            for (int i = 0; i < n; i++) {
                int rs = rowStart[i], re = rowStart[i + 1];
                if (rs == re) continue; // all mass on "other": no edges, no messages
                double[] ai = a[i];
                double tot = b[i];
                for (int q = rs; q < re; q++) {
                    int j = rowCls[q];
                    tot += ai[j] * r[j][i];
                }
                for (int q = rs; q < re; q++) {
                    int j = rowCls[q];
                    double excl = tot - ai[j] * r[j][i];
                    if (excl < 1e-12 * tot) { // catastrophic cancellation guard: recompute
                        excl = b[i];
                        for (int q2 = rs; q2 < re; q2++) {
                            if (q2 == q) continue;
                            int j2 = rowCls[q2];
                            excl += ai[j2] * r[j2][i];
                        }
                    }
                    double v = (excl > 0) ? (ai[j] / excl) : 1e12; // ai[j] > 0 on every edge
                    mu[j][i] = (v > 1e12) ? 1e12 : v;
                }
            }
            // factor -> variable messages by prefix/suffix convolution per class,
            // over the class's edge list only
            for (int j = 0; j < k; j++) {
                int cs = colStart[j];
                int D = colStart[j + 1] - cs;
                int lo = low[j];
                int uP = up[j];
                double[] w = (wOcc == null) ? null : wOcc[j];
                // ---- complement side (2026-08-24): selecting c successes
                // among D edges = selecting D - c failures. With cmin the
                // smallest count carrying nonzero potential weight, the
                // complement count d = D - c needs bins only up to
                // D - cmin (+1 so the outsider's shifted weight w(cmin) at
                // edge-count cmin - 1 stays representable). Engaged when
                // strictly cheaper: O(D * min(uP, D - cmin + 1)) per class,
                // the O(D * min(c, D - c)) dual for exact cardinalities.
                // cmin is capped at lo (not just the weight support) so the
                // consumer-visible msgOcc range [lo, uP] stays exact; below
                // cmin - 1 the complement's msgOcc entries are zeroed
                // (unrepresentable overflow mass), which no consumer reads
                // (CardinalityDC masks to [lo, uP], GccPhase1 iterates it).
                int cmin = lo;
                if (w != null) {
                    int f = 0;
                    while (f <= uP && w[f] == 0.0) f++;
                    if (f <= uP && f < cmin) cmin = f; // general callers may put weight below lo
                }
                int uC = D - cmin + 1;
                boolean comp = uC >= 1 && uC < uP;
                int u = comp ? uC : uP;
                int bins = u + 2; // counts 0..u and an overflow bin at u+1 (kept for normalization only)
                // prefix: pre[q] = count distribution of the first q edges
                // (complement: distribution of failures, q0/q1 swapped)
                double[] p0 = pre[0];
                java.util.Arrays.fill(p0, 0, bins, 0.0);
                p0[0] = 1.0;
                for (int q = 0; q < D; q++) {
                    double[] cur = pre[q];
                    double[] nxt = pre[q + 1];
                    double m = mu[j][colVar[cs + q]];
                    double norm = 1.0 + m;
                    // normalized Bernoulli, keeps numbers in range
                    double q0 = comp ? m / norm : 1.0 / norm;
                    double q1 = comp ? 1.0 / norm : m / norm;
                    nxt[0] = cur[0] * q0;
                    for (int c = 1; c <= u; c++) nxt[c] = cur[c] * q0 + cur[c - 1] * q1;
                    nxt[u + 1] = cur[u + 1] + cur[u] * q1; // overflow absorbs
                }
                // occurrence-variable message: the full convolution pre[D] of
                // this sweep's variable-to-factor messages; the last sweep's
                // copy is what the caller keeps (bit-identical to the former
                // post-loop recomputation, which used the same final mu)
                if (msgOcc != null) {
                    double[] pd = pre[D];
                    double[] oj = msgOcc[j];
                    if (comp) {
                        for (int c = 0; c <= uP && c < oj.length; c++) {
                            int d = D - c;
                            oj[c] = (d >= 0 && d <= u) ? pd[d] : 0.0;
                        }
                    } else {
                        for (int c = 0; c <= u && c < oj.length; c++) oj[c] = pd[c];
                    }
                }
                // WEIGHTED backward (2026-08-19, TODO.md item 2): suf[q][c] =
                // sum over assignments of the edges q..D-1 of (prod q) * w(c + count),
                // the same O(D*u) construction as BinPackingBP.java:128-144.
                // Complement: seeded with the reversed weights w'(d) = w(D-d),
                // defined against the FULL degree D, so the leave-one-out
                // shift below needs no re-indexing.
                double[] s0 = suf[D];
                for (int c = 0; c <= u; c++)
                    s0[c] = comp ? weightAt(w, lo, uP, D - c) : weightAt(w, lo, uP, c);
                s0[u + 1] = 0.0; // counts beyond u carry zero weight
                for (int q = D - 1; q >= 0; q--) {
                    double[] cur = suf[q + 1];
                    double[] nxt = suf[q];
                    double m = mu[j][colVar[cs + q]];
                    double norm = 1.0 + m;
                    double q0 = comp ? m / norm : 1.0 / norm;
                    double q1 = comp ? 1.0 / norm : m / norm;
                    for (int c = 0; c <= u; c++) nxt[c] = cur[c] * q0 + cur[c + 1] * q1;
                    nxt[u + 1] = 0.0;
                }
                // leave-one-out in O(u) per edge:
                //   den (z_ij = 0) = sum_c pre[q][c] * suf[q+1][c]
                //   num (z_ij = 1) = sum_c pre[q][c] * suf[q+1][c+1]
                // complement: the same two sums swap roles — the unshifted
                // pairing is z'_ij = 0, i.e. z_ij = 1 (the D-based seed
                // absorbs the +1 count shift).
                // q = D is the phantom outsider: leave-one-out of a variable
                // with mu = 0 is the full edge set, pre[D] against the weight
                // seed suf[D] — one shared ratio for every non-edge variable.
                // In complement mode the outsider shifts the PRIMAL count up
                // (num0 pairs pre[D](d) with w(D-d+1)), computed directly.
                for (int q = 0; q <= D; q++) {
                    double num, den;
                    if (comp && q == D) {
                        double[] pd = pre[D];
                        num = 0.0;
                        den = 0.0;
                        for (int d = 0; d <= u; d++) {
                            num += pd[d] * weightAt(w, lo, uP, D - d + 1);
                            den += pd[d] * s0[d];
                        }
                    } else {
                        double[] pi = pre[q];
                        double[] si = suf[q < D ? q + 1 : D];
                        double sum0 = 0.0, sum1 = 0.0;
                        for (int c = 0; c <= u; c++) {
                            sum0 += pi[c] * si[c];
                            if (c < u) sum1 += pi[c] * si[c + 1];
                        }
                        num = comp ? sum0 : sum1;
                        den = comp ? sum1 : sum0;
                    }
                    double ratio;
                    if (den > 0) ratio = num / den;
                    else if (num > 0) ratio = 1e12;
                    else ratio = 0.0;
                    if (Double.isNaN(ratio) || Double.isInfinite(ratio)) {
                        haveWarmStart = false; // partially updated messages: cold-start next call
                        return false;
                    }
                    if (ratio > 1e12) ratio = 1e12;
                    if (q < D) r[j][colVar[cs + q]] = ratio;
                    else r0[j] = ratio;
                }
            }
            // stability of the solver-facing beliefs, not of the messages
            double R = rowBeliefChange(n, a, b);
            if (msgOcc != null) {
                double rOcc = occBeliefChange(k, low, up, wOcc, msgOcc);
                if (havePOcc && rOcc > R) R = rOcc;
                havePOcc = true;
            }
            if (eps > 0.0 && it + 1 >= minSweeps && R <= eps) {
                converged = true;
                it++;
                break;
            }
        }
        lastIterations = it;
        lastConverged = converged;
        if (converged) nbConverged++;
        // emit messages: edges carry their own cavity ratio, non-edges the
        // shared outsider ratio (read by the caller only for in-domain values
        // whose outside belief happens to be zero)
        for (int i = 0; i < n; i++) {
            double[] mi = msg[i];
            for (int j = 0; j < k; j++) {
                double v = r0[j];
                if (Double.isNaN(v) || Double.isInfinite(v) || v < 0) {
                    haveWarmStart = false;
                    return false;
                }
                mi[j] = v;
            }
            for (int q = rowStart[i]; q < rowStart[i + 1]; q++) {
                int j = rowCls[q];
                double v = r[j][i];
                if (Double.isNaN(v) || Double.isInfinite(v) || v < 0) {
                    haveWarmStart = false;
                    return false;
                }
                mi[j] = v;
            }
            mi[k] = 1.0;
        }
        return true;
    }
}
