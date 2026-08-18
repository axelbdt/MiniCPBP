package minicpbp.util;



/**
 * Exact oracle for SELF-REFERENTIAL gcc systems (followup.md §1.1): systems
 * where some occurrence variable o_j IS one of the x variables (selfIdx[j] =
 * i, the MagicSequence shape). CountVectorDP treats x and o as disjoint and
 * therefore counts a RELAXATION on such systems (it drops the constraint
 * x_i = c_j); this class enforces it.
 *
 * Model and weight conventions are IDENTICAL to CountVectorDP — a[i][j],
 * b[i], occurrence windows [low, up], occurrence beliefs wOcc — including the
 * fact that a self-referential variable contributes BOTH its x-row weight
 * a[i][.] and its occurrence belief wOcc[j][.]. That repeated-variable weight
 * placement is what the solver's counting routines see (positions i and n+j
 * of the scope carry separate outside beliefs), so keeping it makes the
 * oracle-vs-arm comparison measure exactly one thing: the dropped x_i = c_j
 * constraint, not a change of weight model.
 *
 * Added constraint, per self-referential class j with owner i = selfIdx[j]:
 * the value taken by x_i must equal the final count c_j. Consequences used
 * by the algorithm:
 * - x_i's value is fully determined by the final count vector c (if two
 *   classes share an owner, their counts must agree, else the state is
 *   infeasible);
 * - a forced value that is not a tracked class value would fall in the
 *   "other" class, whose weight b[i] aggregates several values and cannot be
 *   split; such states are treated as weight-0 and COUNTED in
 *   untrackedStates() for reporting. (On MagicSequence every possible count
 *   value is a tracked value, so this never fires there.)
 *
 * Algorithm: layered count-vector DP over the FREE variables only (those
 * that are not an occurrence variable), then a terminal sweep over all full
 * count-vector states c that (1) folds in windows, occurrence beliefs, and
 * the self layer (forced values, their weights, and their own contribution
 * d(c) to the counts), (2) accumulates the terminal weight H[c - d(c)] for
 * the free-variable backward pass, and (3) emits the self variables' and
 * occurrence variables' messages directly. Free-variable messages then come
 * from the standard forward/backward rolling pass with bwd initialized to H
 * instead of the product-form acceptance — no polynomial deflation anywhere
 * (research_plan §7).
 *
 * Cost: same class as CountVectorDP — O(n * S * k) time, (nf+2) * S doubles,
 * S = prod(up[j]+1); state-space budget identical.
 */
public final class SelfRefOracle {

    private final int maxStates;
    private double[][] fwd;
    private double[] bwd, bwdNext, H;
    private int allocN = -1;
    private long untrackedStates = 0;

    public SelfRefOracle(int maxStates) {
        this.maxStates = maxStates;
    }

    /** states whose forced self value was untracked in the last run (weight-0 by convention). */
    public long untrackedStates() {
        return untrackedStates;
    }

    private void ensure(int nf, int S) {
        if (fwd == null || allocN < nf || fwd[0].length < S) {
            fwd = new double[nf + 1][S];
            bwd = new double[S];
            bwdNext = new double[S];
            H = new double[S];
            allocN = nf;
        }
    }

