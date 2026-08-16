/*
 * mini-cpbp, replacing classic propagation by belief propagation
 *
 * Exact permanent routines used by AllDifferentDC.
 *
 * Added 2026-08-16 for the "BP marginals vs. Soules U^3" experiment
 * (see IMPLEMENTATION_LOG.md, 2026-08-16, phase 0).
 *
 * Problem solved here
 * -------------------
 * AllDifferentDC.updateBelief() builds an outside-belief matrix A with
 *   m = nbVar real rows (the free variables) and
 *   n = nbVal columns (the free values), m <= n,
 * then pads it to n x n with d = n - m "dummy" rows whose every entry equals
 * some constant v (v = 1/nbVal in updateBelief, v = 1 in weightedCounting).
 *
 * The quantity handed to setLocalBelief(i, val, .) is
 *     perm(P^{ij})
 * i.e. the permanent of the padded matrix P with row i and column j deleted.
 * Writing
 *     M_ij = sum over injections phi : rows\{i} -> cols\{j} of prod A[r][phi(r)]
 * one has, because the d dummy rows are interchangeable and constant,
 *     perm(P^{ij}) = d! * v^d * M_ij                                     (*)
 * and likewise perm(P) = d! * v^d * (sum over injections of all m rows).
 *
 * Two exact all-minors routines are provided, both sharing work across the
 * m*n minors instead of recomputing each from scratch:
 *
 *  - ryserAllMinors : Ryser's inclusion-exclusion formula with one shared
 *    Gray-code enumeration of the 2^n column subsets. O(2^n * m * n) time,
 *    O(n) memory. Signed sums, so it loses relative precision when the
 *    permanent is small compared with the individual terms.
 *
 *  - dpAllMinors : forward/backward dynamic programming over column subsets.
 *    O(2^n * n) time, O(2^n) memory, and strictly nonnegative additions, so
 *    it is numerically exact up to accumulated rounding and returns exact
 *    zeros on entries with no support.
 *
 * dpAllMinors is asymptotically a factor ~m/3 faster than ryserAllMinors and
 * strictly more accurate; ryserAllMinors is kept because it cross-validates the DP and because the
 * calibration of the exact threshold is reported for both.
 */

package minicpbp.util;

public final class Permanent {

    private Permanent() {
    }

    /* ------------------------------------------------------------------ */
    /* reference implementations                                           */
    /* ------------------------------------------------------------------ */

    /**
     * Permanent of the leading n x n block of A by Heap's algorithm, O(n!).
     * Identical in spirit to AllDifferentDC.permanent(); duplicated here so
     * that the oracle does not depend on the constraint's mutable scratch
     * arrays.
     */
    public static double heap(double[][] A, int n) {
        if (n == 0) return 1.0;
        int[] c = new int[n];
        int[] permutation = new int[n];
        double prod = 1.0;
        for (int i = 0; i < n; i++) {
            c[i] = 0;
            permutation[i] = i;
            prod *= A[i][i];
        }
        double perm = prod;
        int i = 0;
        while (i < n) {
            if (c[i] < i) {
                if (i % 2 == 0) prod = heapSwap(A, permutation, 0, i, n, prod);
                else prod = heapSwap(A, permutation, c[i], i, n, prod);
                perm += prod;
                c[i]++;
                i = 0;
            } else {
                c[i] = 0;
                i++;
            }
        }
        return perm;
    }

    private static double heapSwap(double[][] A, int[] permutation, int i, int j, int n, double prod) {
        int e = permutation[i];
        permutation[i] = permutation[j];
        permutation[j] = e;
        double newFactor = A[i][permutation[i]] * A[j][permutation[j]];
        double oldFactor = A[i][permutation[j]] * A[j][permutation[i]];
        if (newFactor == 0) return 0;
        else if (oldFactor == 0) {
            double newProd = 1.0;
            for (int k = 0; k < n; k++) newProd *= A[k][permutation[k]];
            return newProd;
        } else return prod * newFactor / oldFactor;
    }

    /**
     * Permanent of the leading n x n block of A by Ryser's formula in Gray
     * code order, O(2^n * n).
     */
    public static double ryser(double[][] A, int n) {
        if (n == 0) return 1.0;
        double[] rs = new double[n];
        double total = 0.0;
        int prevGray = 0;
        int size = 0;
        boolean[] inS = new boolean[n];
        long nbSubsets = 1L << n;
        for (long k = 0; k < nbSubsets; k++) {
            int gray = (int) (k ^ (k >>> 1));
            if (k > 0) {
                int idx = Integer.numberOfTrailingZeros(gray ^ prevGray);
                if (inS[idx]) {
                    inS[idx] = false;
                    size--;
                    for (int i = 0; i < n; i++) rs[i] -= A[i][idx];
                } else {
                    inS[idx] = true;
                    size++;
                    for (int i = 0; i < n; i++) rs[i] += A[i][idx];
                }
                prevGray = gray;
            }
            if (size == 0) continue; // product is 0 for n >= 1
            double prod = 1.0;
            for (int i = 0; i < n; i++) prod *= rs[i];
            if (((n - size) & 1) == 0) total += prod;
            else total -= prod;
        }
        return total;
    }

