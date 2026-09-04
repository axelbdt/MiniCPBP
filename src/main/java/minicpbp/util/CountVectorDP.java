package minicpbp.util;

/**
 * Exact weighted counting for the cardinality (gcc) family by the
 * count-vector dynamic program (see GCC_EXPERIMENT.md §4.1 and
 * research_plan.md §5.4).
 *
 * <p>Model: n variables, each taking one of k counted value classes
 * (weight a[i][j], the outside belief mass of variable i on counted value j)
 * or the "other" class (weight b[i], the summed outside belief mass on the
 * uncounted values of its domain). A configuration is accepted iff the count
 * c_j of every class j lies in [low[j], up[j]]; it is additionally weighted
 * by the occurrence-variable beliefs wOcc[j][c_j] when provided.
 *
 * <p>State = count vector (c_1..c_k), c_j &le; up[j], encoded in mixed radix
 * with stride[j] = prod_{j'<j}(up[j']+1); assignments that push any c_j past
 * up[j] are dropped (they violate the constraint, so they contribute zero).
 * One forward pass (all layers stored) and one rolling backward pass emit
 * every factor-to-variable MESSAGE (own outside belief excluded, as
 * setLocalBelief expects) plus the message to every occurrence variable
 * (own occurrence belief excluded), with no polynomial deflation anywhere
 * (research_plan.md §7 invariant).
 *
 * <p>k = 1 is the among/count counter of research_plan.md §2.10.2 and the
 * unit test of the generic code.
 *
 * <p>Cost O(n * S * (k+1)) time, (n+2) * S doubles of memory, S = prod(up[j]+1).
 * Per-layer normalization guards against under/overflow; the constant cancels
 * because callers row-normalize messages downstream.
 */
public final class CountVectorDP {

    private final int maxStates;
    private double[][] fwd;   // fwd[i][s], i = 0..n (layer i = product over vars < i)
    private double[] bwd;     // rolling backward layer (product over vars > i, incl. terminal)
    private double[] bwdNext;
    private int allocN = -1;

    /** number of DP states for bounds up[], or -1 if it exceeds limit. */
    public static long stateCount(int[] up, long limit) {
        long s = 1;
        for (int u : up) {
            s *= (u + 1L);
            if (s > limit) return -1;
        }
        return s;
    }

    public CountVectorDP(int maxStates) {
        this.maxStates = maxStates;
    }

    private void ensure(int n, int S) {
        if (fwd == null || allocN < n || fwd[0].length < S) {
            fwd = new double[n + 1][S];
            bwd = new double[S];
            bwdNext = new double[S];
            allocN = n;
        }
    }