    /**
     * @param vals    vals[j] = the counted value of class j
     * @param selfIdx selfIdx[j] = i if o_j IS x_i, else -1 (at least one >= 0)
     * @return Z (rescaled; sign/zero meaningful) or -1 if the state space exceeds maxStates
     * other parameters and msg/msgOcc as in CountVectorDP.run
     */
    public double run(int n, int k, double[][] a, double[] b, int[] low, int[] up,
                      double[][] wOcc, int[] vals, int[] selfIdx,
                      double[][] msg, double[][] msgOcc) {
        long Sl = CountVectorDP.stateCount(up, maxStates);
        if (Sl < 0) return -1;
        int S = (int) Sl;
        untrackedStates = 0;

        // class of a value, over the value range that can appear as a count (0..n)
        java.util.HashMap<Integer, Integer> classOfValue = new java.util.HashMap<>();
        for (int j = 0; j < k; j++) classOfValue.put(vals[j], j);

        // owners: distinct self variables and the classes they own
        boolean[] isSelf = new boolean[n];
        int[][] classesOf = new int[n][];
        {
            int[] cnt = new int[n];
            for (int j = 0; j < k; j++) if (selfIdx[j] >= 0) cnt[selfIdx[j]]++;
            for (int i = 0; i < n; i++) if (cnt[i] > 0) { isSelf[i] = true; classesOf[i] = new int[cnt[i]]; }
            int[] fill = new int[n];
            for (int j = 0; j < k; j++) if (selfIdx[j] >= 0) {
                int i = selfIdx[j];
                classesOf[i][fill[i]++] = j;
            }
        }
        int[] selfVars = new int[n];
        int nSelf = 0;
        for (int i = 0; i < n; i++) if (isSelf[i]) selfVars[nSelf++] = i;
        int nf = n - nSelf;
        int[] freeVars = new int[nf];
        {
            int t = 0;
            for (int i = 0; i < n; i++) if (!isSelf[i]) freeVars[t++] = i;
        }
        ensure(nf, S);

        int[] stride = new int[k];
        int st = 1;
        for (int j = 0; j < k; j++) {
            stride[j] = st;
            st *= (up[j] + 1);
        }

        // ---- forward over free variables ----
        java.util.Arrays.fill(fwd[0], 0, S, 0.0);
        fwd[0][0] = 1.0;
        for (int t = 0; t < nf; t++) {
            int i = freeVars[t];
            double[] cur = fwd[t];
            double[] nxt = fwd[t + 1];
            java.util.Arrays.fill(nxt, 0, S, 0.0);
            double bi = b[i];
            for (int s = 0; s < S; s++) {
                double p = cur[s];
                if (p == 0.0) continue;
                if (bi != 0.0) nxt[s] += p * bi;
                for (int j = 0; j < k; j++) {
                    int cj = (s / stride[j]) % (up[j] + 1);
                    if (cj < up[j]) {
                        double aij = a[i][j];
                        if (aij != 0.0) nxt[s + stride[j]] += p * aij;
                    }
                }
            }
            normalize(nxt, S);
        }

        // ---- terminal sweep over full states c ----
        for (double[] row : msg) java.util.Arrays.fill(row, 0.0);
        if (msgOcc != null)
            for (int j = 0; j < k; j++) if (msgOcc[j] != null) java.util.Arrays.fill(msgOcc[j], 0, up[j] + 1, 0.0);
        java.util.Arrays.fill(H, 0, S, 0.0);
        double z = 0.0;
        int[] c = new int[k];
        double[] wFac = new double[k];       // per-class occurrence factor at this state
        int[] forcedCls = new int[nSelf];    // class forced on each self var at this state
        for (int s = 0; s < S; s++) {
            boolean ok = true;
            for (int j = 0; j < k; j++) {
                int cj = (s / stride[j]) % (up[j] + 1);
                c[j] = cj;
                if (cj < low[j]) { ok = false; break; }
                double[] w = (wOcc == null) ? null : wOcc[j];
                wFac[j] = (w == null) ? 1.0 : w[cj];
            }
            if (!ok) continue;

            // self layer: forced values, agreement, tracked-ness, weights, count shift
            double aSelf = 1.0;
            int sf = s;
            boolean feasible = true;
            for (int t = 0; t < nSelf && feasible; t++) {
                int i = selfVars[t];
                int[] Ji = classesOf[i];
                int v = c[Ji[0]];
                for (int q = 1; q < Ji.length; q++)
                    if (c[Ji[q]] != v) { feasible = false; break; }
                if (!feasible) break;
                Integer cls = classOfValue.get(v);
                if (cls == null) { untrackedStates++; feasible = false; break; }
                forcedCls[t] = cls;
                double w = a[i][cls];
                if (w == 0.0) { feasible = false; break; }
                aSelf *= w;
                // the self variable itself occupies one slot of its forced class
                int have = (sf / stride[cls]) % (up[cls] + 1);
                if (have == 0) { feasible = false; break; } // free vars cannot supply -1
                sf -= stride[cls];
            }
            if (!feasible) continue;

            double wAll = aSelf;
            for (int j = 0; j < k; j++) wAll *= wFac[j];
            if (wAll != 0.0) H[sf] += wAll;

            double pf = fwd[nf][sf];

            // occurrence messages: exclude the class's own wOcc factor
            if (msgOcc != null && pf != 0.0 && aSelf != 0.0) {
                // prefix/suffix products of wFac for O(k) exclusion
                double pre = 1.0;
                boolean zeroSeen = false;
                int zeroAt = -1;
                for (int j = 0; j < k; j++) {
                    if (wFac[j] == 0.0) {
                        if (zeroSeen) { zeroAt = -2; break; } // two zeros: every exclusion is 0
                        zeroSeen = true;
                        zeroAt = j;
                    }
                }
                if (zeroAt != -2) {
                    if (!zeroSeen) {
                        double all = 1.0;
                        for (int j = 0; j < k; j++) all *= wFac[j];
                        for (int j = 0; j < k; j++)
                            if (msgOcc[j] != null) msgOcc[j][c[j]] += pf * aSelf * (all / wFac[j]);
                    } else if (msgOcc[zeroAt] != null) {
                        double excl = 1.0;
                        for (int j = 0; j < k; j++) if (j != zeroAt) excl *= wFac[j];
                        msgOcc[zeroAt][c[zeroAt]] += pf * aSelf * excl;
                    }
                }
            }

            if (wAll == 0.0 || pf == 0.0) {
                // still contribute self-variable messages when only the OWN
                // factor of that variable is the zero? own factor a[i][cls]
                // zero was already declared infeasible above (support empty),
                // so nothing to credit here.
                continue;
            }
            z += pf * wAll;

            // self-variable messages: exclude the variable's own a-factor
            for (int t = 0; t < nSelf; t++) {
                int i = selfVars[t];
                int cls = forcedCls[t];
                msg[i][cls] += pf * wAll / a[i][cls];
            }
        }

        // ---- backward over free variables, terminal = H ----
        System.arraycopy(H, 0, bwd, 0, S);
        normalize(bwd, S); // constant cancels in row-normalized messages
        for (int t = nf - 1; t >= 0; t--) {
            int i = freeVars[t];
            double[] cur = fwd[t];
            double[] mi = msg[i];
            double mOther = 0.0;
            for (int s = 0; s < S; s++) {
                double p = cur[s];
                if (p == 0.0) continue;
                double back = bwd[s];
                if (back != 0.0) mOther += p * back;
                for (int j = 0; j < k; j++) {
                    int cj = (s / stride[j]) % (up[j] + 1);
                    if (cj < up[j]) {
                        double bj = bwd[s + stride[j]];
                        if (bj != 0.0) mi[j] += p * bj;
                    }
                }
            }
            mi[k] = mOther;
            double bi = b[i];
            for (int s = 0; s < S; s++) {
                double acc = bi != 0.0 ? bi * bwd[s] : 0.0;
                for (int j = 0; j < k; j++) {
                    int cj = (s / stride[j]) % (up[j] + 1);
                    if (cj < up[j]) {
                        double aij = a[i][j];
                        if (aij != 0.0) acc += aij * bwd[s + stride[j]];
                    }
                }
                bwdNext[s] = acc;
            }
            double[] tmp = bwd; bwd = bwdNext; bwdNext = tmp;
            normalize(bwd, S);
        }
        return z;
    }

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
