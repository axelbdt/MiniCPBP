/*
 * mini-cpbp, replacing classic propagation by belief propagation
 *
 * Candidate-interval counting for Cumulative (MDD_COUNTING_PLAN.md §1.1,
 * brief §3): the resource factor as a layered decision diagram over the
 * candidates, exact or width-relaxed.
 */

package minicpbp.util;

/**
 * Resource factor R of the candidate-interval graph as a decision diagram.
 *
 * <p>Layers: one per live candidate a = (job i, start s), in increasing start
 * (ties: job, then end). Every node of layer a has a z = 0 arc (weight 1) and,
 * when feasible, a z = 1 arc (weight lambda_a). Node state at the layer of
 * start s = the future release profile of the selected intervals still active
 * at s, as {@code load[t − s]} for t in [s, s + pMax), plus, for the jobs whose
 * demand fits twice under Cap (2d ≤ Cap — the only jobs whose overlapping
 * second placement the profile alone would not reject), the remaining active
 * length of the job (0 = not active). Entries expire by shifting when the
 * layer start advances. Nodes with equal state merge exactly.
 *
 * <p>z = 1 is feasible iff the job is not active and, for every t in [s, e),
 * load + d + fixedLoad(t) ≤ Cap, where fixedLoad is the table's committed
 * profile of the bound jobs.
 *
 * <p>Width cap W (0 = unbounded, with a safety limit beyond which the update
 * declines): when a layer exceeds W after exact merging, the W − 1 nodes of
 * largest forward mass alpha are kept and the others merge into one relaxed
 * node whose profile is the pointwise minimum of theirs and whose active
 * lengths are the minimum (a job stays forbidden only while every merged
 * state forbids it). The relaxed diagram accepts a superset of the feasible
 * selections: an upper bound, never a restriction.
 *
 * <p>Messages: forward alpha (root = 1) and backward beta (terminal = 1) with
 * per-layer rescaling as in Regular's weighted counting;
 * {@code r_a = sum_{z=1 arcs} alpha(u) beta(u') / sum_{z=0 arcs} alpha(u) beta(u')},
 * both sums on the same pair of layers so the scales cancel. No feasible z = 1
 * arc in the layer gives r = 0 (structural zero); a feasible arc whose mass
 * underflowed keeps {@code Double.MIN_NORMAL}.
 *
 * <p>Job state (amendment A4, MDD_COUNTING_PLAN.md §6.4; {@code jobState}):
 * the state additionally records, for every job, whether it has been placed
 * (no / yes / maybe — "maybe" only arises from a relaxed merge). z = 1 is
 * forbidden once the job is placed, and at the job's last layer a node in
 * which the job is certainly unplaced loses its z = 0 arc, so the diagram
 * accepts exactly one candidate per job: the exactly-one factors E_i live
 * inside R and no inner loop is needed. {@link #updateDirect} then runs one
 * pass with the outside beliefs as arc weights and returns the cavity
 * message m_a = Σ_{z=1 arcs of a} alpha(u) beta(u') — exact at exact width
 * (identity (e) in exp.PackingCheck), a relaxation otherwise. The busy
 * segment is dropped in this mode (placed subsumes it).
 *
 * <p>Cost O(M · W) per pass, plus the state hashing during the build. Rebuilt
 * from scratch at every update in this version (brief §3.5: acceptable as a
 * first correct version). Scratch memory is reused and grows monotonically.
 */
public final class ResourceMDD implements PackingFactor {

    /** layers beyond this size decline the update when W = 0 (exact mode) */
    public static final int EXACT_SAFETY_WIDTH = 1 << 16;

    private final int width; // 0 = exact
    private final boolean jobState; // amendment A4: placed / unplaced / maybe per job inside the state
    private int nPlacedWords, placedOff;    // 2-bit fields, 16 per int, after the busy segment
    private int[] lastLayerOfJob = new int[0];
    private static final int P_NO = 0, P_YES = 1, P_MAYBE = 2;

