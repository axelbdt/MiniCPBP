/*
 * mini-cpbp, replacing classic propagation by belief propagation
 *
 * Phase 1 instrumentation: samples the outside-belief matrices that
 * AllDifferentDC.updateBelief() actually builds while solving, so that the
 * Soules / BP / exact comparison runs on real BP traffic rather than on
 * synthetic random matrices.
 *
 * Reservoir sampling per nbVal bucket keeps the sample uniform over the whole
 * run instead of over-representing the top of the search tree, and bounds
 * memory. The reservoir is written out by a JVM shutdown hook.
 *
 * File format (plain text, one record per matrix):
 *   M <tag> <nbVar> <nbVal> <constraintName>
 *   <nbVal doubles>            (row 0)
 *   ...                        (nbVar rows in total; dummy rows are NOT
 *                               stored, they are reconstructible as 1/nbVal)
 */

package minicpbp.util;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public final class BeliefMatrixHarvester {

    private static final int NB_BUCKETS = 13; // bucket b holds nbVal in [2b, 2b+1], up to nbVal = 25
    private static final List<double[]>[] reservoir = newReservoirs();
    private static final long[] seen = new long[NB_BUCKETS];
    private static final Random rnd = new Random(0xC0FFEE);
    private static boolean installed = false;

    @SuppressWarnings("unchecked")
    private static List<double[]>[] newReservoirs() {
        List<double[]>[] r = new List[NB_BUCKETS];
        for (int i = 0; i < NB_BUCKETS; i++) r[i] = new ArrayList<>();
        return r;
    }

    private BeliefMatrixHarvester() {
    }

    private static int bucketOf(int nbVal) {
        int b = nbVal / 2;
        return (b >= 0 && b < NB_BUCKETS) ? b : -1;
    }

    /**
     * Offers one matrix to the sampler.
     *
     * @param beliefs the padded matrix; only rows [0,nbVar) columns [0,nbVal) are read
     */
    public static synchronized void offer(double[][] beliefs, int nbVar, int nbVal, String constraintName) {
        if (!AllDiffConfig.harvesting()) return;
        int b = bucketOf(nbVal);
        if (b < 0 || nbVar < 2) return;
        install();
        long k = seen[b]++;
        int cap = AllDiffConfig.HARVEST_PER_BUCKET;
        int slot;
        if (reservoir[b].size() < cap) {
            slot = -1;
        } else {
            long r = (long) (rnd.nextDouble() * (k + 1));
            if (r >= cap) return;
            slot = (int) r;
        }
        double[] flat = new double[2 + nbVar * nbVal];
        flat[0] = nbVar;
        flat[1] = nbVal;
        int p = 2;
        for (int i = 0; i < nbVar; i++)
            for (int j = 0; j < nbVal; j++) flat[p++] = beliefs[i][j];
        if (slot < 0) reservoir[b].add(flat);
        else reservoir[b].set(slot, flat);
    }

    private static void install() {
        if (installed) return;
        installed = true;
        Runtime.getRuntime().addShutdownHook(new Thread(BeliefMatrixHarvester::dump));
    }

    public static synchronized void dump() {
        if (!AllDiffConfig.harvesting()) return;
        try (BufferedWriter w = new BufferedWriter(new FileWriter(AllDiffConfig.HARVEST_FILE, true))) {
            for (int b = 0; b < NB_BUCKETS; b++) {
                for (double[] flat : reservoir[b]) {
                    int nbVar = (int) flat[0];
                    int nbVal = (int) flat[1];
                    w.write("M " + AllDiffConfig.HARVEST_TAG + " " + nbVar + " " + nbVal + " alldiff\n");
                    int p = 2;
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < nbVar; i++) {
                        sb.setLength(0);
                        for (int j = 0; j < nbVal; j++) {
                            if (j > 0) sb.append(' ');
                            sb.append(Double.toString(flat[p++]));
                        }
                        sb.append('\n');
                        w.write(sb.toString());
                    }
                }
                reservoir[b].clear();
            }
        } catch (IOException e) {
            System.err.println("harvester: " + e.getMessage());
        }
    }

    public static synchronized String summary() {
        StringBuilder sb = new StringBuilder("harvest seen/kept per nbVal bucket:");
        for (int b = 0; b < NB_BUCKETS; b++)
            if (seen[b] > 0) sb.append(' ').append(2 * b).append('-').append(2 * b + 1)
                    .append(':').append(seen[b]).append('/').append(reservoir[b].size());
        return sb.toString();
    }
}
