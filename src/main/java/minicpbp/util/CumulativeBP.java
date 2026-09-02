package minicpbp.util;

/**
 * Nested belief propagation for the cumulative (and, with unit demands and
 * capacity 1, the disjunctive) constraint: DISJUNCTIVE_CUMULATIVE_PLAN.md
 * §1.3, formulation 4A of DISJUNCTIVE_CUMULATIVE.md.
 *
 * <p>Internal factor graph, time-indexed. One categorical variable node x_i
 * per job over its candidate start values (prior = the outside belief
 * a[i][k]); one capacity factor Φ_t per time slot t of the relative axis
 * [0, T) over the jobs A_t that may run at t (est_i ≤ t &lt; lct_i):
 * Φ_t = 1[ Σ_{i∈A_t} dem_i · 1[t − p_i &lt; x_i ≤ t] ≤ C ]. A slot with fewer
 * than two active jobs, or whose active demands sum to at most C, is an
 * identity factor and is not materialised (its messages are 1). The model is
 * in bijection with the feasible schedules; the only approximation is BP
 * loopiness (a job of duration p sits in p consecutive factors).
 *
 * <p>Messages. Job → slot is a Bernoulli q1[t,i] = P(i occupies t) under the
 * cavity distribution of x_i: with r[t,i] the incoming slot ratios, prefix
 * sums of log r (zeros counted apart) along the job's axis give the window
 * mass of every start value in O(1); the values occupying t are a contiguous
 * range of the sorted domain, so the "idle at t" mass is a prefix sum plus a
 * suffix sum — no subtraction — and the "occupies t" mass is the range sum
 * with r[t,i] taken out of the product. Slot → job is the knapsack
 * leave-one-out ratio r[t,i] = m(occupies)/m(idle) from a forward load
 * distribution over the jobs of A_t (overflow beyond C dropped) and a
 * backward acceptance layer, exactly as {@link BinPackingBP} does per bin —
 * prefix and suffix combined, never a factor divided out of a convolution
 * (research_plan.md §7). The emitted message for value v of job i is
 * Π_{t∈[v, v+p_i)} r[t,i], up to the caller's row normalisation.
 *
 * <p>Stop rule as in {@link BinPackingBP}: a hard sweep cap with early exit
 * once the max over jobs of the total-variation change of the normalised
 * solver-facing belief a_k·Π r between two consecutive sweeps is at most eps,
 * after a minimum number of sweeps. Always cold-starts from r = 1.
 *
 * <p>Cost per sweep: O(Σ_t |A_t| (C+1)) for the factors plus
 * O(Σ_i (lct_i − est_i) · p_i) for the jobs. Scratch memory is reused across
 * calls and grows monotonically.
 */
public final class CumulativeBP {

    /** Default minimum number of sweeps before the stability test may fire. */
    public static final int DEFAULT_MIN_SWEEPS = 2;

    public static final int OK = 0;
    /** The per-sweep operation count exceeded the budget; nothing was computed. */
    public static final int DECLINED = 1;
    /** NaN/Inf met inside a sweep; the output is unusable. */
    public static final int NUMERICAL = 2;

    private static final double RATIO_CAP = 1e12;
    private static final double RATIO_FLOOR = 1e-100; // positive ratios below this are raised to it (never to 0)
    private static final double Q_MIN = 1e-15, Q_MAX = 1.0 - 1e-15; // Bernoulli bounds when both sides are structurally alive

    // ----- structure of the current system (rebuilt on every call) -----
    private int T;
    private int[] est, lct;
    private boolean[] inert;
    private int[] cntDiff, demDiff, cnt, sumDem, off, fill;
    private int[] jobs;
    private int[][] pos;
    private int[] sRel; // scratch: relative start of each value of the current job

    // ----- messages -----
    private double[] r;   // slot -> job ratio, per flat (slot, job) index
    private double[] q1;  // job -> slot Bernoulli, per flat index