    // per-call structure
    private int[] order = new int[0];          // live candidates in layer order
    private int[] smallIdx = new int[0];       // job -> slot in the busy segment, or -1
    private int nSmall, stateLen, pMax;
    private int[] layerStart = new int[0];     // node offset of each layer (m + 2 entries)
    private int[] child0 = new int[0], child1 = new int[0];
    private double[] alpha = new double[0], beta = new double[0];
    private double[] logScaleAlpha = new double[0], logScaleBeta = new double[0];
    private int[] curState = new int[0], nxtState = new int[0];
    private int[] table = new int[0];          // open addressing: node index + 1, 0 = empty
    private int[] sortIdx = new int[0];
    private int[] remap = new int[0];
    private int[] mergedState = new int[0];
    private int[] scratch = new int[0];
    private int[] mergeStates = new int[0];
    private double[] mergeAlpha = new double[0];

    // instrumentation
    private int lastWidth, lastWidthBeforeMerge;
    private boolean lastRelaxed, lastDeclined;
    private long nbCalls, nbRelaxedCalls, nbDeclined, sumWidth;

    public ResourceMDD(int width) {
        this(width, false);
    }

    public ResourceMDD(int width, boolean jobState) {
        this.width = width;
        this.jobState = jobState;
    }

    @Override
    public String name() {
        return (width == 0 ? "mdd-exact" : "mdd-w" + width) + (jobState ? "-job" : "");
    }

    private int placedGet(int[] st, int so, int i) {
        return (st[so + placedOff + (i >> 4)] >>> ((i & 15) << 1)) & 3;
    }

    private static void placedSet(int[] st, int so, int placedOff, int i, int v) {
        int idx = so + placedOff + (i >> 4), sh = (i & 15) << 1;
        st[idx] = (st[idx] & ~(3 << sh)) | (v << sh);
    }

    /**
     * Amendment A4: one direct pass with the outside beliefs as arc weights on
     * a job-state diagram; writes the log cavity message log m_a (not a ratio;
     * −∞ when infeasible) for every live candidate. Requires {@code jobState}.
     *
     * @return false on decline (safety width) or numerical failure
     */
    public boolean updateDirect(CandidateTable t, double[] weight, double[] msg) {
        if (!jobState) throw new IllegalStateException("updateDirect needs jobState");
        direct = true;
        try {
            return update(t, weight, msg);
        } finally {
            direct = false;
        }
    }

    private boolean direct;

    @Override
    public int lastWidth() {
        return lastWidth;
    }

    /** the largest layer seen before any merging in the last update (a lower bound on the exact width once relaxed) */
    public int lastWidthBeforeMerge() {
        return lastWidthBeforeMerge;
    }

    @Override
    public boolean lastRelaxed() {
        return lastRelaxed;
    }

    /** the last update hit the exact-mode safety width and computed nothing */
    public boolean lastDeclined() {
        return lastDeclined;
    }

    public long nbCalls() {
        return nbCalls;
    }

    public long nbRelaxedCalls() {
        return nbRelaxedCalls;
    }

    public long nbDeclined() {
        return nbDeclined;
    }

    public long sumWidth() {
        return sumWidth;
    }

    /** grows with copy: several arrays are extended mid-build and must keep their prefix */
    private static int[] grow(int[] a, int need) {
        if (a.length >= need) return a;
        return java.util.Arrays.copyOf(a, Math.max(need, a.length * 2));
    }

    private static double[] grow(double[] a, int need) {
        if (a.length >= need) return a;
        return java.util.Arrays.copyOf(a, Math.max(need, a.length * 2));
    }

