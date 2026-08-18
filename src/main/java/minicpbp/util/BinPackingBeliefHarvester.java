/*
 * mini-cpbp, replacing classic propagation by belief propagation
 *
 * bin_packing belief-system harvester (2026-08-18, round 4,
 * BINPACKING_EXPERIMENT.md): samples the item x bin weight matrices that
 * BinPackingCounting.updateBelief() builds while solving, exactly as
 * GccBeliefHarvester does for gcc. A system is the n x m weight matrix, the
 * item sizes, the load bounds low/up, and the load-variable outside beliefs.
 *
 * Reservoir sampling per bucket of log2(state space) keeps the sample uniform
 * over the run. Written out by a JVM shutdown hook.
 *
 * File format (plain text, one record per system):
 *   P <tag> <n> <m> <constraintName>
 *   Z <size_1> ... <size_n>
 *   L <low_1> ... <low_m>
 *   U <up_1> ... <up_m>
 *   W j <w_j(0)> ... <w_j(up_j)>     (m lines, load beliefs)
 *   <m doubles per row, n rows>      (a[i][0..m-1])
 */

package minicpbp.util;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class BinPackingBeliefHarvester {

    private static final int NB_BUCKETS = 64;
    private static final List<Record>[] reservoir = newReservoirs();
    private static final long[] seen = new long[NB_BUCKETS];
    private static final Random rnd = new Random(0xB1BBED);
    private static boolean installed = false;

    private static final class Record {
        int n, m;
        int[] size;
        int[] low, up;
        double[][] wLoad;
        double[][] a; // n x m
    }

    @SuppressWarnings("unchecked")
    private static List<Record>[] newReservoirs() {
        List<Record>[] r = new List[NB_BUCKETS];
        for (int i = 0; i < NB_BUCKETS; i++) r[i] = new ArrayList<>();
        return r;
    }

    private BinPackingBeliefHarvester() {
    }

    public static synchronized void offer(int n, int m, int[] size, double[][] a,
                                          int[] low, int[] up, double[][] wLoad) {
        if (!BinPackingConfig.harvesting() || n < 2 || m < 1) return;
        install();
        double logStates = 0;
        for (int j = 0; j < m; j++) logStates += Math.log(up[j] + 1.0) / Math.log(2.0);
        int bucket = Math.min(NB_BUCKETS - 1, (int) logStates);
        long cnt = seen[bucket]++;
        int cap = BinPackingConfig.HARVEST_PER_BUCKET;
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
        rec.m = m;
        rec.size = java.util.Arrays.copyOf(size, n);
        rec.low = java.util.Arrays.copyOf(low, m);
        rec.up = java.util.Arrays.copyOf(up, m);
        rec.wLoad = new double[m][];
        for (int j = 0; j < m; j++)
            rec.wLoad[j] = (wLoad == null || wLoad[j] == null) ? null : java.util.Arrays.copyOf(wLoad[j], up[j] + 1);
        rec.a = new double[n][m];
        for (int i = 0; i < n; i++)
            System.arraycopy(a[i], 0, rec.a[i], 0, m);
        if (slot < 0) reservoir[bucket].add(rec);
        else reservoir[bucket].set(slot, rec);
    }

    private static void install() {
        if (installed) return;
        installed = true;
        Runtime.getRuntime().addShutdownHook(new Thread(BinPackingBeliefHarvester::dump));
    }

    public static synchronized void dump() {
        if (!BinPackingConfig.harvesting()) return;
        try (BufferedWriter w = new BufferedWriter(new FileWriter(BinPackingConfig.HARVEST_FILE, true))) {
            for (int bkt = 0; bkt < NB_BUCKETS; bkt++) {
                for (Record rec : reservoir[bkt]) {
                    w.write("P " + BinPackingConfig.HARVEST_TAG + " " + rec.n + " " + rec.m + " binpacking\n");
                    StringBuilder sb = new StringBuilder("Z");
                    for (int i = 0; i < rec.n; i++) sb.append(' ').append(rec.size[i]);
                    sb.append("\nL");
                    for (int j = 0; j < rec.m; j++) sb.append(' ').append(rec.low[j]);
                    sb.append("\nU");
                    for (int j = 0; j < rec.m; j++) sb.append(' ').append(rec.up[j]);
                    sb.append('\n');
                    w.write(sb.toString());
                    for (int j = 0; j < rec.m; j++) {
                        sb.setLength(0);
                        sb.append("W ").append(j);
                        if (rec.wLoad[j] == null) {
                            for (int c = 0; c <= rec.up[j]; c++)
                                sb.append(' ').append(c >= rec.low[j] ? "1.0" : "0.0");
                        } else {
                            for (int c = 0; c <= rec.up[j]; c++)
                                sb.append(' ').append(Double.toString(rec.wLoad[j][c]));
                        }
                        sb.append('\n');
                        w.write(sb.toString());
                    }
                    for (int i = 0; i < rec.n; i++) {
                        sb.setLength(0);
                        for (int j = 0; j < rec.m; j++) {
                            if (j > 0) sb.append(' ');
                            sb.append(Double.toString(rec.a[i][j]));
                        }
                        sb.append('\n');
                        w.write(sb.toString());
                    }
                }
                reservoir[bkt].clear();
            }
        } catch (IOException e) {
            System.err.println("binpacking harvester: " + e.getMessage());
        }
    }
}