    // ----- per-job scratch -----
    private double[] lp, zc;        // prefix sums of log r and of zero counts along a job's axis
    private double[] logQ, W, wPre, wSuf;
    private int[] zr;
    private double[][] pPrev;       // previous sweep's normalised solver-facing beliefs

    // ----- knapsack scratch -----
    private double[][] pre, bwd;

    // ----- instrumentation -----
    private long opsPerSweep;
    private int lastSweeps;
    private boolean lastConverged;
    private long nbCalls, nbSweeps, nbConverged;

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

    /**
     * Per-sweep operation count Σ_t |A_t| (C+1) over the non-trivial slots of
     * the system, without allocating anything beyond the slot arrays. Used
     * by the AUTO routine to decide between bp and timetable.
     */
    public long opsPerSweep(int n, int[][] dom, int[] size, int[] p, int[] dem, int C) {
        if (!layoutSlots(n, dom, size, p, dem, C)) return 0;
        return opsPerSweep;
    }

    /**
     * Runs capped-sweep nested BP.
     *
     * @param n         number of jobs
     * @param dom       dom[i][0..size[i]) candidate start values of job i, sorted ascending
     * @param size      number of candidate values per job (≥ 1)
     * @param a         a[i][k] outside-belief weight of value dom[i][k] (≥ 0)
     * @param p         durations (≥ 0; a zero-duration job is inert)
     * @param dem       demands (≥ 0; a zero-demand job is inert; a demand above C zeroes the job)
     * @param C         capacity (≥ 0)
     * @param sweeps    hard sweep cap
     * @param eps       early-exit threshold on the belief change (≤ 0 disables)
     * @param minSweeps minimum sweeps before the stability test may fire
     * @param opsBudget per-sweep operation budget; 0 or less means unlimited
     * @param out       out[i][k] the relative message for value dom[i][k]; inert jobs get 1
     * @return {@link #OK}, {@link #DECLINED} or {@link #NUMERICAL}
     */
    public int run(int n, int[][] dom, int[] size, double[][] a, int[] p, int[] dem, int C,
                   int sweeps, double eps, int minSweeps, long opsBudget, double[][] out) {
        nbCalls++;
        lastSweeps = 0;
        lastConverged = false;
        if (!layoutSlots(n, dom, size, p, dem, C)) { // no interacting job at all
            for (int i = 0; i < n; i++)
                java.util.Arrays.fill(out[i], 0, size[i], (dem[i] > C && p[i] > 0 && dem[i] > 0) ? 0.0 : 1.0);
            return OK;
        }
        if (opsBudget > 0 && opsPerSweep > opsBudget) return DECLINED;
        layoutJobs(n, dom, size, p, C);

        int flat = off[T];
        java.util.Arrays.fill(r, 0, flat, 1.0);
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
        // emit Π_{t in window} r, shifted per job so the largest value is 1
        for (int i = 0; i < n; i++) {
            if (inert[i]) {
                java.util.Arrays.fill(out[i], 0, size[i], (dem[i] > C && p[i] > 0 && dem[i] > 0) ? 0.0 : 1.0);
                continue;
            }
            axisPrefix(i);
            double M = valueMass(i, dom[i], size[i], p[i], null);
            for (int k = 0; k < size[i]; k++) {
                // structurally feasible values keep a positive (if underflown) mass
                double v = (zr[k] > 0) ? 0.0 : Math.max(Math.exp(logQ[k] - M), Double.MIN_NORMAL);
                if (Double.isNaN(v) || Double.isInfinite(v)) return NUMERICAL;
                out[i][k] = v;
            }
        }
        return OK;
    }

    // ------------------------------------------------------------------
    // structure
    // ------------------------------------------------------------------

