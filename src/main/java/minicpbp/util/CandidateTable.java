/*
 * mini-cpbp, replacing classic propagation by belief propagation
 *
 * Candidate-interval counting for Cumulative / Disjunctive
 * (MDD_COUNTING_PLAN.md §1.1, brief §4 "candidate indexing").
 */

package minicpbp.util;

/**
 * Global candidate indexing for one Cumulative (or Disjunctive) constraint.
 *
 * <p>A candidate a is one (job i, start value s) pair of the domains seen when
 * the table was built; its id is stable for the life of the table, which is
 * what lets {@link ExactlyOneRows} warm-start its messages by id across search
 * nodes (brief §8: "preallocate candidate arrays sized by initial domains and
 * mask removed candidates"). {@link #refresh} masks the candidates no longer
 * in the domains, takes the outside beliefs as weights, and derives:
 *
 * <ul>
 * <li>{@code fixed[i]}: the job has one alive candidate (bound variable). Its
 * interval is committed: it leaves the factor graph and enters the static
 * load profile {@code fixedLoad[t]} that every factor treats as pre-consumed
 * capacity;</li>
 * <li>{@code live[a]}: alive, of a non-fixed, non-inert job, and not in
 * conflict with the fixed profile (its own demand fits under Cap − fixedLoad on
 * its whole extent). A candidate that is alive but not live has message 0 —
 * it is infeasible whatever the other jobs do;</li>
 * <li>{@code inert[i]}: duration 0 or demand 0 — always feasible, message 1,
 * absent from the factor.</li>
 * </ul>
 *
 * Time is kept absolute in {@code start/end} and relative to {@code tmin} in
 * the fixed profile (index t − tmin).
 */
public final class CandidateTable {

    public int n;               // jobs
    public int M;               // candidates
    public int cap;
    public int[] p, d;          // per job
    public int[] job, start, end, dem;   // per candidate
    public int[] jobBegin, jobEnd;       // candidates of job i are [jobBegin[i], jobEnd[i]), sorted by start
    public boolean[] alive;     // in the current domain
    public boolean[] live;      // alive, non-fixed, non-inert, compatible with the fixed profile
    public int[] slot;          // position k in the caller's sorted domain array, for alive candidates
    public double[] weight;     // outside belief, alive candidates
    public boolean[] fixed;     // job bound
    public boolean[] inert;     // job with p = 0 or d = 0
    public int nLive;           // live candidates in the last refresh
    public int nLiveJobs;       // non-fixed, non-inert jobs with at least one live candidate
    public int tmin, tmax;      // horizon covered by every candidate ever seen: [tmin, tmax)
    public int[] fixedLoad;     // committed load of fixed jobs, index t − tmin
    public int pMax;            // largest duration over non-inert jobs

    /**
     * whether a job with a single alive candidate is committed to it (fixed:
     * its interval enters the fixed profile and it leaves the factor). True
     * for scheduling; false for the AllDifferent path, where AssignmentBP's
     * semantics keep such a row in the factor (its lambda saturates at MU_MAX).
     */
    public boolean fixSingletons = true;

    private int[] base;         // smallest start value per job at build time
    private int[][] idOf;       // idOf[i][v − base[i]] = candidate id, or −1
    private long liveSignature; // changes whenever the live set changes

    /**
     * Builds the table from the current sorted domains.
     *
     * @param dom  dom[i][0..size[i]) sorted ascending
     * @param size domain sizes
     */
    public CandidateTable(int n, int[][] dom, int[] size, int[] p, int[] d, int cap) {
        this.n = n;
        this.p = p;
        this.d = d;
        this.cap = cap;
        jobBegin = new int[n];
        jobEnd = new int[n];
        base = new int[n];
        idOf = new int[n][];
        fixed = new boolean[n];
        inert = new boolean[n];
        int m = 0;
        tmin = Integer.MAX_VALUE;
        tmax = Integer.MIN_VALUE;
        pMax = 0;
        for (int i = 0; i < n; i++) {
            inert[i] = p[i] <= 0 || d[i] <= 0;
            jobBegin[i] = m;
            m += size[i];
            jobEnd[i] = m;
            if (size[i] > 0) {
                tmin = Math.min(tmin, dom[i][0]);
                tmax = Math.max(tmax, dom[i][size[i] - 1] + Math.max(p[i], 0));
            }
            if (!inert[i]) pMax = Math.max(pMax, p[i]);
        }
        if (tmin == Integer.MAX_VALUE) {
            tmin = 0;
            tmax = 0;
        }
        M = m;
        job = new int[M];
        start = new int[M];
        end = new int[M];
        dem = new int[M];
        alive = new boolean[M];
        live = new boolean[M];
        slot = new int[M];
        weight = new double[M];
        fixedLoad = new int[Math.max(1, tmax - tmin)];
        for (int i = 0; i < n; i++) {
            if (size[i] == 0) {
                idOf[i] = new int[0];
                continue;
            }
            base[i] = dom[i][0];
            int span = dom[i][size[i] - 1] - base[i] + 1;
            idOf[i] = new int[span];
            java.util.Arrays.fill(idOf[i], -1);
            for (int k = 0; k < size[i]; k++) {
                int a = jobBegin[i] + k;
                idOf[i][dom[i][k] - base[i]] = a;
                job[a] = i;
                start[a] = dom[i][k];
                end[a] = dom[i][k] + p[i];
                dem[a] = d[i];
            }
        }
    }

    /**
     * Masks the table to the current domains and weights.
     *
     * @param dom  current sorted domains
     * @param size current sizes
     * @param a    a[i][k] weight of dom[i][k] (standard representation, ≥ 0)
     * @return false if some current value was not in the table (the caller
     * must rebuild the table); the table is then in an unspecified state
     */
    public boolean refresh(int[][] dom, int[] size, double[][] a) {
        java.util.Arrays.fill(alive, false);
        java.util.Arrays.fill(live, false);
        java.util.Arrays.fill(fixedLoad, 0);
        long sig = 1469598103934665603L;
        nLive = 0;
        nLiveJobs = 0;
        for (int i = 0; i < n; i++) {
            fixed[i] = fixSingletons && size[i] == 1;
            int[] ids = idOf[i];
            for (int k = 0; k < size[i]; k++) {
                int off = dom[i][k] - base[i];
                if (off < 0 || off >= ids.length || ids[off] < 0) return false;
                int c = ids[off];
                alive[c] = true;
                slot[c] = k;
                weight[c] = a[i][k];
            }
            if (fixed[i] && !inert[i]) {
                int c = ids[dom[i][0] - base[i]];
                for (int t = start[c]; t < end[c]; t++) fixedLoad[t - tmin] += d[i];
            }
        }
        for (int i = 0; i < n; i++) {
            if (fixed[i] || inert[i]) continue;
            boolean any = false;
            for (int c = jobBegin[i]; c < jobEnd[i]; c++) {
                if (!alive[c]) continue;
                boolean ok = true;
                int dd = dem[c];
                for (int t = start[c]; t < end[c]; t++) {
                    if (fixedLoad[t - tmin] + dd > cap) {
                        ok = false;
                        break;
                    }
                }
                if (ok) {
                    live[c] = true;
                    nLive++;
                    any = true;
                    sig = sig * 1099511628211L + c;
                }
            }
            if (any) nLiveJobs++;
        }
        liveSignature = sig;
        return true;
    }

    /** a hash of the live set of the last refresh; equal signatures mean (with overwhelming probability) equal live sets */
    public long liveSignature() {
        return liveSignature;
    }
}
