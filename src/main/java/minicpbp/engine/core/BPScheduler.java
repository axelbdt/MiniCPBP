/*
 * mini-cpbp: belief-propagation scheduling (BP_SCHEDULING.md phase 0.1).
 *
 * The historical engine hard-coded one schedule: a synchronous Jacobi sweep in
 * which every active constraint reads all of its incoming messages, every
 * marginal is reset, and every active constraint writes all of its outgoing
 * messages. Information produced by one factor could therefore not reach a
 * neighbouring factor before the next sweep.
 *
 * This interface separates the schedule from the engine, so that the flooding
 * sweep stays available as the reference implementation while other schedules
 * are measured against it (BP_SCHEDULING.md section 10, the scheduler ladder).
 */

package minicpbp.engine.core;

public interface BPScheduler {

    /** identifies the schedule in traces and statistics */
    String name();

    /**
     * Runs one belief-propagation invocation on the current factor graph.
     *
     * @param maxSweeps upper bound on the number of sweeps, a sweep being one
     *                  pass over the scheduled factors: the unit in which the
     *                  solver's iteration budget is expressed
     * @param monitor   called after every sweep; returns true to stop. The
     *                  engine uses it to recompute entropies, to log, and to
     *                  apply its stopping criteria.
     */
    void run(int maxSweeps, SweepMonitor monitor);

    /**
     * Called when a new invocation starts, before the first sweep.
     *
     * @param fullDirty when true the schedule must treat every factor as stale,
     *                  whatever it may know about what changed. The engine sets
     *                  it when the invocation cannot inherit anything: a cold
     *                  reset, or a warm entry that had to fall back to one.
     */
    void beginInvocation(boolean fullDirty);

    /**
     * Called once after the last sweep of an invocation, on the normal exit
     * path only. A schedule that gates on staleness records here what it left
     * un-executed, so the next invocation on this path can pick it up; an
     * exception path deliberately records nothing, since the node is about to
     * be backtracked and the conservative answer is "still stale".
     */
    default void endInvocation() {
    }

    interface SweepMonitor {
        boolean afterSweep(int sweep);
    }
}
