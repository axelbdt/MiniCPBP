package minicpbp.util;

/**
 * Exact weighted counting for the bin_packing constraint by the load-vector
 * dynamic program (BINPACKING_EXPERIMENT.md §4.1, research_plan.md §6.2).
 * The bin_packing analogue of {@link CountVectorDP}: the count vector becomes
 * a load vector and each item advances its bin's coordinate by its size
 * instead of by 1. At all sizes = 1 it IS the count-vector DP with an empty
 * "other" class, which is the unit test.
 *
 * <p>Model: n items, item i put into exactly one bin j &lt; m with weight
 * a[i][j] (the outside belief mass of b_i on bin j; 0 if j not in D(b_i)) and
 * fixed integer size size[i] &ge; 0. A configuration is accepted iff the load
 * c_j of every bin j lies in [low[j], up[j]]; it is additionally weighted by
 * the load-variable beliefs wLoad[j][c_j] when provided.
 *
 * <p>State = load vector (c_1..c_m), c_j &le; up[j], encoded in mixed radix
 * with stride[j] = prod_{j'&lt;j}(up[j']+1); placements that push any c_j past
 * up[j] are dropped (they violate the constraint, so they contribute zero).
 * One forward pass (all layers stored) and one rolling backward pass emit
 * every factor-to-variable MESSAGE (own outside belief excluded, as
 * setLocalBelief expects) plus the message to every load variable (own load
 * belief excluded), with no polynomial deflation anywhere (research_plan.md
 * §7 invariant: forward/backward only).
 *
 * <p>Cost O(n * S * m) time, (n+2) * S doubles of memory, S = prod(up[j]+1).
 * Per-layer normalization guards against under/overflow; the constant cancels
 * because callers row-normalize messages downstream.
 */
public final class BinLoadDP {

    private final int maxStates;
    private double[][] fwd;   // fwd[i][s], i = 0..n (layer i = product over items < i)
    private double[] bwd;     // rolling backward layer (product over items > i, incl. terminal)
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

    public BinLoadDP(int maxStates) {
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
     * @param n       number of items
     * @param m       number of bins
     * @param size    size[i] fixed integer size of item i (&ge; 0)
     * @param a       a[i][j] weight of item i on bin j; 0 if j not in D(b_i)
     * @param low     low[j] lower load bound (after intersecting with l_j's domain)
     * @param up      up[j] upper load bound
     * @param wLoad   wLoad[j][c] load belief for bin j at load c (length up[j]+1),
     *                or null (whole array or per-bin) for the 0/1 indicator of [low[j], up[j]]
     * @param msg     out: msg[i][j] message for item i, bin j
     * @param msgLoad out: msgLoad[j][c] message to load variable j at load c
     *                (length &ge; up[j]+1), or null if not wanted
     * @return total weighted count Z (with load beliefs folded in, up to the
     *         normalization tracked by {@link #logScale()}), or -1 if the
     *         state space exceeds maxStates
     */
    public double run(int n, int m, int[] size, double[][] a, int[] low, int[] up,
                      double[][] wLoad, double[][] msg, double[][] msgLoad) {
        long Sl = stateCount(up, maxStates);
        if (Sl < 0) return -1;
        int S = (int) Sl;
        ensure(n, S);

        int[] stride = new int[m];
        int st = 1;
        for (int j = 0; j < m; j++) {
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
            int sz = size[i];
            for (int s = 0; s < S; s++) {
                double p = cur[s];
                if (p == 0.0) continue;
                for (int j = 0; j < m; j++) {
                    double aij = a[i][j];
                    if (aij == 0.0) continue;
                    int cj = (s / stride[j]) % (up[j] + 1);
                    if (cj + sz <= up[j]) nxt[s + sz * stride[j]] += p * aij;
                }
            }
            logScale += normalize(nxt, S);
        }

        // terminal acceptance (per state): indicator of bounds x load beliefs.
        // message to load variables: exclude the bin's own wLoad factor.
        double z = 0.0;
        for (int s = 0; s < S; s++) {
            double p = fwd[n][s];
            double acc = 1.0;
            for (int j = 0; j < m; j++) {
                int cj = (s / stride[j]) % (up[j] + 1);
                if (cj < low[j]) { acc = 0.0; break; }
                double[] w = (wLoad == null) ? null : wLoad[j];
                if (w != null) acc *= w[cj];
            }
            bwd[s] = acc; // acceptance must be set for EVERY state: the backward
                          // pass serves messages even where the forward mass is 0
            if (p == 0.0) continue;
            z += p * acc;
            if (msgLoad != null) {
                for (int j = 0; j < m; j++) {
                    int cj = (s / stride[j]) % (up[j] + 1);
                    double accExcl = 1.0;
                    boolean ok = true;
                    for (int j2 = 0; j2 < m; j2++) {
                        if (j2 == j) continue;
                        int cj2 = (s / stride[j2]) % (up[j2] + 1);
                        if (cj2 < low[j2]) { ok = false; break; }
                        double[] w2 = (wLoad == null) ? null : wLoad[j2];
                        if (w2 != null) accExcl *= w2[cj2];
                    }
                    if (ok && msgLoad[j] != null) msgLoad[j][cj] += p * accExcl;
                }
            }
        }

        // backward, rolling, emitting messages
        for (int i = n - 1; i >= 0; i--) {
            double[] cur = fwd[i];
            double[] mi = msg[i];
            for (int j = 0; j < m; j++) mi[j] = 0.0;
            int sz = size[i];
            for (int s = 0; s < S; s++) {
                double p = cur[s];
                if (p == 0.0) continue;
                for (int j = 0; j < m; j++) {
                    int cj = (s / stride[j]) % (up[j] + 1);
                    if (cj + sz <= up[j]) {
                        double bj = bwd[s + sz * stride[j]];
                        if (bj != 0.0) mi[j] += p * bj;
                    }
                }
            }
            // roll backward: bwdNext[s] = sum_j a[i][j] * bwd[s + sz*stride_j]
            for (int s = 0; s < S; s++) {
                double acc = 0.0;
                for (int j = 0; j < m; j++) {
                    double aij = a[i][j];
                    if (aij == 0.0) continue;
                    int cj = (s / stride[j]) % (up[j] + 1);
                    if (cj + sz <= up[j]) acc += aij * bwd[s + sz * stride[j]];
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
            double inv = 1.0 / sum;
            for (int s = 0; s < S; s++) v[s] *= inv;
            return Math.log(sum);
        }
        return 0.0;
    }
}
