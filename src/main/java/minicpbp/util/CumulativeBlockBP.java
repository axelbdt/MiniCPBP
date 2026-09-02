package minicpbp.util;

/**
 * Nested belief propagation for the cumulative constraint with <b>block
 * factors</b>: DISJUNCTIVE_CUMULATIVE_PLAN.md amendment A3 (§6.12).
 *
 * <p>Same internal model as {@link CumulativeBP} — one categorical node per
 * job over its start values, capacity checked at every time slot — but the
 * relative time axis [0, T) is partitioned into blocks of k consecutive
 * slots and one factor Φ_B per block checks the capacity at every slot of
 * the block simultaneously:
 * Φ_B = Π_{t∈B} 1[ Σ_{i∈A_B} dem_i · 1[t − p_i &lt; x_i ≤ t] ≤ C ].
 * The partition keeps the factorisation exact (every slot is checked exactly
 * once); a job of duration p now sits in about p/k factors instead of p, so
 * the loopiness the slot factorisation suffers from — the deterministic
 * correlation between consecutive slots occupied by the same job, which slot
 * factors only see through x_i — is resolved inside the factor for slots of
 * the same block. Blocks are anchored at multiples of k in absolute time, so
 * the partition does not move as domains shrink during search. k = 1 is the
 * slot engine; a single block over the horizon is exact enumeration.
 *
 * <p>Job → block messages are categorical over the job's <i>occupancy
 * pattern</i> in the block: the intersection of its window [v, v+p) with the
 * block [B, B+w) is a sub-interval [B+a, B+b), 0 ≤ a &lt; b ≤ w, or empty.
 * Pattern id 0 is empty, 1 + a·k + (b−1) otherwise (P = k² + 1 ids). Every
 * pattern but "full" is realised by at most one start value; "full" by a
 * contiguous range; "empty" by a prefix plus a suffix of the sorted domain.
 * The cavity mass of a pattern is the sum of the window masses of its values
 * with the block's own ratio divided out — the slot engine's scheme, one
 * ratio per pattern instead of one per slot. Prefix sums of log r(full)
 * along the job's block axis give the interior of a window in O(1); the two
 * boundary blocks are direct lookups.
 *
 * <p>Block → job ratios r_B,i(π) = m(π)/m(∅) come from a forward load
 * distribution over the jobs of A_B on the state space {0..C}^w (a load per
 * slot of the block, encoded in base C+1) and a backward acceptance layer,
 * combined leave-one-out — prefix and suffix, never a division out of a
 * convolution. A transition adds dem_i to every slot of the pattern and is
 * dropped when any slot overflows; the feasibility test is a table lookup on
 * the pattern's maximum digit. Cost per block O(|A_B| · (C+1)^w · P_w);
 * for C = 1 the state space is 2^w.
 *
 * <p>Stop rule, guards (structural zeros only, Bernoulli-style clamps on the
 * categorical) and the emitted message Π_B r_B,i(pattern_B(v)) are those of
 * {@link CumulativeBP}. Cold start every call.
 */
public final class CumulativeBlockBP {

    public static final int OK = 0;
    public static final int DECLINED = 1;
    public static final int NUMERICAL = 2;

    private static final double RATIO_CAP = 1e12;
    private static final double RATIO_FLOOR = 1e-100;
    private static final double Q_MIN = 1e-15;

    private final int K;   // block width
    private final int P;   // pattern ids per (block, job) edge: K*K + 1

    // ----- structure of the current system -----
    private int T, nb, tmin;
    private int[] est, lct;
    private boolean[] inert;
    private int[] cntDiff, demDiff;
    private boolean[] blkLive;
    private int[] width, cntB, off, fill;
    private int[] jobs;
    private int[][] pos;   // pos[i][b - bLo[i]] = flat edge index, or -1
    private int[] bLo, bHi;

    // ----- messages, per flat edge e and pattern id: index e*P + pid -----
    private double[] r, q;

    // ----- per-job scratch -----
    private double[] lpFull, zcFull;
    private double[] logQ, W, wPre, wSuf;
    private int[] zr, sRel;
    private double[][] pPrev;
    private final double[] sumW, sumW1, numP;

