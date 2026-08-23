/*
 * mini-cpbp: instrumentation of the belief-propagation engine
 * (BP_SCHEDULING.md P0.2 "instrument BP work").
 *
 * The metric that matters for the scheduling question is the number of
 * weighted-counting executions per search decision, i.e. factorUpdates/nodes,
 * so factor updates are counted separately from sweeps and from invocations.
 *
 * All counters are plain statics: one JVM runs one configuration on one
 * instance (exp.Bench), and BP is single-threaded.
 */

package minicpbp.util;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;

public final class BPStats {

    /** calls to Solver.beliefPropa() */
    public static long calls;
    /** calls that actually ran BP (domains changed by more than the threshold) */
    public static long invocations;
    /** sweeps executed, one sweep = one pass over the scheduled factors */
    public static long sweeps;
    /** factor updates = calls to updateBelief() from the schedule */
    public static long factorUpdates;
    /** factor updates skipped because the factor was clean (residual below tolerance) */
    public static long factorSkipsClean;
    /** factor updates skipped because the factor cannot influence any query marginal */
    public static long factorSkipsPruned;
    /** factor updates skipped because at most one variable of the scope is unbound,
     *  so the outgoing message cannot change during this invocation */
    public static long factorSkipsFrozen;
    /** exact marginal recomputations forced by a zero-valued incoming message */
    public static long marginalResyncs;
    /** schedules built (a rebuild is one dependency-graph construction) */
    public static long schedulesBuilt;
    /** schedules reused without rebuilding */
    public static long schedulesReused;
    /** nanoseconds spent inside beliefPropa() */
    public static long bpNanos;
    /** nanoseconds spent building schedules (included in bpNanos) */
    public static long scheduleNanos;

    /* structure of the last schedule built, for the diagnostic line */
    public static int lastFactors;
    public static int lastVars;
    public static int lastAcyclicFactors;
    public static int lastCyclicFactors;
    public static int lastComponents;

    /* structure summed over every schedule built: how far search decomposes the
     * factor graph, which is the quantity the topology-aware schedule trades on */
    public static long sumFactors;
    public static long sumMessageNodes;
    public static long sumAcyclicFactors;
    public static long sumFrozenFactors;
    public static long sumPrunedFactors;
    public static long sumComponents;

    private BPStats() {
    }

    private static boolean installed = false;

    public static synchronized void install() {
        if (installed) return;
        installed = true;
        if (!BPConfig.STATS_FILE.isEmpty()) {
            Runtime.getRuntime().addShutdownHook(new Thread(BPStats::dump));
        }
    }

    public static String line() {
        return "bp=[" + BPConfig.describe() + "]"
                + " bpCalls=" + calls
                + " bpInvocations=" + invocations
                + " bpTriggerRate=" + (calls == 0 ? 0.0 : (double) invocations / calls)
                + " bpSweeps=" + sweeps
                + " bpSweepsPerInvocation=" + (invocations == 0 ? 0.0 : (double) sweeps / invocations)
                + " bpFactorUpdates=" + factorUpdates
                + " bpUpdatesPerInvocation=" + (invocations == 0 ? 0.0 : (double) factorUpdates / invocations)
                + " bpSkipClean=" + factorSkipsClean
                + " bpSkipPruned=" + factorSkipsPruned
                + " bpSkipFrozen=" + factorSkipsFrozen
                + " bpMarginalResyncs=" + marginalResyncs
                + " bpSchedulesBuilt=" + schedulesBuilt
                + " bpSchedulesReused=" + schedulesReused
                + " bpMs=" + (bpNanos / 1000000)
                + " bpScheduleMs=" + (scheduleNanos / 1000000)
                + " bpLastFactors=" + lastFactors
                + " bpLastVars=" + lastVars
                + " bpLastAcyclic=" + lastAcyclicFactors
                + " bpLastCyclic=" + lastCyclicFactors
                + " bpLastComponents=" + lastComponents
                + " bpMeanFactors=" + per(sumFactors)
                + " bpMeanMessageNodes=" + per(sumMessageNodes)
                + " bpMeanAcyclicFraction=" + (sumFactors == 0 ? 0.0 : (double) sumAcyclicFactors / sumFactors)
                + " bpMeanFrozenFraction=" + (sumFactors == 0 ? 0.0 : (double) sumFrozenFactors / sumFactors)
                + " bpMeanPrunedFraction=" + (sumFactors == 0 ? 0.0 : (double) sumPrunedFactors / sumFactors)
                + " bpMeanComponents=" + per(sumComponents);
    }

    private static double per(long total) {
        return schedulesBuilt == 0 ? 0.0 : (double) total / schedulesBuilt;
    }

    public static void dump() {
        if (BPConfig.STATS_FILE.isEmpty()) return;
        try (BufferedWriter w = new BufferedWriter(new FileWriter(BPConfig.STATS_FILE, true))) {
            w.write(line());
            w.write('\n');
        } catch (IOException e) {
            System.err.println("bp stats: " + e.getMessage());
        }
    }
}