    @Override
    public boolean update(CandidateTable t, double[] lambda, double[] r) {
        nbCalls++;
        lastDeclined = false;
        lastRelaxed = false;
        lastWidth = 1;
        lastWidthBeforeMerge = 1;
        int M = t.M;
        // ---- layer order ----
        order = grow(order, M);
        int m = 0;
        for (int a = 0; a < M; a++) if (t.live[a]) order[m++] = a;
        if (m == 0) return true;
        sortByStart(t, m);
        // ---- state layout ----
        pMax = t.pMax;
        smallIdx = grow(smallIdx, t.n);
        nSmall = 0;
        for (int i = 0; i < t.n; i++) {
            smallIdx[i] = (!jobState && !t.inert[i] && 2 * t.d[i] <= t.cap) ? nSmall++ : -1;
        }
        placedOff = pMax + nSmall;
        nPlacedWords = jobState ? (t.n + 15) >> 4 : 0;
        stateLen = pMax + nSmall + nPlacedWords;
        layerStart = grow(layerStart, m + 2);
        if (jobState) {
            lastLayerOfJob = grow(lastLayerOfJob, t.n);
            java.util.Arrays.fill(lastLayerOfJob, 0, t.n, -1);
            for (int L = 0; L < m; L++) lastLayerOfJob[t.job[order[L]]] = L;
        }
        int cap = t.cap;
        int[] fixedLoad = t.fixedLoad;
        int tmin = t.tmin;
        // ---- root ----
        int nodes = 0;
        layerStart[0] = 0;
        logScaleAlpha = grow(logScaleAlpha, m + 2);
        logScaleBeta = grow(logScaleBeta, m + 2);
        logScaleAlpha[0] = 0.0;
        curState = grow(curState, stateLen);
        java.util.Arrays.fill(curState, 0, stateLen, 0);
        alpha = grow(alpha, 1);
        alpha[0] = 1.0;
        int curCount = 1;
        nodes = 1;
        // ---- forward build ----
        for (int L = 0; L < m; L++) {
            int a = order[L];
            int s = t.start[a];
            int p = t.p[t.job[a]];
            int d = t.dem[a];
            int si = smallIdx[t.job[a]];
            int ji = t.job[a];
            boolean lastOfJob = jobState && lastLayerOfJob[ji] == L;
            double lam = lambda[a];
            boolean terminal = (L + 1 == m);
            int shift = terminal ? Integer.MAX_VALUE : t.start[order[L + 1]] - s;
            int curBase = layerStart[L];
            int maxNext = 2 * curCount;
            nxtState = grow(nxtState, Math.max(1, maxNext) * stateLen);
            child0 = grow(child0, curBase + curCount);
            child1 = grow(child1, curBase + curCount);
            alpha = grow(alpha, curBase + curCount + maxNext + 1);
            int nxtBase = curBase + curCount;
            int nxtCount = 0;
            if (terminal) {
                logScaleAlpha[L + 1] = logScaleAlpha[L];
                // everything flows into one terminal node; states are irrelevant
                for (int u = 0; u < curCount; u++) {
                    int so = u * stateLen;
                    boolean unplaced = lastOfJob && placedGet(curState, so, ji) == P_NO;
                    child0[curBase + u] = unplaced ? -1 : nxtBase;
                    child1[curBase + u] = feasible(t, curState, so, s, p, d, si, ji, cap, fixedLoad, tmin) ? nxtBase : -1;
                }
                alpha[nxtBase] = 1.0;
                nxtCount = 1;
            } else {
                // hash table over the next layer
                int tsize = 1;
                while (tsize < 2 * maxNext + 2) tsize <<= 1;
                table = grow(table, tsize);
                java.util.Arrays.fill(table, 0, tsize, 0);
                int mask = tsize - 1;
                scratch = grow(scratch, stateLen);
                for (int u = 0; u < curCount; u++) {
                    int so = u * stateLen;
                    double au = alpha[curBase + u];
                    // z = 0: shift (A4: forbidden at the job's last layer when the job is certainly unplaced)
                    if (lastOfJob && placedGet(curState, so, ji) == P_NO) {
                        child0[curBase + u] = -1;
                    } else {
                        shifted(curState, so, scratch, shift);
                        int c0 = findOrInsert(scratch, nxtCount, mask);
                        if (c0 == nxtCount) {
                            alpha[nxtBase + nxtCount] = 0.0;
                            nxtCount++;
                        }
                        alpha[nxtBase + c0] += au;
                        child0[curBase + u] = nxtBase + c0;
                    }
                    // z = 1
                    if (feasible(t, curState, so, s, p, d, si, ji, cap, fixedLoad, tmin)) {
                        System.arraycopy(curState, so, scratch, 0, stateLen);
                        for (int q = 0; q < p; q++) scratch[q] += d;
                        if (si >= 0) scratch[pMax + si] = p;
                        if (jobState) placedSet(scratch, 0, placedOff, ji, P_YES);
                        shifted(scratch, 0, scratch, shift);
                        int c1 = findOrInsert(scratch, nxtCount, mask);
                        if (c1 == nxtCount) {
                            alpha[nxtBase + nxtCount] = 0.0;
                            nxtCount++;
                        }
                        alpha[nxtBase + c1] += lam * au;
                        child1[curBase + u] = nxtBase + c1;
                    } else {
                        child1[curBase + u] = -1;
                    }
                }
                if (nxtCount > lastWidthBeforeMerge) lastWidthBeforeMerge = nxtCount;
                if (width == 0) {
                    if (nxtCount > EXACT_SAFETY_WIDTH) {
                        lastDeclined = true;
                        nbDeclined++;
                        return false;
                    }
                } else if (nxtCount > width) {
                    nxtCount = merge(curBase, curCount, nxtBase, nxtCount);
                    lastRelaxed = true;
                }
                // rescale alpha of the new layer, remembering the cumulative scale
                double mx = 0.0;
                for (int v = 0; v < nxtCount; v++) if (alpha[nxtBase + v] > mx) mx = alpha[nxtBase + v];
                logScaleAlpha[L + 1] = logScaleAlpha[L];
                if (mx > 0.0 && mx != 1.0) {
                    double inv = 1.0 / mx;
                    for (int v = 0; v < nxtCount; v++) alpha[nxtBase + v] *= inv;
                    logScaleAlpha[L + 1] += Math.log(mx);
                }
            }
            if (nxtCount > lastWidth) lastWidth = nxtCount;
            layerStart[L + 1] = nxtBase;
            nodes = nxtBase + nxtCount;
            // swap pools
            int[] tmp = curState;
            curState = nxtState;
            nxtState = tmp;
            curCount = nxtCount;
        }
        layerStart[m + 1] = nodes;
        if (lastRelaxed) nbRelaxedCalls++;
        sumWidth += lastWidth;
        // ---- backward ----
        beta = grow(beta, nodes);
        beta[layerStart[m]] = 1.0; // terminal
        logScaleBeta[m] = 0.0;
        for (int L = m - 1; L >= 0; L--) {
            int a = order[L];
            double lam = lambda[a];
            int b = layerStart[L], e = layerStart[L + 1];
            double mx = 0.0;
            for (int u = b; u < e; u++) {
                int c0 = child0[u];
                double v = (c0 >= 0) ? beta[c0] : 0.0;
                int c1 = child1[u];
                if (c1 >= 0) v += lam * beta[c1];
                beta[u] = v;
                if (v > mx) mx = v;
            }
            logScaleBeta[L] = logScaleBeta[L + 1];
            if (mx > 0.0 && mx != 1.0) {
                double inv = 1.0 / mx;
                for (int u = b; u < e; u++) beta[u] *= inv;
                logScaleBeta[L] += Math.log(mx);
            }
        }
        // ---- messages ----
        for (int L = 0; L < m; L++) {
            int a = order[L];
            int b = layerStart[L], e = layerStart[L + 1];
            double m1 = 0.0, m0 = 0.0;
            boolean anyArc = false;
            for (int u = b; u < e; u++) {
                double au = alpha[u];
                int c0 = child0[u];
                if (c0 >= 0) m0 += au * beta[c0];
                int c1 = child1[u];
                if (c1 >= 0) {
                    anyArc = true;
                    m1 += au * beta[c1];
                }
            }
            double rv;
            if (direct) {
                // A4: the LOG of the cavity message itself. The candidates of one job sit on
                // different layers, so the per-layer rescaling is undone through the cumulative
                // log scales; the caller shifts per row and exponentiates.
                rv = (!anyArc || m1 <= 0.0) ? Double.NEGATIVE_INFINITY
                        : Math.log(m1) + logScaleAlpha[L] + logScaleBeta[L + 1];
                if (Double.isNaN(rv)) return false;
            } else if (!anyArc) rv = 0.0;
            else if (m0 > 0.0) {
                rv = m1 / m0;
                if (Double.isNaN(rv)) return false;
                if (rv > ExactlyOneRows.MU_MAX) rv = ExactlyOneRows.MU_MAX;
                else if (rv < Double.MIN_NORMAL) rv = Double.MIN_NORMAL; // feasible arc, underflown mass
            } else rv = (m1 > 0.0) ? ExactlyOneRows.MU_MAX : Double.MIN_NORMAL;
            r[a] = rv;
        }
        return true;
    }

