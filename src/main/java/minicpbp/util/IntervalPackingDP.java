/*
 * mini-cpbp, replacing classic propagation by belief propagation
 *
 * Candidate-interval counting for Disjunctive (MDD_COUNTING_PLAN.md §1.1,
 * brief §2.4): weighted interval scheduling, forward and backward.
 */

package minicpbp.util;

/**
 * Exact resource factor for Cap = 1, unit demands: weighted independent-set
 * counting on the interval graph of the live candidates, in O(M log M) per
 * update (the log is the binary search for the predecessor; the two scans are
 * linear). Independent of the horizon and of the durations.
 *
 * <p>With candidates sorted by end time (F) and by start time (G):
 *
 * <pre>
 *   F(k) = F(k−1) + lambda_k F(p(k))     p(k): number of candidates ending ≤ start_k
 *   G(j) = G(j+1) + lambda_j G(q(j))     q(j): first position whose start ≥ end_j
 *   L_a = F(p(a)),  U_a = G(q(a)),  Z = F(M) = G(0)
 *   m(1) = L_a U_a,   m(0) = Z − lambda_a L_a U_a,   r_a = m(1) / m(0)
 * </pre>
 *
 * m(1) is exact because selecting a forbids exactly the candidates that
 * neither end before a starts nor start after a ends, and the two compatible
 * sides are independent. Everything is in the log domain (Z is a sum of
 * products of up to n odds, each up to 1e12). m(0) is a difference; when the
 * relative difference is below {@code CANCEL_REL} the leave-one-out Z is
 * recomputed by a forward scan that skips a (counted). m(0) = 0 with m(1) > 0
 * is a forced candidate: r = MU_MAX.
 *
 * <p>Fixed jobs are not in the factor: the table has already killed every
 * candidate overlapping a committed interval.
 */
public final class IntervalPackingDP implements PackingFactor {

    private int[] byEnd = new int[0], byStart = new int[0];
    private int[] endSorted = new int[0], startSorted = new int[0];
    private int[] pred = new int[0], succ = new int[0];
    private int[] posByStart = new int[0]; // position of candidate a in the by-start order
    private double[] logLam = new double[0], logF = new double[0], logG = new double[0];
    private long nbCancelRecomputes, nbForced;
    /** log(1 − 1e-4): own shares above it take the exact leave-one-out scan */
    private static final double SHARE_LOG_LIMIT = Math.log1p(-1e-4);

    @Override
    public String name() {
        return "interval";
    }

    private static double logAddExp(double x, double y) {
        if (x == Double.NEGATIVE_INFINITY) return y;
        if (y == Double.NEGATIVE_INFINITY) return x;
        if (x < y) {
            double s = x;
            x = y;
            y = s;
        }
        return x + Math.log1p(Math.exp(y - x));
    }

    @Override
    public boolean update(CandidateTable t, double[] lambda, double[] r) {
        int M = t.M;
        int m = 0;
        if (byEnd.length < M) {
            byEnd = new int[M];
            byStart = new int[M];
            endSorted = new int[M];
            startSorted = new int[M];
            pred = new int[M];
            succ = new int[M + 1];
            posByStart = new int[M];
            logLam = new double[M];
            logF = new double[M + 1];
            logG = new double[M + 1];
        }
        boolean[] live = t.live;
        for (int a = 0; a < M; a++) if (live[a]) byEnd[m++] = a;
        if (m == 0) return true;
        // sort by end (ties by start): ids are already in increasing start within a job, but
        // jobs interleave, so a full sort is needed. Insertion order of ids is a useful
        // near-sorted start order; use a stable sort on a boxed-free path: simple
        // comparison sort via index arrays.
        sort(byEnd, m, t.end, t.start);
        System.arraycopy(byEnd, 0, byStart, 0, m);
        sort(byStart, m, t.start, t.end);
        for (int k = 0; k < m; k++) {
            endSorted[k] = t.end[byEnd[k]];
            startSorted[k] = t.start[byStart[k]];
            posByStart[byStart[k]] = k;
        }
        for (int k = 0; k < m; k++) {
            int a = byEnd[k];
            double l = lambda[a];
            logLam[a] = (l > 0.0) ? Math.log(l) : Double.NEGATIVE_INFINITY;
            // p(k): number of candidates (by end) with end ≤ start_a
            pred[k] = upperBound(endSorted, m, t.start[a]);
        }
        for (int j = 0; j < m; j++) {
            int a = byStart[j];
            // q(j): first position (by start) with start ≥ end_a
            succ[j] = lowerBound(startSorted, m, t.end[a]);
        }
        // forward over the end order
        logF[0] = 0.0;
        for (int k = 0; k < m; k++) {
            int a = byEnd[k];
            logF[k + 1] = logAddExp(logF[k], logLam[a] + logF[pred[k]]);
        }
        // backward over the start order
        logG[m] = 0.0;
        for (int j = m - 1; j >= 0; j--) {
            int a = byStart[j];
            logG[j] = logAddExp(logG[j + 1], logLam[a] + logG[succ[j]]);
        }
        double logZ = logF[m];
        // messages
        for (int k = 0; k < m; k++) {
            int a = byEnd[k];
            double logM1 = logF[pred[k]] + logG[succ[posByStart[a]]];
            double x = logLam[a] + logM1 - logZ; // log of the own share of Z, ≤ 0 up to rounding
            double logM0;
            // The difference Z − λ L U loses one digit per digit of the own share's
            // closeness to 1: logZ carries an absolute error of order M·1e-16, so
            // 1 − e^x is reliable to ~1e-9 relative only while 1 − e^x ≥ 1e-4. Above
            // that share (a nearly forced candidate) Z without a is recomputed by
            // a scan that skips a — O(M), rare outside the clamp regime, counted.
            if (x < SHARE_LOG_LIMIT) {
                logM0 = logZ + Math.log1p(-Math.exp(x));
            } else {
                nbCancelRecomputes++;
                logM0 = logZWithout(k, m);
            }
            double rv;
            if (logM0 == Double.NEGATIVE_INFINITY) {
                rv = ExactlyOneRows.MU_MAX; // forced
                nbForced++;
            } else {
                rv = Math.exp(logM1 - logM0);
                if (Double.isNaN(rv)) return false;
                if (rv > ExactlyOneRows.MU_MAX) rv = ExactlyOneRows.MU_MAX;
                else if (rv > 0.0 && rv < Double.MIN_NORMAL) rv = Double.MIN_NORMAL;
            }
            r[a] = rv;
        }
        return true;
    }