    // ----- knapsack scratch and tables (keyed by C) -----
    private int tableC = -1;
    private int[] pow;               // (C+1)^c
    private int[] shift;             // Σ_{c∈[a,b)} pow[c] per pattern id
    private int[][] pidsByW;         // valid pattern ids for a block of width w
    private int[][][] maxDigByW;     // [w][pid][state] max digit over the pattern's slots
    private double[][] pre, bwd;

    // ----- instrumentation -----
    private long opsPerSweep;
    private int lastSweeps;
    private boolean lastConverged;
    private long nbCalls, nbSweeps, nbConverged;

    public CumulativeBlockBP(int blockWidth) {
        if (blockWidth < 1) throw new IllegalArgumentException("block width must be >= 1");
        this.K = blockWidth;
        this.P = K * K + 1;
        this.sumW = new double[P];
        this.sumW1 = new double[P];
        this.numP = new double[P];
    }

    public int blockWidth() {
        return K;
    }

    public long opsPerSweep() {
        return opsPerSweep;
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

    private static int pid(int a, int b, int K) {
        return 1 + a * K + (b - 1);
    }

    /** Same contract as {@link CumulativeBP#run}. */
    public int run(int n, int[][] dom, int[] size, double[][] a, int[] p, int[] dem, int C,
                   int sweeps, double eps, int minSweeps, long opsBudget, double[][] out) {
        nbCalls++;
        lastSweeps = 0;
        lastConverged = false;
        tables(C);
        if (!layout(n, dom, size, p, dem, C)) {
            for (int i = 0; i < n; i++)
                java.util.Arrays.fill(out[i], 0, size[i], (dem[i] > C && p[i] > 0 && dem[i] > 0) ? 0.0 : 1.0);
            return OK;
        }
        if (opsBudget > 0 && opsPerSweep > opsBudget) return DECLINED;

        int flat = off[nb];
        java.util.Arrays.fill(r, 0, flat * P, 1.0);
        int minIt = Math.max(1, minSweeps);
        boolean converged = false;
        int it = 0;
        for (; ; ) {
            double R = jobPass(n, dom, size, a, p, it == 0);
            if (Double.isNaN(R)) return NUMERICAL;
            if (it > 0 && eps > 0.0 && it >= minIt && R <= eps) {
                converged = true;
                break;
            }
            if (it >= sweeps) break;
            if (!factorPass(dem, C)) return NUMERICAL;
            it++;
        }
        nbSweeps += it;
        lastSweeps = it;
        lastConverged = converged;
        if (converged) nbConverged++;
        for (int i = 0; i < n; i++) {
            if (inert[i]) {
                java.util.Arrays.fill(out[i], 0, size[i], (dem[i] > C && p[i] > 0 && dem[i] > 0) ? 0.0 : 1.0);
                continue;
            }
            axisPrefix(i);
            double M = valueMass(i, dom[i], size[i], p[i], null);
            for (int k = 0; k < size[i]; k++) {
                double v = (zr[k] > 0) ? 0.0 : Math.max(Math.exp(logQ[k] - M), Double.MIN_NORMAL);
                if (Double.isNaN(v) || Double.isInfinite(v)) return NUMERICAL;
                out[i][k] = v;
            }
        }
        return OK;
    }

    // ------------------------------------------------------------------
    // tables
    // ------------------------------------------------------------------

    /** Powers of C+1, pattern shifts and the max-digit tables for every width 1..K. Rebuilt when C changes. */
    private void tables(int C) {
        if (C == tableC) return;
        tableC = C;
        int B = C + 1;
        pow = new int[K + 1];
        pow[0] = 1;
        for (int c = 1; c <= K; c++) pow[c] = pow[c - 1] * B;
        shift = new int[P];
        for (int a = 0; a < K; a++)
            for (int b = a + 1; b <= K; b++) {
                int s = 0;
                for (int c = a; c < b; c++) s += pow[c];
                shift[pid(a, b, K)] = s;
            }
        pidsByW = new int[K + 1][];
        maxDigByW = new int[K + 1][][];
        for (int w = 1; w <= K; w++) {
            int cntP = w * (w + 1) / 2;
            int[] ids = new int[cntP];
            int u = 0;
            for (int a = 0; a < w; a++)
                for (int b = a + 1; b <= w; b++) ids[u++] = pid(a, b, K);
            pidsByW[w] = ids;
            int S = pow[w];
            int[][] md = new int[P][];
            for (int id : ids) {
                int a = (id - 1) / K, b = (id - 1) % K + 1;
                int[] tab = new int[S];
                for (int st = 0; st < S; st++) {
                    int m = 0;
                    for (int c = a; c < b; c++) {
                        int dg = (st / pow[c]) % B;
                        if (dg > m) m = dg;
                    }
                    tab[st] = m;
                }
                md[id] = tab;
            }
            maxDigByW[w] = md;
        }
        pre = null; // state width may have changed
    }

    // ------------------------------------------------------------------
    // structure
    // ------------------------------------------------------------------

    private boolean layout(int n, int[][] dom, int[] size, int[] p, int[] dem, int C) {
        if (est == null || est.length < n) {
            est = new int[n];
            lct = new int[n];
            inert = new boolean[n];
            bLo = new int[n];
            bHi = new int[n];
            pos = new int[n][];
            pPrev = new double[n][];
        }
        tmin = Integer.MAX_VALUE;
        int tmax = Integer.MIN_VALUE;
        int live = 0;
        for (int i = 0; i < n; i++) {
            inert[i] = size[i] == 0 || p[i] == 0 || dem[i] == 0 || dem[i] > C;
            if (inert[i]) continue;
            live++;
            tmin = Math.min(tmin, dom[i][0]);
            tmax = Math.max(tmax, dom[i][size[i] - 1] + p[i]);
        }
        opsPerSweep = 0;
        if (live < 2) {
            T = 0;
            nb = 0;
            return false;
        }
        // blocks are anchored at multiples of K in ABSOLUTE time, so the
        // factorisation is a property of the model, not of the current
        // domains: the relative axis starts at the block boundary at or
        // below the earliest start
        tmin = Math.floorDiv(tmin, K) * K;
        T = tmax - tmin;
        nb = (T + K - 1) / K;
        if (cntDiff == null || cntDiff.length < T + 2) {
            cntDiff = new int[T + 2];
            demDiff = new int[T + 2];
        }
        if (blkLive == null || blkLive.length < nb + 1) {
            blkLive = new boolean[nb + 1];
            width = new int[nb + 1];
            cntB = new int[nb + 1];
            off = new int[nb + 2];
            fill = new int[nb + 1];
        }
        java.util.Arrays.fill(cntDiff, 0, T + 1, 0);
        java.util.Arrays.fill(demDiff, 0, T + 1, 0);
        for (int i = 0; i < n; i++) {
            if (inert[i]) continue;
            est[i] = dom[i][0] - tmin;
            lct[i] = dom[i][size[i] - 1] + p[i] - tmin;
            cntDiff[est[i]]++;
            cntDiff[lct[i]]--;
            demDiff[est[i]] += dem[i];
            demDiff[lct[i]] -= dem[i];
            bLo[i] = est[i] / K;
            bHi[i] = (lct[i] - 1) / K;
        }
        java.util.Arrays.fill(blkLive, 0, nb, false);
        java.util.Arrays.fill(cntB, 0, nb, 0);
        int c = 0, d = 0;
        boolean any = false;
        for (int t = 0; t < T; t++) {
            c += cntDiff[t];
            d += demDiff[t];
            if (c >= 2 && d > C) {
                blkLive[t / K] = true;
                any = true;
            }
        }
        if (!any) return false;
        for (int b = 0; b < nb; b++) width[b] = Math.min(K, T - b * K);
        for (int i = 0; i < n; i++) {
            if (inert[i]) continue;
            for (int b = bLo[i]; b <= bHi[i]; b++) if (blkLive[b]) cntB[b]++;
        }
        off[0] = 0;
        int maxCnt = 0;
        for (int b = 0; b < nb; b++) {
            off[b + 1] = off[b] + cntB[b];
            if (cntB[b] > maxCnt) maxCnt = cntB[b];
            if (blkLive[b]) opsPerSweep += (long) cntB[b] * pow[width[b]] * pidsByW[width[b]].length;
        }
        int flat = off[nb];
        if (r == null || r.length < flat * P) {
            r = new double[flat * P];
            q = new double[flat * P];
        }
        if (jobs == null || jobs.length < flat) jobs = new int[flat];
        java.util.Arrays.fill(fill, 0, nb, 0);
        int maxBlocks = 0, maxSize = 0;
        for (int i = 0; i < n; i++) {
            if (inert[i]) continue;
            int nbi = bHi[i] - bLo[i] + 1;
            maxBlocks = Math.max(maxBlocks, nbi);
            maxSize = Math.max(maxSize, size[i]);
            if (pos[i] == null || pos[i].length < nbi) pos[i] = new int[nbi];
            if (pPrev[i] == null || pPrev[i].length < size[i]) pPrev[i] = new double[size[i]];
            int[] pi = pos[i];
            for (int b = bLo[i]; b <= bHi[i]; b++) {
                if (blkLive[b]) {
                    int e = off[b] + fill[b]++;
                    jobs[e] = i;
                    pi[b - bLo[i]] = e;
                } else {
                    pi[b - bLo[i]] = -1;
                }
            }
        }
        if (lpFull == null || lpFull.length < maxBlocks + 1) {
            lpFull = new double[maxBlocks + 1];
            zcFull = new double[maxBlocks + 1];
        }
        if (logQ == null || logQ.length < maxSize + 1) {
            logQ = new double[maxSize + 1];
            W = new double[maxSize + 1];
            wPre = new double[maxSize + 2];
            wSuf = new double[maxSize + 2];
            zr = new int[maxSize + 1];
            sRel = new int[maxSize + 1];
        }
        int S = pow[K];
        if (pre == null || pre.length < maxCnt + 1 || pre[0].length < S) {
            pre = new double[maxCnt + 1][S];
            bwd = new double[maxCnt + 1][S];
        }
        return true;
    }

    // ------------------------------------------------------------------
    // job side
    // ------------------------------------------------------------------

    /** Prefix sums along job i's block axis of log r(full) and of zero counts. */
    private void axisPrefix(int i) {
        int nbi = bHi[i] - bLo[i] + 1;
        int[] pi = pos[i];
        lpFull[0] = 0.0;
        zcFull[0] = 0.0;
        for (int j = 0; j < nbi; j++) {
            int e = pi[j];
            double rv = 1.0;
            if (e >= 0) {
                int w = width[bLo[i] + j];
                rv = r[e * P + pid(0, w, K)];
            }
            if (rv == 0.0) {
                zcFull[j + 1] = zcFull[j] + 1.0;
                lpFull[j + 1] = lpFull[j];
            } else {
                zcFull[j + 1] = zcFull[j];
                lpFull[j + 1] = lpFull[j] + Math.log(rv);
            }
        }
    }

    /**
     * logQ[k], zr[k], sRel[k] for the values of job i from the current
     * ratios: the two boundary blocks by direct lookup, the interior from
     * the prefix sums. Returns the shift M (max finite logQ over values with
     * positive weight, 0 when none).
     */
    private double valueMass(int i, int[] domi, int sz, int p, double[] ai) {
        double M = Double.NEGATIVE_INFINITY;
        int[] pi = pos[i];
        int lo = bLo[i];
        for (int k = 0; k < sz; k++) {
            int s = domi[k] - tmin;
            sRel[k] = s;
            int b0 = s / K, b1 = (s + p - 1) / K;
            double lq = 0.0;
            int z = 0;
            if (b0 == b1) {
                int e = pi[b0 - lo];
                if (e >= 0) {
                    double rv = r[e * P + pid(s - b0 * K, s + p - b0 * K, K)];
                    if (rv == 0.0) z++;
                    else lq += Math.log(rv);
                }
            } else {
                int e0 = pi[b0 - lo];
                if (e0 >= 0) {
                    double rv = r[e0 * P + pid(s - b0 * K, K, K)];
                    if (rv == 0.0) z++;
                    else lq += Math.log(rv);
                }
                lq += lpFull[b1 - lo] - lpFull[b0 + 1 - lo];
                z += (int) (zcFull[b1 - lo] - zcFull[b0 + 1 - lo]);
                int e1 = pi[b1 - lo];
                if (e1 >= 0) {
                    double rv = r[e1 * P + pid(0, s + p - b1 * K, K)];
                    if (rv == 0.0) z++;
                    else lq += Math.log(rv);
                }
            }
            logQ[k] = lq;
            zr[k] = z;
            if (z == 0 && (ai == null || ai[k] > 0.0) && lq > M) M = lq;
        }
        return (M == Double.NEGATIVE_INFINITY) ? 0.0 : M;
    }

    private double jobPass(int n, int[][] dom, int[] size, double[][] a, int[] p, boolean first) {
        double maxTv = 0.0;
        for (int i = 0; i < n; i++) {
            if (inert[i]) continue;
            int sz = size[i];
            int pi = p[i];
            double[] ai = a[i];
            axisPrefix(i);
            double M = valueMass(i, dom[i], sz, pi, ai);
            double tot = 0.0;
            for (int k = 0; k < sz; k++) {
                double w = (zr[k] > 0) ? 0.0 : ai[k] * Math.exp(logQ[k] - M);
                W[k] = w;
                tot += w;
            }
            double[] pp = pPrev[i];
            double tv = 0.0;
            for (int k = 0; k < sz; k++) {
                double pk = (tot > 0.0) ? W[k] / tot : 0.0;
                tv += Math.abs(pk - pp[k]);
                pp[k] = pk;
            }
            tv *= 0.5;
            if (!first && tv > maxTv) maxTv = tv;
            wPre[0] = 0.0;
            for (int k = 0; k < sz; k++) wPre[k + 1] = wPre[k] + W[k];
            wSuf[sz] = 0.0;
            for (int k = sz - 1; k >= 0; k--) wSuf[k] = wSuf[k + 1] + W[k];
            // blocks in increasing order; the intersecting value range moves monotonically
            int[] posi = pos[i];
            int nbi = bHi[i] - bLo[i] + 1;
            int lo = 0, hi = -1;
            for (int j = 0; j < nbi; j++) {
                int e = posi[j];
                if (e < 0) continue;
                int b = bLo[i] + j;
                int B = b * K, w = width[b];
                // values intersecting the block: s + p > B and s < B + w
                while (lo < sz && sRel[lo] + pi <= B) lo++;
                while (hi + 1 < sz && sRel[hi + 1] < B + w) hi++;
                if (hi < lo - 1) hi = lo - 1;
                double den = wPre[lo] + wSuf[hi + 1];
                int[] ids = pidsByW[w];
                for (int id : ids) {
                    sumW[id] = 0.0;
                    sumW1[id] = 0.0;
                }
                for (int k = lo; k <= hi; k++) {
                    int s = sRel[k];
                    int aa = Math.max(s - B, 0), bb = Math.min(s + pi - B, w);
                    int id = pid(aa, bb, K);
                    sumW[id] += W[k];
                    if (zr[k] == 1) sumW1[id] += ai[k] * Math.exp(logQ[k] - M);
                }
                int qe = e * P;
                if (den == 0.0) {
                    for (int k = 0; k < lo && den == 0.0; k++)
                        if (ai[k] > 0.0 && zr[k] == 0) den = Double.MIN_NORMAL;
                    for (int k = hi + 1; k < sz && den == 0.0; k++)
                        if (ai[k] > 0.0 && zr[k] == 0) den = Double.MIN_NORMAL;
                }
                double totq = den;
                boolean inf = false;
                for (int id : ids) {
                    double rv = r[qe + id];
                    double num = (rv > 0.0) ? sumW[id] / rv : sumW1[id];
                    if (num == 0.0) {
                        int allowedZeros = (rv > 0.0) ? 0 : 1;
                        for (int k = lo; k <= hi && num == 0.0; k++) {
                            if (ai[k] <= 0.0 || zr[k] != allowedZeros) continue;
                            int s = sRel[k];
                            int aa = Math.max(s - B, 0), bb = Math.min(s + pi - B, w);
                            if (pid(aa, bb, K) == id) num = Double.MIN_NORMAL;
                        }
                    }
                    if (Double.isInfinite(num)) inf = true;
                    numP[id] = num;
                    totq += num;
                }
                if (inf) {
                    // an infinite pattern mass takes everything; the others keep a floor when alive
                    for (int id : ids) q[qe + id] = Double.isInfinite(numP[id]) ? 1.0 : (numP[id] > 0.0 ? Q_MIN : 0.0);
                    q[qe] = (den > 0.0) ? Q_MIN : 0.0;
                    continue;
                }
                if (totq > 0.0) {
                    double q0 = den / totq;
                    if (den > 0.0 && q0 < Q_MIN) q0 = Q_MIN;
                    q[qe] = q0;
                    for (int id : ids) {
                        double qq = numP[id] / totq;
                        if (numP[id] > 0.0 && qq < Q_MIN) qq = Q_MIN;
                        if (Double.isNaN(qq)) return Double.NaN;
                        q[qe + id] = qq;
                    }
                } else {
                    double u = 1.0 / (ids.length + 1);
                    q[qe] = u;
                    for (int id : ids) q[qe + id] = u;
                }
            }
        }
        return maxTv;
    }

    // ------------------------------------------------------------------
    // factor side
    // ------------------------------------------------------------------

    private boolean factorPass(int[] dem, int C) {
        for (int b = 0; b < nb; b++) {
            if (!blkLive[b]) continue;
            int m = cntB[b];
            int base = off[b];
            int w = width[b];
            int S = pow[w];
            int[] ids = pidsByW[w];
            int[][] md = maxDigByW[w];
            double[] p0 = pre[0];
            java.util.Arrays.fill(p0, 0, S, 0.0);
            p0[0] = 1.0;
            for (int j = 0; j < m; j++) {
                int i = jobs[base + j];
                int d = dem[i];
                int qe = (base + j) * P;
                double q0 = q[qe];
                double[] cur = pre[j];
                double[] nxt = pre[j + 1];
                java.util.Arrays.fill(nxt, 0, S, 0.0);
                for (int st = 0; st < S; st++) {
                    double pv = cur[st];
                    if (pv == 0.0) continue;
                    nxt[st] += pv * q0;
                    for (int id : ids) {
                        double qq = q[qe + id];
                        if (qq == 0.0 || md[id][st] + d > C) continue;
                        nxt[st + d * shift[id]] += pv * qq;
                    }
                }
            }
            double[] bm = bwd[m];
            java.util.Arrays.fill(bm, 0, S, 1.0);
            for (int j = m - 1; j >= 0; j--) {
                int i = jobs[base + j];
                int d = dem[i];
                int qe = (base + j) * P;
                double q0 = q[qe];
                double[] cur = bwd[j + 1];
                double[] nxt = bwd[j];
                for (int st = 0; st < S; st++) {
                    double v = q0 * cur[st];
                    for (int id : ids) {
                        double qq = q[qe + id];
                        if (qq == 0.0 || md[id][st] + d > C) continue;
                        v += qq * cur[st + d * shift[id]];
                    }
                    nxt[st] = v;
                }
            }
            for (int j = 0; j < m; j++) {
                int i = jobs[base + j];
                int d = dem[i];
                int qe = (base + j) * P;
                double[] pj = pre[j];
                double[] bj = bwd[j + 1];
                double den = 0.0;
                for (int id : ids) numP[id] = 0.0;
                for (int st = 0; st < S; st++) {
                    double pc = pj[st];
                    if (pc == 0.0) continue;
                    den += pc * bj[st];
                    for (int id : ids) {
                        if (md[id][st] + d > C) continue;
                        numP[id] += pc * bj[st + d * shift[id]];
                    }
                }
                r[qe] = 1.0;
                for (int id : ids) {
                    double num = numP[id];
                    double ratio;
                    if (den > 0.0) ratio = num / den;
                    else if (num > 0.0) ratio = RATIO_CAP;
                    else ratio = 1.0;
                    if (Double.isNaN(ratio) || Double.isInfinite(ratio)) return false;
                    if (ratio > RATIO_CAP) ratio = RATIO_CAP;
                    else if (ratio > 0.0 && ratio < RATIO_FLOOR) ratio = RATIO_FLOOR;
                    r[qe + id] = ratio;
                }
            }
        }
        return true;
    }
}
