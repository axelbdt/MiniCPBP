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

    /** Called when a new invocation starts, before the first sweep. */
    void beginInvocation();

    interface SweepMonitor {
        boolean afterSweep(int sweep);
    }
}
