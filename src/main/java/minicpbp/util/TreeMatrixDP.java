/*
 * mini-cpbp, replacing classic propagation by belief propagation
 *
 * Exact weighted counting for the Tree constraint (round 5, 2026-08-18).
 * See TREE_GAC_DESIGN.md, TREE_EXPERIMENT.md and paper/regime2_section.md.
 *
 * Semantics counted. Variables succ_0..succ_{n-1}; a[i][j] >= 0 is the
 * outside belief of succ_i = j. A self-loop (succ_i = i, weight a[i][i])
 * makes i a root. A full assignment is a solution iff its functional graph
 * has no cycle other than root self-loops, i.e. it is a forest of
 * anti-arborescences. The weighted count is
 *
 *     Z = sum over forests F of prod_i a[i][succ_i(F)].
 *
 * Directed Matrix-Tree / Matrix-Forest theorem (Kirchhoff lineage; directed
 * all-minors form Chaiken 1982; forest form Chebotarev-Shamis 1997): with
 *
 *     M[i][i] = sum_j a[i][j]        (self-loop weight included)
 *     M[i][j] = -a[i][j]             (j != i)
 *
 * det(M) = Z. The determinant is multilinear in the entries of each row, so
 * by Jacobi's formula (d det / d M[p][q] = adj(M)[q][p]) the LEAVE-OWN-
 * BELIEF-OUT message for succ_i = j is
 *
 *     mu[i][j] = d det / d a[i][j] = adj[i][i] - adj[j][i]   (j != i)
 *     mu[i][i] = d det / d a[i][i] = adj[i][i]
 *
 * i.e. ONE adjugate yields every (variable, value) message simultaneously —
 * the regime-2 shared-work property. No message is ever obtained by
 * dividing the total count by an own belief (division ban): the adjugate IS
 * the vector of leave-one-out counts, exactly as the forward/backward
 * passes are in the regime-1 DP counters. The divisions inside the LU
 * factorization are pivot divisions of linear algebra, not belief
 * deflation.
 *
 * Two paths:
 *
 *  runForest    one real LU + inverse, O(n^3). Counts forests with ANY
 *               number of roots. The caller uses it when the tree-count
 *               variable imposes no distinction between solutions — in
 *               this codebase: when there is exactly one potential root
 *               (every solution has NTREES = 1).
 *
 *  runWithCount the count-resolved path. P(t) = det(A(t)) with the
 *               self-loop weights multiplied by t is the generating
 *               polynomial whose coefficient c_k is the weighted count of
 *               forests with exactly k roots (Matrix-Forest theorem;
 *               heterogeneous root weights). Coefficients are extracted by
 *               evaluation at the (n+1)-st roots of unity and an inverse
 *               DFT — unitary, hence numerically benign, unlike real
 *               Vandermonde interpolation. One complex LU + inverse per
 *               evaluation point: O(n^4) total, capped by the caller
 *               (TreeConfig.NTREES_MAX_N). It returns both the coefficient
 *               vector c_k (the exact, leave-own-out belief over the
 *               NTREES variable — the "belief over a counting variable" of
 *               research_plan section 2.9) and the succ messages folded
 *               with the NTREES outside belief wN.
 *
 * Both paths return -1 on numerical failure (near-singular LU); the caller
 * falls back to the uniform belief with setExactWCounting(false), which
 * never prunes and is therefore sound.
 */

package minicpbp.util;

public final class TreeMatrixDP {

    private static final double RCOND_TOL = 1e-13;

    private final int maxN;
    // real scratch
    private final double[][] lu;
    private final int[] piv;
    private final double[][] inv;
    private final double[] col;
    // complex scratch (re/im pairs)
    private final double[][] cluRe, cluIm;
    private final double[][] cinvRe, cinvIm;
    private final double[] ccolRe, ccolIm;
    // per-evaluation-point adjugate stash for the complex path
    private final double[][][] adjRe, adjIm; // [h][i][j]
    private final double[] detRe, detIm;     // det at each point

