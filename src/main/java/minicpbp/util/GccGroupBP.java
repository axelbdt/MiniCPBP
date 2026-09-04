/*
 * mini-cpbp, replacing classic propagation by belief propagation
 *
 * Grouped nested belief propagation for the cardinality (gcc) constraint
 * (AMONG_GCC_OPTIMIZATION_PLAN.md WP3, lever L3).
 */

package minicpbp.util;

/**
 * Belief propagation between the per-variable "exactly one class or other"
 * row factors and m JOINT count factors, one per group of counted classes.
 *
 * <p>The two existing kernels are the endpoints of this family:
 * <ul>
 *   <li>m = 1 — one group holding all k classes — is the exact count-vector
 *       DP {@link CountVectorDP}: a single factor, no outer iteration, and
 *       this class then runs one DP call and copies its messages, so the
 *       output is bit-for-bit {@code CountVectorDP.run}.</li>
 *   <li>m = k — one group per class — is {@link GccBP}: each group factor is
 *       an Among over one class, and the outer loop is GccBP's sweep. The
 *       messages agree with GccBP's ratios up to summation order.</li>
 * </ul>
 * Intermediate m keeps the strongest couplings — the classes with the largest
 * occurrence bounds — inside one exact factor, and approximates only the
 * coupling ACROSS groups.
 *
 * <p>Messages. Group g holds classes G_g. From group g every variable i
 * receives one entry per class of G_g plus one "other" entry that applies to
 * every option outside G_g (any class of another group, and the uncounted
 * values). The variable-to-group message is the cavity product
 *
 * <pre>
 *   q_i(j)     = a[i][j] * prod_{g' != g} M_{g'}(i, j)          j in G_g
 *   b_g(i)     = b[i]    * prod_{g' != g} M_{g'}(i, other)
 *              + sum_{j not in G_g} a[i][j] * prod_{g'' != g} M_{g''}(i, j)
 * </pre>
 *
 * so the mass a variable can place outside the group — on another group's
 * class or on an uncounted value — is folded into the group factor's "other"
 * column exactly as {@link CountVectorDP} expects. Every group factor is then
 * evaluated EXACTLY by {@link CountVectorDP} over its own count vector, which
 * keeps the deflation ban of research_plan.md §7: leave-one-out always comes
 * from the DP's forward/backward passes, never from dividing a group's own
 * message back out.
 *
 * <p>The factor-to-variable message returned to the caller is the product
 * over ALL groups (own outside belief excluded — message, not marginal), and
 * the message to occurrence variable o_j is the one its own group's DP
 * emitted.
 *
 * <p>Stop criterion: the same solver-facing belief stability as
 * {@link GccBP} — after each sweep, p_i(v) proportional to theta_i(v) m_i(v)
 * collapsed to the class level, R_t the maximum total-variation distance
 * between consecutive sweeps, stop once R_t &lt;= eps subject to a minimum
 * sweep count.
 */
public final class GccGroupBP {

    private final CountVectorDP dp;
    private final int maxStates;

    // partition
    private int[] grp;        // k, group id of each class
    private int[] slot;       // k, index of the class inside its group
    private int[][] gCls;     // m x |G_g|, classes of each group
    private int m;

    // per-group scratch
    private double[][][] gA;  // m x n x (|G_g|)
    private double[][] gB;    // m x n
    private int[][] gLow, gUp;
    private double[][][] gW;  // m x |G_g| x (up+1)
    private double[][][] gMsg;// m x n x (|G_g|+1), last message from group g
    private double[][] pPrev; // n x (k+1) previous solver-facing beliefs
    private boolean havePrev;
    private int allocN = -1, allocK = -1;

    private long nbCalls, nbConverged;
    private int lastIterations;
    private boolean lastConverged;
    private int lastGroups;

    public GccGroupBP(int maxStates) {
        this.maxStates = maxStates;
        this.dp = new CountVectorDP(maxStates);
    }

    public int lastIterations() { return lastIterations; }
    public boolean lastConverged() { return lastConverged; }
    public int lastGroups() { return lastGroups; }
    public long nbCalls() { return nbCalls; }
    public long nbConverged() { return nbConverged; }

