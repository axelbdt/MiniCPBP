/*
 * mini-cpbp, replacing classic propagation by belief propagation
 *
 * Aggregate counters for the AllDifferentDC counting routines, dumped at JVM
 * exit to the file named by -Dminicpbp.alldiff.stats.
 * Added 2026-08-16 (see IMPLEMENTATION_LOG.md).
 */

package minicpbp.util;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;

public final class AllDiffStats {

    public static long updateBeliefCalls;
    public static long exactCalls;
    public static long approxCalls;
    public static long bpCalls;
    public static long bpIterations;
    public static long bpConverged;
    public static long bpFallbacks;      // BP output rejected, Soules used instead
    public static long bpClamps;         // messages clamped at MU_MAX
    public static long bpCancelRecomputes;
    public static long bpNonFinite;
    public static long bpEdges;
    public static long soulesCalls;

    private static boolean installed = false;

    private AllDiffStats() {
    }

    public static synchronized void install() {
        if (installed) return;
        installed = true;
        Runtime.getRuntime().addShutdownHook(new Thread(AllDiffStats::dump));
    }

    public static String line() {
        return "config=[" + AllDiffConfig.describe() + "]"
                + " updateBeliefCalls=" + updateBeliefCalls
                + " exactCalls=" + exactCalls
                + " approxCalls=" + approxCalls
                + " exactFraction=" + (updateBeliefCalls == 0 ? 0.0 : (double) exactCalls / updateBeliefCalls)
                + " bpCalls=" + bpCalls
                + " bpIterations=" + bpIterations
                + " bpMeanIters=" + (bpCalls == 0 ? 0.0 : (double) bpIterations / bpCalls)
                + " bpConvergedFraction=" + (bpCalls == 0 ? 0.0 : (double) bpConverged / bpCalls)
                + " bpFallbacks=" + bpFallbacks
                + " bpFallbackRate=" + (bpCalls == 0 ? 0.0 : (double) bpFallbacks / bpCalls)
                + " bpClamps=" + bpClamps
                + " bpCancelRecomputes=" + bpCancelRecomputes
                + " bpNonFinite=" + bpNonFinite
                + " bpEdges=" + bpEdges
                + " soulesCalls=" + soulesCalls;
    }

    public static void dump() {
        if (AllDiffConfig.STATS_FILE.isEmpty()) return;
        try (BufferedWriter w = new BufferedWriter(new FileWriter(AllDiffConfig.STATS_FILE, true))) {
            w.write(line());
            w.write('\n');
        } catch (IOException e) {
            System.err.println("alldiff stats: " + e.getMessage());
        }
    }
}