    /** log Z over the live candidates except the one at end-position skip */
    private double[] fSkip = new double[0];

    private double logZWithout(int skip, int m) {
        // recompute F with candidate skip absent: F'(k) = F'(k-1) + lambda_k F'(p(k)) where
        // p(k) still indexes the same prefix (the skipped candidate contributes nothing)
        if (fSkip.length < m + 1) fSkip = new double[m + 1];
        double[] f = fSkip;
        f[0] = 0.0;
        for (int k = 0; k < m; k++) {
            if (k == skip) {
                f[k + 1] = f[k];
                continue;
            }
            int a = byEnd[k];
            f[k + 1] = logAddExp(f[k], logLam[a] + f[pred[k]]);
        }
        return f[m];
    }

    private static int upperBound(int[] arr, int m, int key) { // number of entries ≤ key
        int lo = 0, hi = m;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (arr[mid] <= key) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    private static int lowerBound(int[] arr, int m, int key) { // first index with entry ≥ key
        int lo = 0, hi = m;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (arr[mid] < key) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    /** in-place sort of idx[0..m) by (key1, key2); insertion sort on near-sorted input, else a merge sort */
    private int[] tmpSort = new int[0];

    private void sort(int[] idx, int m, int[] key1, int[] key2) {
        if (m < 32) {
            for (int i = 1; i < m; i++) {
                int v = idx[i];
                int j = i - 1;
                while (j >= 0 && less(v, idx[j], key1, key2)) {
                    idx[j + 1] = idx[j];
                    j--;
                }
                idx[j + 1] = v;
            }
            return;
        }
        if (tmpSort.length < m) tmpSort = new int[m];
        mergeSort(idx, tmpSort, 0, m, key1, key2);
    }

    private static boolean less(int a, int b, int[] key1, int[] key2) {
        if (key1[a] != key1[b]) return key1[a] < key1[b];
        if (key2[a] != key2[b]) return key2[a] < key2[b];
        return a < b;
    }

    private void mergeSort(int[] idx, int[] tmp, int lo, int hi, int[] key1, int[] key2) {
        if (hi - lo <= 16) {
            for (int i = lo + 1; i < hi; i++) {
                int v = idx[i];
                int j = i - 1;
                while (j >= lo && less(v, idx[j], key1, key2)) {
                    idx[j + 1] = idx[j];
                    j--;
                }
                idx[j + 1] = v;
            }
            return;
        }
        int mid = (lo + hi) >>> 1;
        mergeSort(idx, tmp, lo, mid, key1, key2);
        mergeSort(idx, tmp, mid, hi, key1, key2);
        int i = lo, j = mid, k = lo;
        while (i < mid && j < hi) tmp[k++] = less(idx[j], idx[i], key1, key2) ? idx[j++] : idx[i++];
        while (i < mid) tmp[k++] = idx[i++];
        while (j < hi) tmp[k++] = idx[j++];
        System.arraycopy(tmp, lo, idx, lo, hi - lo);
    }

    public long nbCancelRecomputes() {
        return nbCancelRecomputes;
    }

    public long nbForced() {
        return nbForced;
    }
}
