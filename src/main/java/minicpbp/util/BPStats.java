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
    /**
     * Exact marginal recomputations, i.e. calls to {@code BPGraph.resync}.
     * <p>
     * Split by trigger below, because the four have nothing to do with each
     * other. Two caveats: the count includes the
     * {@code id == null || stampOfId[id] != stamp} path of
     * {@code BPGraph.resync}, which only normalises; and it is structurally 0
     * under {@code flood}, which never calls {@code updateMessagesInPlace} and
     * rebuilds every marginal from scratch on every sweep instead
     * (BP_BASELINE_REPAIR.md section 1.3). It cannot be used to compare
     * flooding against the other schedules.
     */
    public static long marginalResyncs;
    /** resyncs because a message that was zero became nonzero */
    public static long resyncResurrected;
    /** resyncs because the rebuilt marginal had no mass left. Structurally 0
     *  since 2026-08-25: a zero product is the exact marginal, see
     *  zeroMassLeftAlone. Kept so old and new runs can be compared. */
    public static long resyncZeroMass;
    /** resyncs because two scope positions are views of one variable */
    public static long resyncDuplicateScope;
    /** resyncs repairing a uniform-cavity fallback, which drops every other
     *  factor's message from the product (BP_WARM_START_EXPERIMENT.md D4) */
    public static long resyncCavityFallback;
    /** cavity distributions replaced by uniform because the quotient left the
     *  representable range. Before 2026-08-25 this also counted the all-zero
     *  quotient, which is not a failure at all: see cavityExactZero. */
    public static long cavityFallbacks;
    /** cavity distributions that came out zero on every value, which is the
     *  exact answer -- every value of the variable is excluded by some factor
     *  other than this one -- and needs no repair (BP_COST_PROFILE.md Lever B) */
    public static long cavityExactZero;
    /** publications whose product carried no mass and were left alone, the
     *  zero being the exact marginal. These were resyncZeroMass before
     *  2026-08-25. */
    public static long zeroMassLeftAlone;
    /** rescalings of a variable's zero-aware product, to keep it off the
     *  underflow floor (SparseSetDomain.NZ_FLOOR) */
    public static long nzRescales;
    /** zero-aware products that underflowed to zero anyway, so the cavity had to
     *  be reported unusable and the marginal rebuilt */
    public static long nzUnusable;
    /** schedules built (a rebuild is one dependency-graph construction) */
    public static long schedulesBuilt;
    /** schedules reused without rebuilding */
    public static long schedulesReused;
    /** nanoseconds spent inside beliefPropa() */
    public static long bpNanos;
    /** nanoseconds spent building schedules (included in bpNanos) */
    public static long scheduleNanos;

    /* warm start: re-establishing b(v) = prod_c local_c(v) at invocation entry */
    /** entry passes run (one per warm invocation) */
    public static long warmEntryRebuilds;
    /** nanoseconds spent in them (included in bpNanos) */
    public static long warmEntryNanos;
    /** entry passes that found a variable with no mass left and fell back to a
     *  full cold reset, which is the only invariant-preserving repair */
    public static long warmEntryColdFallbacks;

    /* incremental dirty seeding */
    /** factors seeded dirty at entry, summed over invocations */
    public static long dirtySeeded;
    /** factors in the graph at entry, summed over invocations: the denominator */
    public static long dirtySeedFactors;
    /** invocations that had to seed everything (first on the path, or a cold
     *  fallback), so the ratio above is not read as a steady state */
    public static long dirtySeedFull;

    /* the root damping tuning, which runs inside the timed region and outside
     * the monitored loop, and with the reuse gate on can be most of all BP work */
    public static long tuneDampingTrials;
    public static long tuneDampingSweeps;
    public static long tuneDampingNanos;

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
                + " bpResyncResurrected=" + resyncResurrected
                + " bpResyncZeroMass=" + resyncZeroMass
                + " bpResyncDuplicateScope=" + resyncDuplicateScope
                + " bpResyncCavityFallback=" + resyncCavityFallback
                + " bpCavityFallbacks=" + cavityFallbacks
                + " bpCavityExactZero=" + cavityExactZero
                + " bpZeroMassLeftAlone=" + zeroMassLeftAlone
                + " bpNzRescales=" + nzRescales
                + " bpNzUnusable=" + nzUnusable
                + " bpWarmEntryRebuilds=" + warmEntryRebuilds
                + " bpWarmEntryMs=" + (warmEntryNanos / 1000000)
                + " bpWarmEntryColdFallbacks=" + warmEntryColdFallbacks
                + " bpDirtySeeded=" + dirtySeeded
                + " bpDirtySeedFraction=" + (dirtySeedFactors == 0 ? 0.0 : (double) dirtySeeded / dirtySeedFactors)
                + " bpDirtySeedFull=" + dirtySeedFull
                + " bpTuneDampingTrials=" + tuneDampingTrials
                + " bpTuneDampingSweeps=" + tuneDampingSweeps
                + " bpTuneDampingMs=" + (tuneDampingNanos / 1000000)
                + " bpSchedulesBuilt=" + schedulesBuilt
                + " bpSchedulesReused=" + schedulesReused
                + " bpMs=" + (bpNanos / 1000000)
                + " bpScheduleMs=" + (scheduleNanos / 1000000)
                + " bpScheduleShare=" + (bpNanos == 0 ? 0.0 : (double) scheduleNanos / bpNanos)
                + " bpWarmEntryShare=" + (bpNanos == 0 ? 0.0 : (double) warmEntryNanos / bpNanos)
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