    /**
     * Greedy partition: classes sorted by up descending, each added to the
     * current group while the group's state count prod(up+1) stays within
     * {@code stateCap}; otherwise a new group starts. Descending order keeps
     * the large counts — the strong couplings — together, and the resulting m
     * is the smallest this order admits under the cap.
     *
     * @param grpOut out: group id per class, length k
     * @return the number of groups
     */
    public static int partition(int k, int[] up, long stateCap, int[] grpOut) {
        Integer[] idx = new Integer[k];
        for (int j = 0; j < k; j++) idx[j] = j;
        final int[] u = up;
        java.util.Arrays.sort(idx, (x, y) -> Integer.compare(u[y], u[x]));
        int g = 0;
        long states = 1;
        boolean empty = true;
        for (int t = 0; t < k; t++) {
            int j = idx[t];
            long next = states * (up[j] + 1L);
            if (!empty && (next > stateCap || next < 0)) {
                g++;
                states = up[j] + 1L;
                grpOut[j] = g;
                empty = false;
                continue;
            }
            states = next;
            grpOut[j] = g;
            empty = false;
        }
        return g + 1;
    }

    private void ensure(int n, int k) {
        if (allocN < n || allocK < k) {
            grp = new int[k];
            slot = new int[k];
            pPrev = new double[n][k + 1];
            allocN = n;
            allocK = k;
            havePrev = false;
        }
    }

    private void buildGroups(int n, int k, int[] low, int[] up, double[][] wOcc, long stateCap) {
        m = partition(k, up, stateCap, grp);
        int[] size = new int[m];
        for (int j = 0; j < k; j++) size[grp[j]]++;
        gCls = new int[m][];
        for (int g = 0; g < m; g++) gCls[g] = new int[size[g]];
        int[] fill = new int[m];
        for (int j = 0; j < k; j++) {
            int g = grp[j];
            slot[j] = fill[g];
            gCls[g][fill[g]++] = j;
        }
        gA = new double[m][][];
        gB = new double[m][];
        gLow = new int[m][];
        gUp = new int[m][];
        gW = new double[m][][];
        gMsg = new double[m][][];
        for (int g = 0; g < m; g++) {
            int kg = gCls[g].length;
            gA[g] = new double[n][kg];
            gB[g] = new double[n];
            gLow[g] = new int[kg];
            gUp[g] = new int[kg];
            gW[g] = (wOcc == null) ? null : new double[kg][];
            gMsg[g] = new double[n][kg + 1];
            for (int t = 0; t < kg; t++) {
                int j = gCls[g][t];
                gLow[g][t] = low[j];
                gUp[g][t] = up[j];
                if (gW[g] != null) gW[g][t] = wOcc[j];
                for (int i = 0; i < n; i++) gMsg[g][i][t] = 1.0;
            }
            for (int i = 0; i < n; i++) gMsg[g][i][kg] = 1.0;
        }
    }

    /** message from group g to variable i for class j (j need not be in G_g). */
    private double mOf(int g, int i, int j) {
        int kg = gCls[g].length;
        return (grp[j] == g) ? gMsg[g][i][slot[j]] : gMsg[g][i][kg];
    }

    /** message from group g to variable i for an uncounted value. */
    private double mOther(int g, int i) {
        return gMsg[g][i][gCls[g].length];
    }

