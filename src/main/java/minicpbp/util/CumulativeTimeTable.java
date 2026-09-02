package minicpbp.util;

/**
 * Mean-field time-table belief for the cumulative constraint
 * (DISJUNCTIVE_CUMULATIVE_PLAN.md §1.2, formulation 4D of the brief).
 *
 * <p>The compulsory parts of the jobs — [max start, min end) whenever that
 * interval is non-empty — are stacked into a height profile h(t). For job i
 * and candidate start v the message is the product over the slots the job
 * would occupy of a saturating function of the residual capacity left by the
 * OTHER jobs, R_{−i}(t) = C − h(t) + (own compulsory part covers t ? dem_i : 0):
 *
 * <pre>
 *   m_i(v) = Π_{t∈[v, v+p_i)} g(R_{−i}(t), dem_i),
 *   g(R, d) = 0 if R &lt; d, else (R − d + 1) / (C − d + 1).
 * </pre>
 *
 * g is 1 where nothing else is committed, 0 where the slot is already full
 * for this job, and decreases linearly in between. Products are taken
 * through prefix sums of log g with zeros counted apart, so each value costs
 * O(1) after an O(lct_i − est_i) pass per job. Ignores the outside beliefs
 * entirely: it is a function of the domains, cheap and monotone in the
 * time-table filtering's own quantities, not a count.
 *
 * <p>Cost: O(T + Σ_i (lct_i − est_i + size_i)).
 */
public final class CumulativeTimeTable {

    private int[] hDiff;
    private int[] h;
    private double[] lp, zc;

    /**
     * @param n    number of jobs
     * @param dom  dom[i][0..size[i]) candidate start values, sorted ascending
     * @param size number of candidate values per job
     * @param p    durations
     * @param dem  demands
     * @param C    capacity
     * @param out  out[i][k] message for value dom[i][k]; inert jobs (p = 0 or dem = 0) get 1,
     *             jobs with dem &gt; C get 0
     */
    public void run(int n, int[][] dom, int[] size, int[] p, int[] dem, int C, double[][] out) {
        int tmin = Integer.MAX_VALUE, tmax = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            if (size[i] == 0 || p[i] == 0 || dem[i] == 0) continue;
            tmin = Math.min(tmin, dom[i][0]);
            tmax = Math.max(tmax, dom[i][size[i] - 1] + p[i]);
        }
        if (tmin == Integer.MAX_VALUE) {
            for (int i = 0; i < n; i++) java.util.Arrays.fill(out[i], 0, size[i], 1.0);
            return;
        }
        int T = tmax - tmin;
        if (h == null || h.length < T + 1) {
            hDiff = new int[T + 2];
            h = new int[T + 1];
        }
        java.util.Arrays.fill(hDiff, 0, T + 1, 0);
        int maxLen = 0;
        for (int i = 0; i < n; i++) {
            if (size[i] == 0 || p[i] == 0 || dem[i] == 0) continue;
            int est = dom[i][0] - tmin, lct = dom[i][size[i] - 1] + p[i] - tmin;
            maxLen = Math.max(maxLen, lct - est);
            int cs = dom[i][size[i] - 1] - tmin;   // compulsory part [max start, min end)
            int ce = dom[i][0] + p[i] - tmin;
            if (cs < ce) {
                hDiff[cs] += dem[i];
                hDiff[ce] -= dem[i];
            }
        }
        int acc = 0;
        for (int t = 0; t < T; t++) {
            acc += hDiff[t];
            h[t] = acc;
        }
        if (lp == null || lp.length < maxLen + 1) {
            lp = new double[maxLen + 1];
            zc = new double[maxLen + 1];
        }
        for (int i = 0; i < n; i++) {
            int sz = size[i];
            if (sz == 0) continue;
            if (p[i] == 0 || dem[i] == 0) {
                java.util.Arrays.fill(out[i], 0, sz, 1.0);
                continue;
            }
            if (dem[i] > C) {
                java.util.Arrays.fill(out[i], 0, sz, 0.0);
                continue;
            }
            int est = dom[i][0] - tmin, lct = dom[i][sz - 1] + p[i] - tmin;
            int cs = dom[i][sz - 1] - tmin, ce = dom[i][0] + p[i] - tmin;
            int d = dem[i];
            double scale = 1.0 / (C - d + 1);
            lp[0] = 0.0;
            zc[0] = 0.0;
            for (int t = est; t < lct; t++) {
                int R = C - h[t] + ((cs < ce && t >= cs && t < ce) ? d : 0);
                int k = t - est;
                if (R < d) {
                    zc[k + 1] = zc[k] + 1.0;
                    lp[k + 1] = lp[k];
                } else {
                    zc[k + 1] = zc[k];
                    lp[k + 1] = lp[k] + Math.log((R - d + 1) * scale);
                }
            }
            for (int k = 0; k < sz; k++) {
                int s = dom[i][k] - tmin - est;
                double zeros = zc[s + p[i]] - zc[s];
                out[i][k] = (zeros > 0.0) ? 0.0 : Math.exp(lp[s + p[i]] - lp[s]);
            }
        }
    }
}