    /* ------------------------------------------------------------------ */
    /* all-minors, Ryser with shared subset enumeration                     */
    /* ------------------------------------------------------------------ */

    /**
     * Computes out[i][j] = perm(P^{ij}) for every real row i in [0,m) and
     * every column j in [0,n), where P is A padded with d = n-m dummy rows of
     * constant value dummyVal.
     * <p>
     * One Gray-code sweep over the 2^n column subsets is shared by all m*n
     * minors; per subset the leave-one-row-out products are obtained from
     * prefix/suffix products, so the cost is O(2^n * m * n) rather than the
     * O(2^n * m * n * m) of m*n independent Ryser calls.
     *
     * @param A        m x n nonnegative matrix (larger arrays are fine, only
     *                 the leading m x n block is read)
     * @param m        number of real rows
     * @param n        number of columns (matrix dimension after padding)
     * @param dummyVal value filling the d = n-m dummy rows
     * @param out      m x n output, overwritten
     */
    public static void ryserAllMinors(double[][] A, int m, int n, double dummyVal, double[][] out) {
        int d = n - m;
        double[] rs = new double[m];
        double[] pre = new double[m + 1];
        double[] suf = new double[m + 1];
        boolean[] inS = new boolean[n];
        double[] dummyPow = new double[n + 1];
        for (int s = 0; s <= n; s++) dummyPow[s] = (d == 0) ? 1.0 : Math.pow(dummyVal * s, d);

        for (int i = 0; i < m; i++)
            for (int j = 0; j < n; j++) out[i][j] = 0.0;

        int prevGray = 0;
        int size = 0;
        // global sign of Ryser for a matrix of dimension n-1
        int baseSign = (((n - 1) & 1) == 0) ? 1 : -1;
        long nbSubsets = 1L << n;
        for (long k = 0; k < nbSubsets; k++) {
            int gray = (int) (k ^ (k >>> 1));
            if (k > 0) {
                int idx = Integer.numberOfTrailingZeros(gray ^ prevGray);
                if (inS[idx]) {
                    inS[idx] = false;
                    size--;
                    for (int i = 0; i < m; i++) rs[i] -= A[i][idx];
                } else {
                    inS[idx] = true;
                    size++;
                    for (int i = 0; i < m; i++) rs[i] += A[i][idx];
                }
                prevGray = gray;
            }
            if (size == n) continue; // no column left out, contributes to no minor
            double w = dummyPow[size];
            if (w == 0.0) continue;
            w *= baseSign * (((size & 1) == 0) ? 1 : -1);
            pre[0] = 1.0;
            for (int i = 0; i < m; i++) pre[i + 1] = pre[i] * rs[i];
            suf[m] = 1.0;
            for (int i = m - 1; i >= 0; i--) suf[i] = suf[i + 1] * rs[i];
            for (int i = 0; i < m; i++) {
                double q = pre[i] * suf[i + 1] * w;
                if (q == 0.0) continue;
                double[] outi = out[i];
                for (int j = 0; j < n; j++)
                    if (!inS[j]) outi[j] += q;
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* all-minors, forward/backward subset DP                               */
    /* ------------------------------------------------------------------ */

    /**
     * Reusable workspace for {@link #dpAllMinors}. Allocating the two
     * 2^maxN-sized arrays once keeps the routine allocation-free in the
     * propagator's hot loop.
     */
    public static final class DpWorkspace {
        final double[] fwd;
        final double[] bwd;
        final int maxN;

        public DpWorkspace(int maxN) {
            this.maxN = maxN;
            this.fwd = new double[1 << maxN];
            this.bwd = new double[1 << maxN];
        }

        public int maxN() {
            return maxN;
        }
    }

    /**
     * Same contract as {@link #ryserAllMinors} but computed by a
     * forward/backward dynamic program over column subsets.
     * <p>
     * fwd[S] = weighted number of injections of rows 0..|S|-1 onto exactly S.
     * bwd[T] = weighted number of injections of rows |T|..m-1 into the
     * complement of T.
     * Then M_ij = sum over S with |S| = i and j not in S of fwd[S]*bwd[S+{j}],
     * and out[i][j] = d! * dummyVal^d * M_ij by (*).
     * <p>
     * All additions are of nonnegative terms, so there is no cancellation and
     * out[i][j] is exactly 0 iff no supporting injection exists.
     */
    public static void dpAllMinors(double[][] A, int m, int n, double dummyVal, double[][] out, DpWorkspace ws) {
        int d = n - m;
        double scale = 1.0;
        for (int i = 2; i <= d; i++) scale *= i;
        if (d > 0) scale *= Math.pow(dummyVal, d);

        final double[] fwd = ws.fwd;
        final double[] bwd = ws.bwd;
        final int full = (1 << n) - 1;

        // forward
        java.util.Arrays.fill(fwd, 0, full + 1, 0.0);
        fwd[0] = 1.0;
        for (int S = 1; S <= full; S++) {
            int pc = Integer.bitCount(S);
            if (pc > m) continue;
            double[] row = A[pc - 1];
            double acc = 0.0;
            int rest = S;
            while (rest != 0) {
                int bit = rest & (-rest);
                int j = Integer.numberOfTrailingZeros(bit);
                double a = row[j];
                if (a != 0.0) acc += a * fwd[S ^ bit];
                rest ^= bit;
            }
            fwd[S] = acc;
        }

        // backward
        java.util.Arrays.fill(bwd, 0, full + 1, 0.0);
        for (int T = full; T >= 0; T--) {
            int pc = Integer.bitCount(T);
            if (pc > m) continue;
            if (pc == m) {
                bwd[T] = 1.0;
                continue;
            }
            double[] row = A[pc];
            double acc = 0.0;
            int rest = (~T) & full;
            while (rest != 0) {
                int bit = rest & (-rest);
                int j = Integer.numberOfTrailingZeros(bit);
                double a = row[j];
                if (a != 0.0) acc += a * bwd[T | bit];
                rest ^= bit;
            }
            bwd[T] = acc;
        }

        for (int i = 0; i < m; i++)
            for (int j = 0; j < n; j++) out[i][j] = 0.0;

        for (int S = 0; S <= full; S++) {
            int pc = Integer.bitCount(S);
            if (pc >= m) continue;
            double f = fwd[S];
            if (f == 0.0) continue;
            double[] outi = out[pc];
            int rest = (~S) & full;
            while (rest != 0) {
                int bit = rest & (-rest);
                int j = Integer.numberOfTrailingZeros(bit);
                outi[j] += f * bwd[S | bit];
                rest ^= bit;
            }
        }

        if (scale != 1.0)
            for (int i = 0; i < m; i++)
                for (int j = 0; j < n; j++) out[i][j] *= scale;
    }

    /**
     * perm(P) for the padded matrix, by the same subset DP.
     */
    public static double dpPermanent(double[][] A, int m, int n, double dummyVal, DpWorkspace ws) {
        int d = n - m;
        double scale = 1.0;
        for (int i = 2; i <= d; i++) scale *= i;
        if (d > 0) scale *= Math.pow(dummyVal, d);

        final double[] fwd = ws.fwd;
        final int full = (1 << n) - 1;
        java.util.Arrays.fill(fwd, 0, full + 1, 0.0);
        fwd[0] = 1.0;
        double total = 0.0;
        for (int S = 1; S <= full; S++) {
            int pc = Integer.bitCount(S);
            if (pc > m) continue;
            double[] row = A[pc - 1];
            double acc = 0.0;
            int rest = S;
            while (rest != 0) {
                int bit = rest & (-rest);
                int j = Integer.numberOfTrailingZeros(bit);
                double a = row[j];
                if (a != 0.0) acc += a * fwd[S ^ bit];
                rest ^= bit;
            }
            fwd[S] = acc;
            if (pc == m) total += acc;
        }
        if (m == 0) total = 1.0;
        return total * scale;
    }

    /* ------------------------------------------------------------------ */
    /* brute force, for validation only                                     */
    /* ------------------------------------------------------------------ */

    /**
     * out[i][j] = perm(P^{ij}) by explicit enumeration of all injections.
     * Only usable for very small m, n; exists to validate the two fast paths.
     */
    public static void bruteForceAllMinors(double[][] A, int m, int n, double dummyVal, double[][] out) {
        int d = n - m;
        double scale = 1.0;
        for (int i = 2; i <= d; i++) scale *= i;
        if (d > 0) scale *= Math.pow(dummyVal, d);
        for (int i = 0; i < m; i++)
            for (int j = 0; j < n; j++) out[i][j] = 0.0;
        for (int i = 0; i < m; i++)
            for (int j = 0; j < n; j++)
                out[i][j] = scale * enumerate(A, m, n, i, j, 0, 0, 1.0);
    }

    private static double enumerate(double[][] A, int m, int n, int skipRow, int usedCol,
                                    int row, int used, double prod) {
        if (row == m) return prod;
        if (row == skipRow) return enumerate(A, m, n, skipRow, usedCol, row + 1, used, prod);
        double acc = 0.0;
        for (int j = 0; j < n; j++) {
            if (j == usedCol) continue;
            if ((used & (1 << j)) != 0) continue;
            double a = A[row][j];
            if (a == 0.0) continue;
            acc += enumerate(A, m, n, skipRow, usedCol, row + 1, used | (1 << j), prod * a);
        }
        return acc;
    }
}