    /**
     * Runs the DP and writes results.
     *
     * @param n      number of variables
     * @param k      number of counted classes
     * @param a      a[i][j] weight of variable i on class j (i &lt; n, j &lt; k); 0 if v_j not in D(x_i)
     * @param b      b[i] weight of variable i on the "other" class (0 if none of its values is uncounted)
     * @param low    low[j] lower occurrence bound (after intersecting with o_j's domain)
     * @param up     up[j] upper occurrence bound
     * @param wOcc   wOcc[j][c] occurrence belief for class j at count c (length up[j]+1),
     *               or null (whole array or per-class) for the 0/1 indicator of [low[j], up[j]]
     * @param msg    out: msg[i][j] message for variable i, class j (j = k is the "other" class)
     * @param msgOcc out: msgOcc[j][c] message to occurrence variable j at count c (length &ge; up[j]+1), or null if not wanted
     * @return total weighted count Z (with occurrence beliefs folded in), or -1 if the state space exceeds maxStates
     */
    public double run(int n, int k, double[][] a, double[] b, int[] low, int[] up,
                      double[][] wOcc, double[][] msg, double[][] msgOcc) {
        long Sl = stateCount(up, maxStates);
        if (Sl < 0) return -1;
        int S = (int) Sl;
        ensure(n, S);

        int[] stride = new int[k];
        int st = 1;
        for (int j = 0; j < k; j++) {
            stride[j] = st;
            st *= (up[j] + 1);
        }

        // forward
        java.util.Arrays.fill(fwd[0], 0, S, 0.0);
        fwd[0][0] = 1.0;
        logScale = 0.0;
        for (int i = 0; i < n; i++) {
            double[] cur = fwd[i];
            double[] nxt = fwd[i + 1];
            java.util.Arrays.fill(nxt, 0, S, 0.0);
            double bi = b[i];
            for (int s = 0; s < S; s++) {
                double p = cur[s];
                if (p == 0.0) continue;
                if (bi != 0.0) nxt[s] += p * bi;
                for (int j = 0; j < k; j++) {
                    int cj = (s / stride[j]) % (up[j] + 1);
                    if (cj < up[j]) {
                        double aij = a[i][j];
                        if (aij != 0.0) nxt[s + stride[j]] += p * aij;
                    }
                }
            }
            // normalize layer to guard against under/overflow (constant cancels
            // in row-normalized messages; true Z = returned z * exp(logScale))
            logScale += normalize(nxt, S);
        }

        // terminal acceptance (per state): indicator of bounds x occurrence beliefs
        // message to occurrence variables: exclude the class's own wOcc factor
        double z = 0.0;
        for (int s = 0; s < S; s++) {
            double p = fwd[n][s];
            double acc = 1.0;
            for (int j = 0; j < k; j++) {
                int cj = (s / stride[j]) % (up[j] + 1);
                if (cj < low[j]) { acc = 0.0; break; }
                double[] w = (wOcc == null) ? null : wOcc[j];
                if (w != null) acc *= w[cj];
            }
            bwd[s] = acc; // acceptance must be set for EVERY state: the backward
                          // pass serves messages even where the forward mass is 0
            if (p == 0.0) continue;
            z += p * acc;
            if (msgOcc != null) {
                // accumulate occurrence messages: product over j' != j of the acceptance factors
                for (int j = 0; j < k; j++) {
                    int cj = (s / stride[j]) % (up[j] + 1);
                    double accExcl = 1.0;
                    boolean ok = true;
                    for (int j2 = 0; j2 < k; j2++) {
                        if (j2 == j) continue;
                        int cj2 = (s / stride[j2]) % (up[j2] + 1);
                        if (cj2 < low[j2]) { ok = false; break; }
                        double[] w2 = (wOcc == null) ? null : wOcc[j2];
                        if (w2 != null) accExcl *= w2[cj2];
                    }
                    if (ok && msgOcc[j] != null) msgOcc[j][cj] += p * accExcl;
                }
            }
        }

        // backward, rolling, emitting messages
        // bwd currently = layer n (terminal). msg for var i needs fwd[i] and bwd over vars > i.
        for (int i = n - 1; i >= 0; i--) {
            double[] cur = fwd[i];
            // messages for variable i
            double[] mi = msg[i];
            double mOther = 0.0;
            for (int j = 0; j <= k; j++) mi[j] = 0.0;
            for (int s = 0; s < S; s++) {
                double p = cur[s];
                if (p == 0.0) continue;
                double back = bwd[s];
                if (back != 0.0) mOther += p * back; // other class: count vector unchanged
                for (int j = 0; j < k; j++) {
                    int cj = (s / stride[j]) % (up[j] + 1);
                    if (cj < up[j]) {
                        double bj = bwd[s + stride[j]];
                        if (bj != 0.0) mi[j] += p * bj;
                    }
                }
            }
            mi[k] = mOther;
            // roll backward: bwdNext[s] = b[i]*bwd[s] + sum_j a[i][j]*bwd[s+stride_j]
            double bi = b[i];
            for (int s = 0; s < S; s++) {
                double acc = bi != 0.0 ? bi * bwd[s] : 0.0;
                for (int j = 0; j < k; j++) {
                    int cj = (s / stride[j]) % (up[j] + 1);
                    if (cj < up[j]) {
                        double aij = a[i][j];
                        if (aij != 0.0) acc += aij * bwd[s + stride[j]];
                    }
                }
                bwdNext[s] = acc;
            }
            double[] tmp = bwd; bwd = bwdNext; bwdNext = tmp;
            normalize(bwd, S);
        }
        return z;
    }

    /** log of the cumulative forward rescaling; true Z = run(...) * exp(logScale). */
    public double logScale() {
        return logScale;
    }

    private double logScale = 0.0;

    /** rescales v to sum 1 when its sum leaves [1e-100, 1e100]; returns log of the removed factor. */
    private static double normalize(double[] v, int S) {
        double sum = 0.0;
        for (int s = 0; s < S; s++) sum += v[s];
        if (sum > 0 && (sum < 1e-100 || sum > 1e100)) {
            double removed = 0.0;
            if (sum < 1e-200) {
                // A subnormal layer sum (a cavity belief of ~1e-323 reaching the
                // DP, WP2b on SeqEq-60-2-1-w8-k3 under topo) makes 1.0/sum
                // overflow to +Infinity and every message Inf or NaN. Lift the
                // layer into the normal range first; the two-step rescale is
                // exact up to the precision the subnormals had to begin with.
                for (int s = 0; s < S; s++) v[s] *= 1e200;
                sum *= 1e200;
                removed = 200.0 * Math.log(10.0);
            }
            double inv = 1.0 / sum;
            for (int s = 0; s < S; s++) v[s] *= inv;
            return Math.log(sum) - removed;
        }
        return 0.0;
    }
}