    /**
     * Runs grouped BP.
     *
     * @param stateCap per-group state cap prod_{j in G}(up_j+1) used by the partition
     * @param msg      out: msg[i][j], j &lt; k the class message, j = k the "other" message
     * @param msgOcc   out: msgOcc[j][c], or null; the caller must zero it
     * @return false on numerical failure (the caller falls back)
     */
    public boolean run(int n, int k, double[][] a, double[] b, int[] low, int[] up,
                       double[][] wOcc, int sweeps, double[][] msg, double[][] msgOcc,
                       double eps, long stateCap, int minSweeps) {
        nbCalls++;
        ensure(n, k);
        buildGroups(n, k, low, up, wOcc, stateCap);
        lastGroups = m;

        if (m == 1) {
            // one group: the exact DP, bit-for-bit, no outer iteration
            lastIterations = 1;
            lastConverged = true;
            nbConverged++;
            double z = dp.run(n, k, a, b, low, up, wOcc, msg, msgOcc);
            return !(z < 0 || Double.isNaN(z));
        }

        boolean converged = false;
        int it = 0;
        for (; it < sweeps; it++) {
            for (int g = 0; g < m; g++) {
                int kg = gCls[g].length;
                for (int i = 0; i < n; i++) {
                    double outside = 0.0;
                    // classes of other groups: their mass joins this group's "other"
                    for (int j = 0; j < k; j++) {
                        if (grp[j] == g) continue;
                        double q = a[i][j];
                        if (q == 0.0) continue;
                        for (int g2 = 0; g2 < m; g2++) {
                            if (g2 == g) continue;
                            q *= mOf(g2, i, j);
                        }
                        outside += q;
                    }
                    double bo = b[i];
                    if (bo != 0.0) {
                        for (int g2 = 0; g2 < m; g2++) {
                            if (g2 == g) continue;
                            bo *= mOther(g2, i);
                        }
                    }
                    gB[g][i] = bo + outside;
                    for (int t = 0; t < kg; t++) {
                        int j = gCls[g][t];
                        double q = a[i][j];
                        if (q != 0.0) {
                            for (int g2 = 0; g2 < m; g2++) {
                                if (g2 == g) continue;
                                q *= mOf(g2, i, j);
                            }
                        }
                        gA[g][i][t] = q;
                    }
                }
                if (msgOcc != null) {
                    for (int t = 0; t < kg; t++) {
                        int j = gCls[g][t];
                        java.util.Arrays.fill(msgOcc[j], 0, up[j] + 1, 0.0);
                    }
                }
                double[][] mo = null;
                if (msgOcc != null) {
                    mo = new double[kg][];
                    for (int t = 0; t < kg; t++) mo[t] = msgOcc[gCls[g][t]];
                }
                double z = dp.run(n, kg, gA[g], gB[g], gLow[g], gUp[g], gW[g], gMsg[g], mo);
                if (z < 0 || Double.isNaN(z)) return false;
                // renormalize each row by its "other" entry, so the stored
                // messages are ratios and cannot drift over sweeps; the caller
                // row-normalizes, so a per-(i,g) constant is immaterial
                for (int i = 0; i < n; i++) {
                    double d = gMsg[g][i][kg];
                    if (d > 0.0 && !Double.isInfinite(d)) {
                        for (int t = 0; t <= kg; t++) gMsg[g][i][t] /= d;
                    }
                }
            }
            // solver-facing beliefs and the stability test
            double rt = 0.0;
            for (int i = 0; i < n; i++) {
                double sum = 0.0;
                double[] p = new double[k + 1];
                for (int j = 0; j < k; j++) {
                    double w = a[i][j];
                    if (w != 0.0) for (int g = 0; g < m; g++) w *= mOf(g, i, j);
                    p[j] = w;
                    sum += w;
                }
                double w = b[i];
                if (w != 0.0) for (int g = 0; g < m; g++) w *= mOther(g, i);
                p[k] = w;
                sum += w;
                if (sum <= 0.0 || Double.isNaN(sum) || Double.isInfinite(sum)) return false;
                double tv = 0.0;
                for (int j = 0; j <= k; j++) {
                    p[j] /= sum;
                    tv += Math.abs(p[j] - pPrev[i][j]);
                }
                pPrev[i] = p;
                rt = Math.max(rt, 0.5 * tv);
            }
            boolean enough = (it + 1) >= minSweeps;
            if (havePrev && enough && eps > 0.0 && rt <= eps) {
                converged = true;
                it++;
                break;
            }
            havePrev = true;
        }
        lastIterations = it;
        lastConverged = converged;
        if (converged) nbConverged++;

        for (int i = 0; i < n; i++) {
            for (int j = 0; j < k; j++) {
                double w = 1.0;
                for (int g = 0; g < m; g++) w *= mOf(g, i, j);
                msg[i][j] = w;
            }
            double w = 1.0;
            for (int g = 0; g < m; g++) w *= mOther(g, i);
            msg[i][k] = w;
        }
        return true;
    }
}
