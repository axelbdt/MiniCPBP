package minicpbp.util;

/**
 * Arm 2 of the gcc Phase 1 experiment, upgraded to the intended reference:
 * Lo Bianco, Lorca, Truchet &amp; Pesant, "Revisiting Counting Solutions for
 * the Global Cardinality Constraint", JAIR 66 (2019) 411-441
 * (ref/lobianco_lorca_truchet_pesant_2019_revisiting_counting_gcc_JAIR66.pdf,
 * secured 2026-08-18, user-supplied).
 *
 * What the paper corrects (§3.3): in PQZ 2012's Formula 6, the Upper Bound
 * Residual Graph is balanced by adding FAKE VARIABLES when the duplicated
 * value slots outnumber the remaining variables. Dividing by both the
 * fake-variable symmetry n'! and the duplicated-value symmetry prod_j w_j!
 * double-counts symmetries that offset each other; Formula 6 is NOT an upper
 * bound (counterexample: n=3, exact 8, formula 25/6).
 *
 * The correction (§4.1, Props 4.4-4.7): each instantiation iota corresponds
 * to exactly n'! * prod_j A(w_j, c_j(iota)) perfect matchings of the
 * omega-multiplied value graph, where c_j(iota) is the number of occurrences
 * of value j among the REAL variables and A(w, c) = w!/(w-c)! is the number
 * of arrangements. Hence
 *
 *   |I| <= UB_BM(G(X, w)) / ( n'! * min_iota prod_j A(w_j, c_j(iota)) )
 *
 * and the minimum over the relaxation { 0 <= c_j <= w_j, sum c_j = #real }
 * is reached by greedily saturating the SMALLEST w_j first (Prop 4.6).
 * TRANSCRIPTION NOTE: Algorithm 1 line 11 as printed reads
 * "c*(idx) &lt;- w_sorted(idx) - count"; Prop 4.6 and Example 4.5
 * (w=(2,5), n=4 -&gt; c*=(2,2), min = A(2,2)*A(5,2) = 40) require
 * "c*(idx) &lt;- count". Implemented per Prop 4.6.
 *
 * Everything else is kept IDENTICAL to PqzBound so that the Phase 1
 * comparison between the two closed forms isolates the divisor correction:
 * - Soules U^3 on the belief-weighted matrices stands in for Bregman-Minc
 *   (0/1 bound), as in PqzBound deviation 1.
 * - Weight placement, K-variable max-factor selection, and the treatment of
 *   the "other" class b[i] follow PqzBound deviation 2. In particular, when
 *   remaining variables outnumber residual slots (rows &gt; R), every slot is
 *   saturated, c_j = w_j, A(w_j, w_j) = w_j!, and the corrected divisor
 *   coincides with PQZ's — the correction only bites when rows &lt; R, i.e.
 *   exactly the fake-variable case of §3.3.
 * - The Lower Bound Graph phase is unchanged: there the fake vertices sit on
 *   the same part as the duplicated values, the symmetries do not offset
 *   (paper, end of §3.3), and PQZ's division is correct.
 *
 * Like PqzBound, occurrence-variable beliefs are ignored beyond their
 * [low, up] support — structural limitation of the closed-form column,
 * reported with the results. Messages are leave-one-out evaluations:
 * msg(i, j) = estimate with variable i removed and class j's window
 * decremented; msg(i, other) = estimate with variable i removed.
 */
public final class LoBiancoBound {

    private final SoulesUB3 soules;
    private final int maxDim;
    private final double[][] scratch;

    public LoBiancoBound(int maxDim) {
        this.maxDim = maxDim;
        this.soules = new SoulesUB3(maxDim);
        this.scratch = new double[maxDim][maxDim];
    }