    public TreeMatrixDP(int maxN, int ntreesMaxN) {
        this.maxN = maxN;
        lu = new double[maxN][maxN];
        piv = new int[maxN];
        inv = new double[maxN][maxN];
        col = new double[maxN];
        int cn = ntreesMaxN;
        cluRe = new double[cn][cn];
        cluIm = new double[cn][cn];
        cinvRe = new double[cn][cn];
        cinvIm = new double[cn][cn];
        ccolRe = new double[cn];
        ccolIm = new double[cn];
        adjRe = new double[cn + 1][cn][cn];
        adjIm = new double[cn + 1][cn][cn];
        detRe = new double[cn + 1];
        detIm = new double[cn + 1];
    }

    // =====================================================================
    // real path
    // =====================================================================

    /**
     * Weighted forest count over all root multiplicities, plus all
     * leave-own-belief-out messages.
     *
     * @param n   number of nodes (n <= maxN)
     * @param a   n x n nonnegative weights; a[i][j] = 0 encodes "j not in
     *            D(succ_i)"; a[i][i] is the root weight of i
     * @param msg n x n output; msg[i][j] = weighted count of forests with
     *            succ_i = j, weight a[i][j] excluded; clamped at 0
     * @return Z = det(M) >= 0, or -1 on numerical failure
     */
    public double runForest(int n, double[][] a, double[][] msg) {
        if (n > maxN) return -1;
        // build M
        for (int i = 0; i < n; i++) {
            double d = 0;
            for (int j = 0; j < n; j++) d += a[i][j];
            for (int j = 0; j < n; j++) lu[i][j] = (i == j) ? d : -a[i][j];
        }
        double det = luFactor(n);
        if (Double.isNaN(det)) return -1;
        if (!invertFromLu(n)) return -1;
        // adj = det * inv; messages from adjugate entries
        // Messages are computed for EVERY (i, j) pair, including a[i][j] = 0:
        // the derivative d det / d a[i][j] is exact regardless of the own
        // weight (single- and multi-zero crediting comes for free from
        // multilinearity — no DP crediting scheme needed). The caller emits
        // only in-domain values.
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double m = (i == j)
                        ? det * inv[i][i]
                        : det * (inv[i][i] - inv[i][j]); // adj[p][q] = det*inv[p][q]? see note
                msg[i][j] = Math.max(0, m);
            }
        }
        return Math.max(0, det);
    }

    /*
     * Index note for the message formula above. Jacobi:
     * d det / d M[p][q] = det * (M^-1)[q][p]. For j != i, a[i][j] enters
     * M[i][i] with +1 and M[i][j] with -1, so
     *   mu = det * ( (M^-1)[i][i] - (M^-1)[j][i] ).
     * invertFromLu() stores the TRANSPOSED inverse, inv[q][p] =
     * (M^-1)[p][q]; hence (M^-1)[j][i] = inv[i][j] and the message reads
     * det * (inv[i][i] - inv[i][j]) with row-major locality over j.
     * The brute-force oracle in exp.TreePhase0 pins this convention.
     */

    /** LU with partial pivoting on this.lu; returns det, or NaN if the
     *  factorization is numerically rank-deficient (rcond below tol). */
    private double luFactor(int n) {
        double det = 1.0;
        double maxPiv = 0, minPiv = Double.MAX_VALUE;
        for (int k = 0; k < n; k++) {
            int p = k;
            double best = Math.abs(lu[k][k]);
            for (int r = k + 1; r < n; r++) {
                double v = Math.abs(lu[r][k]);
                if (v > best) { best = v; p = r; }
            }
            piv[k] = p;
            if (p != k) {
                double[] tmp = lu[p]; lu[p] = lu[k]; lu[k] = tmp;
                det = -det;
            }
            double pv = lu[k][k];
            double apv = Math.abs(pv);
            if (apv > maxPiv) maxPiv = apv;
            if (apv < minPiv) minPiv = apv;
            if (apv == 0) return Double.NaN;
            det *= pv;
            for (int r = k + 1; r < n; r++) {
                double f = lu[r][k] / pv;
                lu[r][k] = f;
                if (f != 0) for (int c = k + 1; c < n; c++) lu[r][c] -= f * lu[k][c];
            }
        }
        if (minPiv < RCOND_TOL * maxPiv) return Double.NaN;
        if (Double.isInfinite(det) || Double.isNaN(det)) return Double.NaN;
        return det;
    }

    /** Fills this.inv with the TRANSPOSE of M^-1 (see index note). */
    private boolean invertFromLu(int n) {
        for (int c = 0; c < n; c++) {
            for (int r = 0; r < n; r++) col[r] = (r == c) ? 1 : 0;
            // apply the FULL pivot sequence first (P b), THEN forward
            // substitute: interleaving swap-k with column-k elimination
            // against the final stored L attaches updates to rows that a
            // later swap still moves — wrong whenever pivoting triggers
            for (int k = 0; k < n; k++) {
                int p = piv[k];
                if (p != k) { double t = col[k]; col[k] = col[p]; col[p] = t; }
            }
            for (int k = 0; k < n; k++) {
                for (int r = k + 1; r < n; r++) col[r] -= lu[r][k] * col[k];
            }
            for (int k = n - 1; k >= 0; k--) {
                double s = col[k];
                for (int c2 = k + 1; c2 < n; c2++) s -= lu[k][c2] * col[c2];
                s /= lu[k][k];
                if (Double.isNaN(s) || Double.isInfinite(s)) return false;
                col[k] = s;
            }
            // column c of M^-1 -> row c of inv (transposed storage)
            for (int r = 0; r < n; r++) inv[c][r] = col[r];
        }
        return true;
    }

    // =====================================================================
    // complex DFT path (NTREES undetermined, several potential roots)
    // =====================================================================

    /**
     * Count-resolved path. Evaluates P(t) = det(A(t)) at the (n+1)-st
     * roots of unity, where A(t) is M with every self-loop weight a[i][i]
     * replaced by t * a[i][i]; c_k = coefficient of t^k = weighted count
     * of forests with exactly k roots.
     *
     * @param n    number of nodes (n <= ntreesMaxN)
     * @param a    weights as in runForest
     * @param wN   NTREES outside belief, indexed 0..n; zero outside D(NTREES)
     * @param msg  n x n output, succ messages folded with wN (leave-own-out)
     * @param msgK output, length n+1: c_k, the leave-NTREES-out message to
     *             the NTREES variable (c_0 = 0 always: every node has a
     *             successor, so at least one root exists in any forest)
     * @return Z = sum_k wN[k] * c_k >= 0, or -1 on numerical failure
     */
    public double runWithCount(int n, double[][] a, double[] wN, double[][] msg, double[] msgK) {
        // P(t) can legitimately vanish AT an evaluation point (e.g. t = -1
        // once variables are bound), where the adjugate-via-inverse breaks.
        // Any circle radius rho > 0 gives exact coefficients (they rescale by
        // rho^-k), so retry on a small ladder of radii; the rcond guard
        // rejects a radius that lands on or near a zero of P.
        for (double rho : RADII) {
            double z = runWithCountAt(n, a, wN, msg, msgK, rho);
            if (z >= 0) return z;
        }
        return -1;
    }

    private static final double[] RADII = {1.0, 1.09, 0.917, 1.23};

    private double runWithCountAt(int n, double[][] a, double[] wN, double[][] msg, double[] msgK,
                                  double rho) {
        if (n >= adjRe.length) return -1;
        int np = n + 1;
        double ang = 2 * Math.PI / np;
        for (int h = 0; h < np; h++) {
            double tre = rho * Math.cos(ang * h), tim = rho * Math.sin(ang * h);
            // build A(t_h)
            for (int i = 0; i < n; i++) {
                double off = 0;
                for (int j = 0; j < n; j++) if (j != i) off += a[i][j];
                for (int j = 0; j < n; j++) {
                    if (i == j) {
                        cluRe[i][j] = off + tre * a[i][i];
                        cluIm[i][j] = tim * a[i][i];
                    } else {
                        cluRe[i][j] = -a[i][j];
                        cluIm[i][j] = 0;
                    }
                }
            }
            if (!cluFactorAndInvert(n, h)) return -1;
        }
        // c_k by inverse DFT of det, rescaled by rho^-k; clamp negatives
        // (roundoff)
        double[] rhoInvK = new double[n + 1];
        rhoInvK[0] = 1;
        for (int k = 1; k <= n; k++) rhoInvK[k] = rhoInvK[k - 1] / rho;
        for (int k = 0; k <= n; k++) {
            double s = 0;
            for (int h = 0; h < np; h++) {
                double wre = Math.cos(ang * h * k), wim = -Math.sin(ang * h * k);
                s += detRe[h] * wre - detIm[h] * wim;
            }
            msgK[k] = Math.max(0, s * rhoInvK[k] / np);
        }
        // fold NTREES belief: g_h = (1/np) sum_k wN[k] rho^-k e^{-2 pi i h k / np}
        double[] gRe = new double[np], gIm = new double[np];
        for (int h = 0; h < np; h++) {
            double sre = 0, sim = 0;
            for (int k = 0; k <= n; k++) {
                if (wN[k] == 0) continue;
                sre += wN[k] * rhoInvK[k] * Math.cos(ang * h * k);
                sim -= wN[k] * rhoInvK[k] * Math.sin(ang * h * k);
            }
            gRe[h] = sre / np;
            gIm[h] = sim / np;
        }
        // succ messages (mu = det*(M^-1[i][i] - M^-1[j][i]); with the
        // storage adjRe[h][x][y] = det * M^-1[y][x] that is
        // adjRe[h][i][i] - adjRe[h][i][j]):
        //   mu_ij = Re sum_h g_h * (adjStash_h[i][i] - adjStash_h[i][j])   (j != i)
        //   mu_ii = Re sum_h g_h * t_h * adjStash_h[i][i]
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double s = 0;
                if (i == j) {
                    for (int h = 0; h < np; h++) {
                        double tre = rho * Math.cos(ang * h), tim = rho * Math.sin(ang * h);
                        double are = adjRe[h][i][i], aim = adjIm[h][i][i];
                        double pre = tre * are - tim * aim;
                        double pim = tre * aim + tim * are;
                        s += gRe[h] * pre - gIm[h] * pim;
                    }
                } else {
                    for (int h = 0; h < np; h++) {
                        double are = adjRe[h][i][i] - adjRe[h][i][j];
                        double aim = adjIm[h][i][i] - adjIm[h][i][j];
                        s += gRe[h] * are - gIm[h] * aim;
                    }
                }
                msg[i][j] = Math.max(0, s);
            }
        }
        double z = 0;
        for (int k = 0; k <= n; k++) z += wN[k] * msgK[k];
        return Math.max(0, z);
    }

    /** Complex LU with partial pivoting (by modulus) of clu; stores det in
     *  detRe/detIm[h] and the full adjugate (det * inverse, TRANSPOSED like
     *  the real path) in adjRe/adjIm[h]. */
    private boolean cluFactorAndInvert(int n, int h) {
        double dre = 1, dim = 0;
        double maxPiv = 0, minPiv = Double.MAX_VALUE;
        int[] cpiv = piv; // reuse
        for (int k = 0; k < n; k++) {
            int p = k;
            double best = mod2(cluRe[k][k], cluIm[k][k]);
            for (int r = k + 1; r < n; r++) {
                double v = mod2(cluRe[r][k], cluIm[r][k]);
                if (v > best) { best = v; p = r; }
            }
            cpiv[k] = p;
            if (p != k) {
                double[] t;
                t = cluRe[p]; cluRe[p] = cluRe[k]; cluRe[k] = t;
                t = cluIm[p]; cluIm[p] = cluIm[k]; cluIm[k] = t;
                dre = -dre; dim = -dim;
            }
            double pre = cluRe[k][k], pim = cluIm[k][k];
            double pm = Math.sqrt(mod2(pre, pim));
            if (pm > maxPiv) maxPiv = pm;
            if (pm < minPiv) minPiv = pm;
            if (pm == 0) return false;
            double nre = dre * pre - dim * pim;
            double nim = dre * pim + dim * pre;
            dre = nre; dim = nim;
            double den = pre * pre + pim * pim;
            for (int r = k + 1; r < n; r++) {
                double fre = (cluRe[r][k] * pre + cluIm[r][k] * pim) / den;
                double fim = (cluIm[r][k] * pre - cluRe[r][k] * pim) / den;
                cluRe[r][k] = fre; cluIm[r][k] = fim;
                if (fre != 0 || fim != 0) {
                    for (int c = k + 1; c < n; c++) {
                        cluRe[r][c] -= fre * cluRe[k][c] - fim * cluIm[k][c];
                        cluIm[r][c] -= fre * cluIm[k][c] + fim * cluRe[k][c];
                    }
                }
            }
        }
        if (minPiv < RCOND_TOL * maxPiv) return false;
        detRe[h] = dre; detIm[h] = dim;
        // inverse column by column, then adj = det * inv, stored transposed
        for (int c = 0; c < n; c++) {
            for (int r = 0; r < n; r++) { ccolRe[r] = (r == c) ? 1 : 0; ccolIm[r] = 0; }
            // full pivot sequence first, then forward substitution (see the
            // comment in invertFromLu)
            for (int k = 0; k < n; k++) {
                int p = cpiv[k];
                if (p != k) {
                    double t;
                    t = ccolRe[k]; ccolRe[k] = ccolRe[p]; ccolRe[p] = t;
                    t = ccolIm[k]; ccolIm[k] = ccolIm[p]; ccolIm[p] = t;
                }
            }
            for (int k = 0; k < n; k++) {
                double bre = ccolRe[k], bim = ccolIm[k];
                if (bre == 0 && bim == 0) continue;
                for (int r = k + 1; r < n; r++) {
                    ccolRe[r] -= cluRe[r][k] * bre - cluIm[r][k] * bim;
                    ccolIm[r] -= cluRe[r][k] * bim + cluIm[r][k] * bre;
                }
            }
            for (int k = n - 1; k >= 0; k--) {
                double sre = ccolRe[k], sim = ccolIm[k];
                for (int c2 = k + 1; c2 < n; c2++) {
                    sre -= cluRe[k][c2] * ccolRe[c2] - cluIm[k][c2] * ccolIm[c2];
                    sim -= cluRe[k][c2] * ccolIm[c2] + cluIm[k][c2] * ccolRe[c2];
                }
                double pre = cluRe[k][k], pim = cluIm[k][k];
                double den = pre * pre + pim * pim;
                double ore = (sre * pre + sim * pim) / den;
                double oim = (sim * pre - sre * pim) / den;
                if (Double.isNaN(ore) || Double.isNaN(oim)) return false;
                ccolRe[k] = ore; ccolIm[k] = oim;
            }
            for (int r = 0; r < n; r++) {
                // adj[q][p] = det * inv[p][q]; transposed storage: adjRe[h][c][r]
                adjRe[h][c][r] = detRe[h] * ccolRe[r] - detIm[h] * ccolIm[r];
                adjIm[h][c][r] = detRe[h] * ccolIm[r] + detIm[h] * ccolRe[r];
            }
        }
        return true;
    }

    private static double mod2(double re, double im) {
        return re * re + im * im;
    }
}
