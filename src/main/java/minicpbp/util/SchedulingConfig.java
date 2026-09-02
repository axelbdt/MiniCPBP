/*
 * mini-cpbp, replacing classic propagation by belief propagation
 *
 * Experiment configuration for the Disjunctive / Cumulative weighted-counting
 * experiment (DISJUNCTIVE_CUMULATIVE_PLAN.md, 2026-09-02). Modelled on
 * BinPackingConfig: everything defaults to the behaviour MiniCPBP had before
 * the experiment, so an unconfigured run is the pre-experiment solver bit for
 * bit.
 *
 * System properties
 * -----------------
 *  minicpbp.sched.belief        uniform | timetable | bp | auto   (default uniform)
 *                               uniform:   the AbstractConstraint default (control,
 *                                          today's behaviour)
 *                               timetable: mean-field product over the residual
 *                                          capacity profile built from the other
 *                                          jobs' compulsory parts (brief §4D)
 *                               bp:        time-indexed nested BP, per-slot
 *                                          knapsack factors (brief §4A), never
 *                                          declined on cost
 *                               auto:      bp when the per-sweep operation count
 *                                          fits opsBudget, timetable otherwise
 *  minicpbp.sched.bpIters       nested-BP hard sweep cap (default 5)
 *  minicpbp.sched.bpBlock       block width k of the nested BP's capacity
 *                               factors (default 1 = one factor per time slot,
 *                               the CumulativeBP engine of the first pilots;
 *                               k >= 2 uses CumulativeBlockBP, one factor per
 *                               k consecutive slots). Amendment A3.
 *  minicpbp.sched.bpEps         stability threshold on the max total-variation
 *                               change of the solver-facing beliefs between two
 *                               sweeps (default 0.01; <= 0 disables early exit)
 *  minicpbp.sched.bpMinSweeps   minimum sweeps before the stability test may
 *                               fire (default 2; this BP always cold-starts)
 *  minicpbp.sched.opsBudget     auto only: per-sweep budget on
 *                               sum_t |A_t| (C+1) over the non-trivial slots
 *                               (default 2000000)
 *  minicpbp.sched.bpLean        true | false (default false): keep the
 *                               filtering-only objects of a posting (mirror
 *                               copies, the Disjunctive objects themselves,
 *                               the reified pairwise block) out of the BP
 *                               graph. They still propagate. Amendment A2.
 *  minicpbp.sched.disjunctivePairwise  true | false (default true): whether
 *                               Disjunctive.post() materialises the reified
 *                               pairwise block (two IsLessOrEqualVar + notEqual
 *                               per pair). True is today's posting.
 */

package minicpbp.util;

public final class SchedulingConfig {

    public enum BeliefRoutine {UNIFORM, TIMETABLE, BP, AUTO}

    public static final BeliefRoutine BELIEF;
    public static final int BP_ITERS;
    /** amendment A3: block width of the nested BP's factors; 1 is the slot engine. */
    public static final int BP_BLOCK;
    public static final double BP_EPS;
    public static final int BP_MIN_SWEEPS;
    public static final long OPS_BUDGET;
    public static final boolean DISJUNCTIVE_PAIRWISE;
    /**
     * amendment A2: the filtering-only objects of a scheduling posting (the
     * mirror Cumulative, both Disjunctive objects, the reified pairwise
     * block when posted) are kept out of the BP graph; they still propagate.
     */
    public static final boolean BP_LEAN;

    static {
        BELIEF = BeliefRoutine.valueOf(System.getProperty("minicpbp.sched.belief", "uniform").toUpperCase());
        BP_ITERS = Integer.parseInt(System.getProperty("minicpbp.sched.bpIters", "5"));
        BP_BLOCK = Integer.parseInt(System.getProperty("minicpbp.sched.bpBlock", "1"));
        BP_EPS = Double.parseDouble(System.getProperty("minicpbp.sched.bpEps", "0.01"));
        BP_MIN_SWEEPS = Integer.parseInt(System.getProperty("minicpbp.sched.bpMinSweeps",
                Integer.toString(CumulativeBP.DEFAULT_MIN_SWEEPS)));
        OPS_BUDGET = Long.parseLong(System.getProperty("minicpbp.sched.opsBudget", "2000000"));
        DISJUNCTIVE_PAIRWISE = Boolean.parseBoolean(System.getProperty("minicpbp.sched.disjunctivePairwise", "true"));
        BP_LEAN = Boolean.parseBoolean(System.getProperty("minicpbp.sched.bpLean", "false"));
    }

    private SchedulingConfig() {
    }

    public static String describe() {
        return "sched.belief=" + BELIEF
                + " bpIters=" + BP_ITERS
                + " bpBlock=" + BP_BLOCK
                + " bpEps=" + BP_EPS
                + " bpMinSweeps=" + BP_MIN_SWEEPS
                + " opsBudget=" + OPS_BUDGET
                + " disjunctivePairwise=" + DISJUNCTIVE_PAIRWISE
                + " bpLean=" + BP_LEAN;
    }
}
