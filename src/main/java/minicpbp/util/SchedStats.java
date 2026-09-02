/*
 * mini-cpbp, replacing classic propagation by belief propagation
 *
 * Process-wide counters for the Disjunctive / Cumulative counting routines
 * (DISJUNCTIVE_CUMULATIVE_PLAN.md §1.4). One JVM = one arm, so plain statics
 * are the right scope; exp.Bench appends line() to its .stats row.
 */

package minicpbp.util;

public final class SchedStats {

    /** updateBelief calls on counting-enabled Cumulative objects under a non-uniform routine. */
    public static long calls;
    /** calls served by the time-table mean-field routine (including fallbacks). */
    public static long timetableCalls;
    /** calls served by the nested BP. */
    public static long bpCalls;
    /** nested-BP sweeps actually executed. */
    public static long bpSweeps;
    /** nested-BP calls that stopped on the stability test. */
    public static long bpConverged;
    /** auto: calls whose per-sweep operation count exceeded the budget (served by timetable). */
    public static long bpDeclined;
    /** bp calls that hit NaN/Inf and fell back to timetable. */
    public static long bpNumericalFallbacks;
    /** calls that found a demand above the capacity and threw. */
    public static long inconsistent;
    /** nanoseconds inside the counting routines (collapse + engine + emit). */
    public static long nanos;
    /** per-sweep operation count of the largest system seen (sum_t |A_t| (C+1)). */
    public static long maxOpsPerSweep;

    private SchedStats() {
    }

    public static String line() {
        return "schedCalls=" + calls
                + " schedTimetable=" + timetableCalls
                + " schedBp=" + bpCalls
                + " schedBpSweeps=" + bpSweeps
                + " schedBpConverged=" + bpConverged
                + " schedBpDeclined=" + bpDeclined
                + " schedBpNumFallback=" + bpNumericalFallbacks
                + " schedInconsistent=" + inconsistent
                + " schedMs=" + (nanos / 1000000)
                + " schedMaxOps=" + maxOpsPerSweep;
    }

    public static void reset() {
        calls = timetableCalls = bpCalls = bpSweeps = bpConverged = bpDeclined = 0;
        bpNumericalFallbacks = inconsistent = nanos = maxOpsPerSweep = 0;
    }
}