    /**
     * @return true and fills msg[i][j] (j = k is the "other" class) on success;
     * false if the system exceeds maxDim.
     */
    public boolean messages(int n, int k, double[][] a, double[] b, int[] low, int[] up,
                            double[][] msg) {
        if (n + 1 > maxDim) return false;
        int[] lw = new int[k], u2 = new int[k];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < k; j++) {
                if (a[i][j] == 0 || up[j] == 0) {
                    msg[i][j] = 0;
                    continue;
                }
                System.arraycopy(low, 0, lw, 0, k);
                System.arraycopy(up, 0, u2, 0, k);
                lw[j] = Math.max(0, lw[j] - 1);
                u2[j] = up[j] - 1;
                msg[i][j] = estimate(n, k, a, b, lw, u2, i);
            }
            msg[i][k] = (b[i] == 0) ? 0 : estimate(n, k, a, b, low, up, i);
        }
        return true;
    }

    /**
     * Corrected (UB_IP) estimate of the system without variable skip;
     * skip = -1 evaluates the full system (used by the bound-property check).
     */
    public double estimate(int n, int k, double[][] a, double[] b, int[] low, int[] up, int skip) {
        int m = (skip >= 0) ? n - 1 : n; // remaining variables
        int L = 0, R = 0;
        for (int j = 0; j < k; j++) {
            if (low[j] > up[j]) return 0;
            L += low[j];
            R += up[j] - low[j];
        }
        if (L > m) return 0;

        // ---- phase L: lower-bound graph, m x m (unchanged from PQZ: the
        // fake vertices are on the value part, symmetries do not offset) ----
        double bl = 1.0;
        if (m > 0) {
            int dim = m;
            if (dim > maxDim) return 0;
            int r = 0;
            for (int i = 0; i < n; i++) {
                if (i == skip) continue;
                int c = 0;
                for (int j = 0; j < k; j++)
                    for (int t = 0; t < low[j]; t++) scratch[r][c++] = a[i][j];
                for (; c < dim; c++) scratch[r][c] = 1.0;
                r++;
            }
            bl = soules.ub3(scratch, -1, -1, dim, 0);
            for (int j = 0; j < k; j++) bl /= factorial(low[j]);
            bl /= factorial(m - L);
            if (bl == 0) return 0;
        }

        // ---- phase U: residual graph over the K vars with largest residual row sums ----
        int K = m - L;
        double bu = 1.0;
        if (K > 0 && R > 0) {
            double[] key = new double[n];
            Integer[] order = new Integer[n];
            int cnt = 0;
            for (int i = 0; i < n; i++) {
                if (i == skip) continue;
                double s = b[i];
                for (int j = 0; j < k; j++) if (up[j] > low[j]) s += a[i][j];
                key[i] = s;
                order[cnt++] = i;
            }
            java.util.Arrays.sort(order, 0, cnt, (x, y) -> Double.compare(key[y], key[x]));
            int rows = Math.min(K, cnt);
            int fake = Math.max(0, rows - R);
            int dim = Math.max(rows, R + fake);
            if (dim > maxDim) return 0;
            int dummyRows = dim - rows; // n' fake variables of the omega-multiplied graph
            for (int r = 0; r < rows; r++) {
                int i = order[r];
                int c = 0;
                for (int j = 0; j < k; j++)
                    for (int t = 0; t < up[j] - low[j]; t++) scratch[r][c++] = a[i][j];
                for (int t = 0; t < fake; t++) scratch[r][c++] = b[i];
                for (; c < dim; c++) scratch[r][c] = 0; // should not happen
            }
            for (int r = rows; r < dim; r++)
                for (int c = 0; c < dim; c++) scratch[r][c] = 1.0;
            // ub3 divides by dummyRows! internally = the n'! factor of Prop 4.7
            bu = soules.ub3(scratch, -1, -1, dim, dummyRows);
            if (dummyRows > 0) {
                // CORRECTED divisor (Prop 4.6/4.7): min prod_j A(w_j, c_j)
                // over sum c_j = rows, 0 <= c_j <= w_j, by greedy saturation
                // of ascending w. (PQZ divided by prod_j w_j! here — refuted.)
                int[] w = new int[k];
                for (int j = 0; j < k; j++) w[j] = up[j] - low[j];
                java.util.Arrays.sort(w);
                int count = rows;
                for (int j = 0; j < k && count > 0; j++) {
                    int cj = Math.min(count, w[j]);
                    bu /= arrangements(w[j], cj);
                    count -= cj;
                }
            } else {
                // rows >= R: every slot saturated, c_j = w_j, A(w_j, w_j) = w_j!
                // — identical to PQZ's divisor, kept verbatim.
                for (int j = 0; j < k; j++) bu /= factorial(up[j] - low[j]);
                bu /= factorial(fake);
            }
        }
        return bl * bu;
    }

    /** A(w, c) = w! / (w - c)!, the number of arrangements of c among w. */
    private static double arrangements(int w, int c) {
        double f = 1.0;
        for (int t = 0; t < c; t++) f *= (w - t);
        return f;
    }

    private static double factorial(int x) {
        double f = 1.0;
        for (int i = 2; i <= x; i++) f *= i;
        return f;
    }
}