    private boolean feasible(CandidateTable t, int[] st, int so, int s, int p, int d, int si, int ji, int cap, int[] fixedLoad, int tmin) {
        if (si >= 0 && st[so + pMax + si] > 0) return false;
        if (jobState && placedGet(st, so, ji) == P_YES) return false;
        for (int q = 0; q < p; q++) {
            if (st[so + q] + d + fixedLoad[s + q - tmin] > cap) return false;
        }
        return true;
    }

    /** dst[0..stateLen) = src[so..] shifted forward in time by shift (may alias when so == 0) */
    private void shifted(int[] src, int so, int[] dst, int shift) {
        if (shift >= pMax) {
            java.util.Arrays.fill(dst, 0, pMax, 0);
        } else {
            for (int q = 0; q < pMax - shift; q++) dst[q] = src[so + q + shift];
            java.util.Arrays.fill(dst, pMax - shift, pMax, 0);
        }
        for (int j = 0; j < nSmall; j++) {
            int v = src[so + pMax + j] - shift;
            dst[pMax + j] = v > 0 ? v : 0;
        }
        for (int j = 0; j < nPlacedWords; j++) dst[placedOff + j] = src[so + placedOff + j];
    }

    private int hash(int[] st, int so) {
        int h = 1;
        for (int q = 0; q < stateLen; q++) h = 31 * h + st[so + q];
        h ^= (h >>> 16);
        h *= 0x85ebca6b;
        h ^= (h >>> 13);
        return h;
    }

