/*
 * mini-cpbp, replacing classic propagation by belief propagation
 *
 * gcc Phase 1 instrumentation (2026-08-18, GCC_EXPERIMENT.md §5): samples the
 * collapsed belief systems that CardinalityDC.updateBelief() builds while
 * solving. A system is the n x (k+1) class-collapsed weight matrix (k counted
 * classes + the "other" class), the occurrence bounds low/up, and the
 * occurrence-variable outside beliefs.
 *
 * Reservoir sampling per bucket of log2(state space) keeps the sample uniform
 * over the run. Written out by a JVM shutdown hook.
 *
 * File format (plain text, one record per system):
 *   G <tag> <n> <k> <constraintName>
 *   L <low_1> ... <low_k>
 *   U <up_1> ... <up_k>
 *   V <val_1> ... <val_k>            (optional: the counted value of each class)
 *   S <self_1> ... <self_k>          (optional: self_j = i if the occurrence
 *                                     variable of class j IS x[i], else -1 —
 *                                     the MagicSequence shape; needed by the
 *                                     true self-referential oracle, followup §1.1)
 *   W j <w_j(0)> ... <w_j(up_j)>     (k lines, occurrence beliefs)
 *   <k+1 doubles per row, n rows>    (a[i][0..k-1] then b[i])
 */

package minicpbp.util;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class GccBeliefHarvester {

    private static final int NB_BUCKETS = 64; // bucket = log2(state space), effectively unbounded
    private static final List<Record>[] reservoir = newReservoirs();
    private static final long[] seen = new long[NB_BUCKETS];
    private static final Random rnd = new Random(0xC0FFEE);
    private static boolean installed = false;

    private static final class Record {
        int n, k;
        int[] low, up;
        int[] vals;    // counted value of each class, or null
        int[] selfIdx; // selfIdx[j] = i if o[j] == x[i], else -1; or null
        double[][] wOcc;
        double[][] ab; // n x (k+1)
    }

    @SuppressWarnings("unchecked")
    private static List<Record>[] newReservoirs() {
        List<Record>[] r = new List[NB_BUCKETS];
        for (int i = 0; i < NB_BUCKETS; i++) r[i] = new ArrayList<>();
        return r;
    }

    private GccBeliefHarvester() {
    }

    public static synchronized void offer(int n, int k, double[][] a, double[] b,
                                          int[] low, int[] up, double[][] wOcc,
                                          int[] vals, int[] selfIdx) {
        if (!GccConfig.harvesting() || n < 2 || k < 1) return;
        install();
        double logStates = 0;
        for (int j = 0; j < k; j++) logStates += Math.log(up[j] + 1.0) / Math.log(2.0);
        int bucket = Math.min(NB_BUCKETS - 1, (int) logStates);
        long cnt = seen[bucket]++;
        int cap = GccConfig.HARVEST_PER_BUCKET;
        int slot;
        if (reservoir[bucket].size() < cap) {
            slot = -1;
        } else {
            long r = (long) (rnd.nextDouble() * (cnt + 1));
            if (r >= cap) return;
            slot = (int) r;
        }
        Record rec = new Record();
        rec.n = n;
        rec.k = k;
        rec.low = java.util.Arrays.copyOf(low, k);
        rec.up = java.util.Arrays.copyOf(up, k);
        rec.vals = (vals == null) ? null : java.util.Arrays.copyOf(vals, k);
        rec.selfIdx = (selfIdx == null) ? null : java.util.Arrays.copyOf(selfIdx, k);
        rec.wOcc = new double[k][];
        for (int j = 0; j < k; j++)
            rec.wOcc[j] = (wOcc == null || wOcc[j] == null) ? null : java.util.Arrays.copyOf(wOcc[j], up[j] + 1);
        rec.ab = new double[n][k + 1];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < k; j++) rec.ab[i][j] = a[i][j];
            rec.ab[i][k] = b[i];
        }
        if (slot < 0) reservoir[bucket].add(rec);
        else reservoir[bucket].set(slot, rec);
    }

    private static void install() {
        if (installed) return;
        installed = true;
        Runtime.getRuntime().addShutdownHook(new Thread(GccBeliefHarvester::dump));
    }

    public static synchronized void dump() {
        if (!GccConfig.harvesting()) return;
        try (BufferedWriter w = new BufferedWriter(new FileWriter(GccConfig.HARVEST_FILE, true))) {
            for (int bkt = 0; bkt < NB_BUCKETS; bkt++) {
                for (Record rec : reservoir[bkt]) {
                    w.write("G " + GccConfig.HARVEST_TAG + " " + rec.n + " " + rec.k + " gcc\n");
                    StringBuilder sb = new StringBuilder("L");
                    for (int j = 0; j < rec.k; j++) sb.append(' ').append(rec.low[j]);
                    sb.append("\nU");
                    for (int j = 0; j < rec.k; j++) sb.append(' ').append(rec.up[j]);
                    if (rec.vals != null) {
                        sb.append("\nV");
                        for (int j = 0; j < rec.k; j++) sb.append(' ').append(rec.vals[j]);
                    }
                    if (rec.selfIdx != null) {
                        sb.append("\nS");
                        for (int j = 0; j < rec.k; j++) sb.append(' ').append(rec.selfIdx[j]);
                    }
                    sb.append('\n');
                    w.write(sb.toString());
                    for (int j = 0; j < rec.k; j++) {
                        sb.setLength(0);
                        sb.append("W ").append(j);
                        if (rec.wOcc[j] == null) {
                            for (int c = 0; c <= rec.up[j]; c++)
                                sb.append(' ').append(c >= rec.low[j] ? "1.0" : "0.0");
                        } else {
                            for (int c = 0; c <= rec.up[j]; c++)
                                sb.append(' ').append(Double.toString(rec.wOcc[j][c]));
                        }
                        sb.append('\n');
                        w.write(sb.toString());
                    }
                    for (int i = 0; i < rec.n; i++) {
                        sb.setLength(0);
                        for (int j = 0; j <= rec.k; j++) {
                            if (j > 0) sb.append(' ');
                            sb.append(Double.toString(rec.ab[i][j]));
                        }
                        sb.append('\n');
                        w.write(sb.toString());
                    }
                }
                reservoir[bkt].clear();
            }
        } catch (IOException e) {
            System.err.println("gcc harvester: " + e.getMessage());
        }
    }
}
