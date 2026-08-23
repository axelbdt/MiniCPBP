/*
 * mini-cpbp, replacing belief propagation's flooding schedule by a
 * schedulable one (BP_SCHEDULING.md P1-P7, BP_SCHEDULING_TODO.md).
 *
 * Everything defaults to the flooding behaviour MiniCPBP had before this
 * change, so an unconfigured run is the previous solver bit for bit.
 *
 * System properties
 * -----------------
 *  minicpbp.bp.schedule   flood | seq | seqfb | topo | residual
 *                         (default flood = the historical Jacobi sweep:
 *                          every active constraint receives, all marginals
 *                          are reset, every active constraint sends)
 *      seq       Gauss-Seidel: one factor at a time, its outgoing messages
 *                published into the marginals immediately, posting order
 *      seqfb     seq with alternating forward/backward sweeps
 *      topo      Infer.NET-style static schedule on the effective factor
 *                graph of the search node: spanning forest, leaves-to-root
 *                then root-to-leaves, with dirty gating and early exit. Two
 *                passes are exact wherever the graph is acyclic, and on a
 *                cyclic component the order leaves one stale message per
 *                independent cycle, which is the minimum
 *      residual  asynchronous priority scheduling on message residuals,
 *                seeded with the topo order
 *  minicpbp.bp.warmStart  true | false  (default false)
 *                         keep the restored marginals/local beliefs when BP
 *                         is triggered instead of resetting them to uniform
 *                         (next.md P1a).
 *  minicpbp.bp.queryOnly  true | false  (default false)
 *                         drop message computations that cannot reach the
 *                         marginal of a declared branching variable
 *                         (Infer.NET OptimiseForVariables; topo/residual only)
 *  minicpbp.bp.residualTol  double (default 1e-6)
 *                         residual below which a factor is considered clean
 *                         and is not re-executed
 *  minicpbp.bp.stableDecisionSweeps  int (default 0 = off)
 *                         stop the loop once the variable/value a min-entropy
 *                         heuristic would branch on has been the same for this
 *                         many consecutive sweeps. The shipped rule stops when
 *                         SOME variable's entropy drops below 1e-3, which
 *                         rewards whichever schedule is most overconfident
 *                         rather than the one whose decision has settled
 *                         (BP_SCHEDULING.md section 1.4)
 *  minicpbp.bp.stats      file to append per-run BP counters to
 *  minicpbp.bp.dumpGraph  true | false  (default false)
 *                         print the factor-graph structure of the first real
 *                         BP invocation (sizes, 2-core, components) and exit
 *                         nothing; diagnostic only
 */

package minicpbp.util;

public final class BPConfig {

    public enum Schedule {FLOOD, SEQ, SEQFB, TOPO, RESIDUAL}

    public static final Schedule SCHEDULE;
    /** not final: exp.SchedBench flips it to measure how far from a fixed point
     *  a schedule stopped, by continuing from the marginals it produced */
    public static boolean WARM_START;
    public static final boolean QUERY_ONLY;
    public static final double RESIDUAL_TOL;
    public static final String STATS_FILE;
    public static final boolean DUMP_GRAPH;
    /** ignore the engine's entropy-based early stops, so that a fixed sweep
     *  budget means the same work for every schedule (measurement only) */
    public static final boolean NO_EARLY_STOP;
    /** stop once the branching decision has been the same for this many
     *  consecutive sweeps; 0 disables it (BP_SCHEDULING.md section 1.4) */
    public static final int STABLE_DECISION_SWEEPS;

    static {
        SCHEDULE = Schedule.valueOf(System.getProperty("minicpbp.bp.schedule", "flood").toUpperCase());
        WARM_START = Boolean.parseBoolean(System.getProperty("minicpbp.bp.warmStart", "false"));
        NO_EARLY_STOP = Boolean.parseBoolean(System.getProperty("minicpbp.bp.noEarlyStop", "false"));
        STABLE_DECISION_SWEEPS = Integer.parseInt(System.getProperty("minicpbp.bp.stableDecisionSweeps", "0"));
        QUERY_ONLY = Boolean.parseBoolean(System.getProperty("minicpbp.bp.queryOnly", "false"));
        RESIDUAL_TOL = Double.parseDouble(System.getProperty("minicpbp.bp.residualTol", "1e-6"));
        STATS_FILE = System.getProperty("minicpbp.bp.stats", "");
        DUMP_GRAPH = Boolean.parseBoolean(System.getProperty("minicpbp.bp.dumpGraph", "false"));
    }

    private BPConfig() {
    }

    /** true when the schedule needs the in-place (Gauss-Seidel) factor update */
    public static boolean inPlace() {
        return SCHEDULE != Schedule.FLOOD;
    }

    public static String describe() {
        return "schedule=" + SCHEDULE + " warmStart=" + WARM_START + " queryOnly=" + QUERY_ONLY
                + " residualTol=" + RESIDUAL_TOL + " stableDecisionSweeps=" + STABLE_DECISION_SWEEPS;
    }
}