    private boolean same(int[] a, int ao, int[] b, int bo) {
        for (int q = 0; q < stateLen; q++) if (a[ao + q] != b[bo + q]) return false;
        return true;
    }

    /** index of the node with state key in the next layer; inserts (at index count) when absent */
    private int findOrInsert(int[] key, int count, int mask) {
        int h = hash(key, 0) & mask;
        while (true) {
            int e = table[h];
            if (e == 0) {
                table[h] = count + 1;
                System.arraycopy(key, 0, nxtState, count * stateLen, stateLen);
                return count;
            }
            int v = e - 1;
            if (same(nxtState, v * stateLen, key, 0)) return v;
            h = (h + 1) & mask;
        }
    }

    /**
     * Keeps the width − 1 nodes of largest alpha, merges the others into one
     * relaxed node (pointwise-minimum profile, minimum active lengths, summed
     * alpha), remaps the arcs of the current layer. Returns the new count.
     */
    private int merge(int curBase, int curCount, int nxtBase, int nxtCount) {
        sortIdx = grow(sortIdx, nxtCount);
        for (int v = 0; v < nxtCount; v++) sortIdx[v] = v;
        // partial selection: the width - 1 largest alpha first
        int keep = width - 1;
        selectTop(sortIdx, nxtCount, keep, nxtBase);
        remap = grow(remap, nxtCount);
        mergedState = grow(mergedState, stateLen);
        java.util.Arrays.fill(mergedState, 0, stateLen, Integer.MAX_VALUE);
        double mergedAlpha = 0.0;
        // new layout: kept nodes at 0..keep-1 in nxtState (compacted), relaxed node at keep
        // compaction needs a copy since positions overlap; use table as a marker of kept
        mergeStates = grow(mergeStates, (keep + 1) * stateLen);
        mergeAlpha = grow(mergeAlpha, keep + 1);
        int[] newStates = mergeStates;
        double[] newAlpha = mergeAlpha;
        for (int k = 0; k < keep; k++) {
            int v = sortIdx[k];
            System.arraycopy(nxtState, v * stateLen, newStates, k * stateLen, stateLen);
            newAlpha[k] = alpha[nxtBase + v];
            remap[v] = k;
        }
        int[] yesW = jobState ? new int[nPlacedWords] : null, noW = jobState ? new int[nPlacedWords] : null;
        for (int k = keep; k < nxtCount; k++) {
            int v = sortIdx[k];
            int so = v * stateLen;
            for (int q = 0; q < placedOff; q++) if (nxtState[so + q] < mergedState[q]) mergedState[q] = nxtState[so + q];
            if (jobState) {
                for (int wq = 0; wq < nPlacedWords; wq++) {
                    int word = nxtState[so + placedOff + wq];
                    for (int f = 0; f < 16; f++) {
                        int val = (word >>> (f << 1)) & 3;
                        if (val == P_YES) yesW[wq] |= 1 << f;
                        else if (val == P_NO) noW[wq] |= 1 << f;
                        else { yesW[wq] |= 1 << f; noW[wq] |= 1 << f; }
                    }
                }
            }
            mergedAlpha += alpha[nxtBase + v];
            remap[v] = keep;
        }
        if (jobState) {
            // yes on every merged node -> yes; no on every one -> no; otherwise maybe (relaxation)
            for (int wq = 0; wq < nPlacedWords; wq++) {
                int word = 0;
                for (int f = 0; f < 16; f++) {
                    boolean y = (yesW[wq] >>> f & 1) == 1, nn = (noW[wq] >>> f & 1) == 1;
                    int val = (y && nn) ? P_MAYBE : (y ? P_YES : P_NO);
                    word |= val << (f << 1);
                }
                mergedState[placedOff + wq] = word;
            }
        }
        System.arraycopy(mergedState, 0, newStates, keep * stateLen, stateLen);
        newAlpha[keep] = mergedAlpha;
        System.arraycopy(newStates, 0, nxtState, 0, (keep + 1) * stateLen);
        System.arraycopy(newAlpha, 0, alpha, nxtBase, keep + 1);
        for (int u = curBase; u < curBase + curCount; u++) {
            if (child0[u] >= 0) child0[u] = nxtBase + remap[child0[u] - nxtBase];
            if (child1[u] >= 0) child1[u] = nxtBase + remap[child1[u] - nxtBase];
        }
        return keep + 1;
    }