    /**
     * Computes the relative time axis, the per-slot active counts and demand
     * sums, marks trivial slots, and the CSR offsets. Returns false when no
     * job interacts with any other (every slot trivial), in which case every
     * message is 1.
     */
    private boolean layoutSlots(int n, int[][] dom, int[] size, int[] p, int[] dem, int C) {
        if (est == null || est.length < n) {
            est = new int[n];
            lct = new int[n];
            inert = new boolean[n];
            pos = new int[n][];
            pPrev = new double[n][];
        }
        int tmin = Integer.MAX_VALUE, tmax = Integer.MIN_VALUE;
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
            return false;
        }
        T = tmax - tmin;
        if (cnt == null || cnt.length < T + 1) {
            cntDiff = new int[T + 2];
            demDiff = new int[T + 2];
            cnt = new int[T + 1];
            sumDem = new int[T + 1];
            off = new int[T + 2];
            fill = new int[T + 1];
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
        }
        int c = 0, d = 0;
        boolean any = false;
        off[0] = 0;
        for (int t = 0; t < T; t++) {
            c += cntDiff[t];
            d += demDiff[t];
            boolean nontrivial = c >= 2 && d > C;
            cnt[t] = nontrivial ? c : 0;
            sumDem[t] = d;
            off[t + 1] = off[t] + cnt[t];
            if (nontrivial) {
                any = true;
                opsPerSweep += (long) c * (C + 1);
            }
        }
        return any;
    }

    /** Builds the flattened active-job lists and the per-job position maps; allocates the scratch. */
    private void layoutJobs(int n, int[][] dom, int[] size, int[] p, int C) {
        int flat = off[T];
        if (r == null || r.length < flat) {
            r = new double[flat];
            q1 = new double[flat];
            jobs = new int[flat];
        }
        int maxLen = 0, maxSize = 0, maxCnt = 0;
        for (int t = 0; t < T; t++) maxCnt = Math.max(maxCnt, cnt[t]);
        java.util.Arrays.fill(fill, 0, T, 0);
        for (int i = 0; i < n; i++) {
            if (inert[i]) continue;
            int len = lct[i] - est[i];
            maxLen = Math.max(maxLen, len);
            maxSize = Math.max(maxSize, size[i]);
            if (pos[i] == null || pos[i].length < len) pos[i] = new int[len];
            if (pPrev[i] == null || pPrev[i].length < size[i]) pPrev[i] = new double[size[i]];
            int[] pi = pos[i];
            for (int t = est[i]; t < lct[i]; t++) {
                if (cnt[t] > 0) {
                    int idx = off[t] + fill[t]++;
                    jobs[idx] = i;
                    pi[t - est[i]] = idx;
                } else {
                    pi[t - est[i]] = -1;
                }
            }
        }
        if (lp == null || lp.length < maxLen + 1) {
            lp = new double[maxLen + 1];
            zc = new double[maxLen + 1];
        }
        if (logQ == null || logQ.length < maxSize + 1) {
            logQ = new double[maxSize + 1];
            W = new double[maxSize + 1];
            wPre = new double[maxSize + 2];
            wSuf = new double[maxSize + 2];
            zr = new int[maxSize + 1];
            sRel = new int[maxSize + 1];
        }
        if (pre == null || pre.length < maxCnt + 1 || pre[0].length < C + 1) {
            pre = new double[maxCnt + 1][C + 1];
            bwd = new double[maxCnt + 1][C + 1];
        }
    }

    // ------------------------------------------------------------------
    // job side
    // ------------------------------------------------------------------

    /** Prefix sums of log r (finite part) and of zero counts along job i's axis. */
    private void axisPrefix(int i) {
        int len = lct[i] - est[i];
        int[] pi = pos[i];
        lp[0] = 0.0;
        zc[0] = 0.0;
        for (int t = 0; t < len; t++) {
            int idx = pi[t];
            double rv = (idx < 0) ? 1.0 : r[idx];
            if (rv == 0.0) {
                zc[t + 1] = zc[t] + 1.0;
                lp[t + 1] = lp[t];
            } else {
                zc[t + 1] = zc[t];
                lp[t + 1] = lp[t] + Math.log(rv);
            }
        }
    }

    /**
     * Fills logQ[k], zr[k], sRel[k] for the values of job i from the current
     * axis prefix, and returns the shift M = max finite logQ over values with
     * positive weight (0 when there is none). With a != null, values with
     * zero weight do not count toward M.
     */
    private double valueMass(int i, int[] domi, int sz, int pi, double[] ai) {
        double M = Double.NEGATIVE_INFINITY;
        int base = domi[0];
        for (int k = 0; k < sz; k++) {
            int s = domi[k] - base;
            sRel[k] = s;
            logQ[k] = lp[s + pi] - lp[s];
            zr[k] = (int) (zc[s + pi] - zc[s]);
            if (zr[k] == 0 && (ai == null || ai[k] > 0.0) && logQ[k] > M) M = logQ[k];
        }
        return (M == Double.NEGATIVE_INFINITY) ? 0.0 : M;
    }

    /**
     * One job pass: for every non-inert job, the Bernoulli messages q1 to its
     * non-trivial slots, and the TV change of its solver-facing belief since
     * the previous pass. Returns the max TV over jobs (meaningless on the
     * first pass, when pPrev is seeded), or NaN on a numerical failure.
     */
    private double jobPass(int n, int[][] dom, int[] size, double[][] a, int[] p, boolean first) {
        double maxTv = 0.0;
        for (int i = 0; i < n; i++) {
            if (inert[i]) continue;
            int sz = size[i];
            int pi = p[i];
            double[] ai = a[i];
            axisPrefix(i);
            double M = valueMass(i, dom[i], sz, pi, ai);
            // window masses, shifted
            double tot = 0.0;
            for (int k = 0; k < sz; k++) {
                double w = (zr[k] > 0) ? 0.0 : ai[k] * Math.exp(logQ[k] - M);
                W[k] = w;
                tot += w;
            }
            // stability of the solver-facing belief
            double[] pp = pPrev[i];
            double tv = 0.0;
            for (int k = 0; k < sz; k++) {
                double pk = (tot > 0.0) ? W[k] / tot : 0.0;
                tv += Math.abs(pk - pp[k]);
                pp[k] = pk;
            }
            tv *= 0.5;
            if (!first && tv > maxTv) maxTv = tv;
            // prefix / suffix sums over values
            wPre[0] = 0.0;
            for (int k = 0; k < sz; k++) wPre[k + 1] = wPre[k] + W[k];
            wSuf[sz] = 0.0;
            for (int k = sz - 1; k >= 0; k--) wSuf[k] = wSuf[k + 1] + W[k];
            // two pointers over the axis: values occupying t are sRel in (t - p, t]
            int len = lct[i] - est[i];
            int[] posi = pos[i];
            int lo = 0, hi = -1;
            for (int t = 0; t < len; t++) {
                while (hi + 1 < sz && sRel[hi + 1] <= t) hi++;
                while (lo < sz && sRel[lo] + pi <= t) lo++;
                int idx = posi[t];
                if (idx < 0) continue;
                double den = wPre[lo] + wSuf[hi + 1];
                double rv = r[idx];
                double num = 0.0;
                if (rv > 0.0) {
                    double sum = 0.0;
                    for (int k = lo; k <= hi; k++) sum += W[k];
                    num = sum / rv;
                } else {
                    // r[t,i] = 0: every window through t has W = 0; the
                    // product excluding t is finite iff t was the only zero
                    for (int k = lo; k <= hi; k++)
                        if (zr[k] == 1) num += ai[k] * Math.exp(logQ[k] - M);
                }
                // A zero here must be STRUCTURAL (every value on that side has
                // a = 0 or a zero ratio in its window). W underflows to 0.0
                // for values ~1e-300 below the job's best value; taking that
                // for a hard zero would make q1 exactly 1 and cascade hard
                // zeros through the knapsack factors (seen in the oracle
                // battery: TV 1.0 against the exact marginal).
                if (den == 0.0) {
                    for (int k = 0; k < lo && den == 0.0; k++)
                        if (ai[k] > 0.0 && zr[k] == 0) den = Double.MIN_NORMAL;
                    for (int k = hi + 1; k < sz && den == 0.0; k++)
                        if (ai[k] > 0.0 && zr[k] == 0) den = Double.MIN_NORMAL;
                }
                if (num == 0.0) {
                    int allowedZeros = (rv > 0.0) ? 0 : 1;
                    for (int k = lo; k <= hi && num == 0.0; k++)
                        if (ai[k] > 0.0 && zr[k] == allowedZeros) num = Double.MIN_NORMAL;
                }
                double totq = num + den;
                double q;
                if (Double.isInfinite(num)) q = 1.0;
                else if (totq > 0.0) q = num / totq;
                else q = 0.5;
                if (Double.isNaN(q)) return Double.NaN;
                // and the Bernoulli itself must not round to a hard 0/1 when
                // the other side is structurally alive (1e-308 / 1 rounds to 1)
                if (den > 0.0 && q > Q_MAX) q = Q_MAX;
                if (num > 0.0 && q < Q_MIN) q = Q_MIN;
                q1[idx] = q;
            }
        }
        return maxTv;
    }

    // ------------------------------------------------------------------
    // factor side
    // ------------------------------------------------------------------

    /** One factor pass over every non-trivial slot: knapsack leave-one-out ratios into r. */
    private boolean factorPass(int[] dem, int C) {
        for (int t = 0; t < T; t++) {
            int m = cnt[t];
            if (m == 0) continue;
            int base = off[t];
            double[] p0 = pre[0];
            java.util.Arrays.fill(p0, 0, C + 1, 0.0);
            p0[0] = 1.0;
            for (int j = 0; j < m; j++) {
                int i = jobs[base + j];
                int d = dem[i];
                double q = q1[base + j];
                double q0 = 1.0 - q;
                double[] cur = pre[j];
                double[] nxt = pre[j + 1];
                for (int c = 0; c <= C; c++) {
                    double v = cur[c] * q0;
                    if (c >= d) v += cur[c - d] * q;
                    nxt[c] = v;
                }
            }
            double[] bm = bwd[m];
            java.util.Arrays.fill(bm, 0, C + 1, 1.0);
            for (int j = m - 1; j >= 0; j--) {
                int i = jobs[base + j];
                int d = dem[i];
                double q = q1[base + j];
                double q0 = 1.0 - q;
                double[] cur = bwd[j + 1];
                double[] nxt = bwd[j];
                for (int c = 0; c <= C; c++) {
                    double v = q0 * cur[c];
                    if (c + d <= C) v += q * cur[c + d];
                    nxt[c] = v;
                }
            }
            for (int j = 0; j < m; j++) {
                int i = jobs[base + j];
                int d = dem[i];
                double[] pj = pre[j];
                double[] bj = bwd[j + 1];
                double num = 0.0, den = 0.0;
                for (int c = 0; c <= C; c++) {
                    double pc = pj[c];
                    if (pc == 0.0) continue;
                    den += pc * bj[c];
                    if (c + d <= C) num += pc * bj[c + d];
                }
                double ratio;
                if (den > 0.0) ratio = num / den;
                else if (num > 0.0) ratio = RATIO_CAP;
                else ratio = 1.0; // no feasible completion either way: uninformative
                if (Double.isNaN(ratio) || Double.isInfinite(ratio)) return false;
                if (ratio > RATIO_CAP) ratio = RATIO_CAP;
                else if (ratio > 0.0 && ratio < RATIO_FLOOR) ratio = RATIO_FLOOR;
                r[base + j] = ratio;
            }
        }
        return true;
    }
}