    /** partial sort of idx[0..n) so that the k largest alpha come first (quickselect, then order within the prefix is irrelevant) */
    private void selectTop(int[] idx, int n, int k, int base) {
        int lo = 0, hi = n - 1;
        while (lo < hi) {
            double pivot = alpha[base + idx[(lo + hi) >>> 1]];
            int i = lo, j = hi;
            while (i <= j) {
                while (alpha[base + idx[i]] > pivot) i++;
                while (alpha[base + idx[j]] < pivot) j--;
                if (i <= j) {
                    int tmp = idx[i];
                    idx[i] = idx[j];
                    idx[j] = tmp;
                    i++;
                    j--;
                }
            }
            if (k <= j) hi = j;
            else if (k >= i) lo = i;
            else break;
        }
    }

    /** sorts order[0..m) by (start, job, end) */
    private int[] sortTmp = new int[0];

    private void sortByStart(CandidateTable t, int m) {
        sortTmp = grow(sortTmp, m);
        mergeSort(t, order, sortTmp, 0, m);
    }

    private static boolean less(CandidateTable t, int a, int b) {
        if (t.start[a] != t.start[b]) return t.start[a] < t.start[b];
        if (t.job[a] != t.job[b]) return t.job[a] < t.job[b];
        if (t.end[a] != t.end[b]) return t.end[a] < t.end[b];
        return a < b;
    }

    private void mergeSort(CandidateTable t, int[] idx, int[] tmp, int lo, int hi) {
        if (hi - lo <= 16) {
            for (int i = lo + 1; i < hi; i++) {
                int v = idx[i];
                int j = i - 1;
                while (j >= lo && less(t, v, idx[j])) {
                    idx[j + 1] = idx[j];
                    j--;
                }
                idx[j + 1] = v;
            }
            return;
        }
        int mid = (lo + hi) >>> 1;
        mergeSort(t, idx, tmp, lo, mid);
        mergeSort(t, idx, tmp, mid, hi);
        int i = lo, j = mid, k = lo;
        while (i < mid && j < hi) tmp[k++] = less(t, idx[j], idx[i]) ? idx[j++] : idx[i++];
        while (i < mid) tmp[k++] = idx[i++];
        while (j < hi) tmp[k++] = idx[j++];
        System.arraycopy(tmp, lo, idx, lo, hi - lo);
    }
}
