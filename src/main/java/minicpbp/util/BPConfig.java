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
 *                         (next.md P1a). The invariant b(v) = prod_c local_c(v)
 *                         is re-established at invocation entry, whatever the
 *                         schedule (BP_WARM_START_EXPERIMENT.md F1).
 *  minicpbp.bp.incrementalDirty  true | false  (default false)
 *                         seed the dirty set from what changed since the last
 *                         invocation on this path, instead of marking every
 *                         factor dirty. Requires warmStart: the cold reset
 *                         destroys every stored message, so there is nothing
 *                         whose staleness could be tested
 *                         (BP_WARM_START_EXPERIMENT.md section 1.3). Exact,
 *                         not approximate: a skipped factor would have
 *                         recomputed the same message from the same inputs,
 *                         so results must agree bit for bit with full seeding.
 *                         topo and residual only.
 *  minicpbp.bp.updateThreshold  double (default 0.05)
 *                         relative decrease of the summed domain size below
 *                         which BP is skipped and the current marginals are
 *                         reused. 0 triggers BP whenever any domain changed;
 *                         -Dminicpbp.debug.alwaysRunBP bypasses the test
 *                         altogether. NOTE the metric is a global scalar over
 *                         ALL registered variables including auxiliaries, and
 *                         its denominator is a trailed StateInt, i.e. the last
 *                         ancestor on the path that ran BP.
 *  minicpbp.seed          long, the RNG seed. Absent = unseeded (the previous
 *                         default). A seed alone does NOT give determinism:
 *                         the sparse set restores membership but not order, so
 *                         -Dminicpbp.debug.sortDomainValues is also needed.
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
 *                         (BP_SCHEDULING.md section 1.4). Level 2 research by
 *                         BP_SCHEDULING_TODO.md's ladder: not baseline
 *                         furniture. NOTE k means the same decision on k+1
 *                         sweeps, since the counter increments only on a repeat
 *                         and the first sweep compares against nothing.
 *  minicpbp.bp.convergeTol  double (default 0 = off)
 *                         stop once no marginal of an unbound branchable
 *                         variable moved by more than this, in standard
 *                         representation, from one sweep to the next. This is
 *                         the solver's only convergence criterion: the shipped
 *                         rule, the entropy-equality test and problemEntropy()
 *                         == 0 are all statements about confidence, not about
 *                         movement. Separate from residualTol, which is
 *                         ResidualScheduler's internal queue threshold.
 *  minicpbp.bp.stopRule   shipped | fixed | converge | decision
 *                         which stopping rule is in force. At most one is, and
 *                         it REPLACES the others rather than being OR-ed with
 *                         them; problemEntropy() == 0 stays as the correctness
 *                         stop in every mode. Omitted, it is derived from the
 *                         legacy flags (noEarlyStop -> fixed, convergeTol > 0
 *                         -> converge, stableDecisionSweeps > 0 -> decision,
 *                         else shipped) and setting two of them at once is a
 *                         configuration error rather than a silent precedence.
 *  minicpbp.bp.stats      file to append per-run BP counters to
 *  minicpbp.bp.dumpGraph  true | false  (default false)
 *                         print the factor-graph structure of the first real
 *                         BP invocation (sizes, 2-core, components) and exit
 *                         nothing; diagnostic only
 */

package minicpbp.util;

public final class BPConfig {

    public enum Schedule {FLOOD, SEQ, SEQFB, TOPO, RESIDUAL}

    /**
     * The stopping rule in force. Exactly one is, and it replaces the others.
     * <ul>
     * <li>{@code SHIPPED} (R0) — some variable's entropy below 1e-3, or the
     * problem entropy equal to the previous sweep's. Production as it ships.</li>
     * <li>{@code FIXED} (R1) — no early stop at all: the full sweep budget.</li>
     * <li>{@code CONVERGE} (R2) — no branchable marginal moved by more than
     * {@code convergeTol}. The only criterion that is about movement.</li>
     * <li>{@code DECISION} (R3) — the min-entropy decision has been the same for
     * {@code stableDecisionSweeps} consecutive sweeps. Level 2 research.</li>
     * </ul>
     */
    public enum StopRule {SHIPPED, FIXED, CONVERGE, DECISION}

    /**
     * The BP schedule in force. NOT final since amendment 10b: an in-process
     * dovetail (minicpbp.search.DovetailSearch) runs passes with different
     * configurations in one JVM, which a compile-time constant forbids.
     * Written only by {@link #applyPass}; the static initializer below still
     * reads the same property with the same default.
     */
    public static Schedule SCHEDULE;
    /** not final: exp.SchedBench flips it to measure how far from a fixed point
     *  a schedule stopped, by continuing from the marginals it produced */
    public static boolean WARM_START;
    /** seed the dirty set incrementally instead of marking everything dirty;
     *  requires WARM_START (BP_WARM_START_EXPERIMENT.md section 1.3) */
    public static final boolean INCREMENTAL_DIRTY;
    public static final boolean QUERY_ONLY;
    public static final double RESIDUAL_TOL;
    public static final String STATS_FILE;
    public static final boolean DUMP_GRAPH;
    /** ignore the engine's entropy-based early stops, so that a fixed sweep
     *  budget means the same work for every schedule (measurement only) */
    public static final boolean NO_EARLY_STOP;
    /** stop once the branching decision has been the same for this many
     *  consecutive sweeps; 0 disables it (BP_SCHEDULING.md section 1.4) */
    /** not final since amendment 10b (per-pass dovetail configuration) */
    public static int STABLE_DECISION_SWEEPS;
    /** stop once no branchable marginal moves by more than this; 0 disables it */
    public static final double CONVERGE_TOL;
    /** the one stopping rule in force; not final since amendment 10b */
    public static StopRule STOP_RULE;
    /** relative domain-size decrease below which BP is skipped */
    public static final double UPDATE_THRESHOLD;
    /** RNG seed, or null when unseeded (the previous default) */
    public static final Long SEED;
    /**
     * Probe K (BP_PROBE_PROTOCOL.md amendment 7): decision-keyed cross-node
     * trigger. {@code -Dminicpbp.bp.trigger=decision} replaces the 5 %
     * domain-shrink gate with: run BP iff some ACTIVE constraint's scope
     * contains both the tentative decision variable (argmin entropy over the
     * inherited, renormalized marginals) and a variable touched since the
     * last invocation on this path. Uses the {@code bpTouchStamp} machinery,
     * which therefore stamps under this flag as well as under
     * {@code incrementalDirty}. Default false: behaviour unchanged.
     */
    public static final boolean DECISION_TRIGGER;

    static {
        SCHEDULE = Schedule.valueOf(System.getProperty("minicpbp.bp.schedule", "flood").toUpperCase());
        WARM_START = Boolean.parseBoolean(System.getProperty("minicpbp.bp.warmStart", "false"));
        INCREMENTAL_DIRTY = Boolean.parseBoolean(System.getProperty("minicpbp.bp.incrementalDirty", "false"));
        NO_EARLY_STOP = Boolean.parseBoolean(System.getProperty("minicpbp.bp.noEarlyStop", "false"));
        STABLE_DECISION_SWEEPS = Integer.parseInt(System.getProperty("minicpbp.bp.stableDecisionSweeps", "0"));
        CONVERGE_TOL = Double.parseDouble(System.getProperty("minicpbp.bp.convergeTol", "0"));
        QUERY_ONLY = Boolean.parseBoolean(System.getProperty("minicpbp.bp.queryOnly", "false"));
        RESIDUAL_TOL = Double.parseDouble(System.getProperty("minicpbp.bp.residualTol", "1e-6"));
        UPDATE_THRESHOLD = Double.parseDouble(System.getProperty("minicpbp.bp.updateThreshold", "0.05"));
        String s = System.getProperty("minicpbp.seed");
        SEED = (s == null || s.isEmpty()) ? null : Long.valueOf(Long.parseLong(s));
        STATS_FILE = System.getProperty("minicpbp.bp.stats", "");
        DUMP_GRAPH = Boolean.parseBoolean(System.getProperty("minicpbp.bp.dumpGraph", "false"));
        String trig = System.getProperty("minicpbp.bp.trigger", "ship");
        if (!trig.equals("ship") && !trig.equals("decision"))
            throw new IllegalStateException("c minicpbp.bp.trigger must be ship or decision, not " + trig);
        DECISION_TRIGGER = trig.equals("decision");

        // F6: resolve the precedence explicitly. The three legacy flags used to
        // be silently ordered by where their tests sat in the loop body, and
        // NO_EARLY_STOP returned before STABLE_DECISION_SWEEPS was ever read,
        // which made the two mutually exclusive without saying so.
        String rule = System.getProperty("minicpbp.bp.stopRule");
        if (rule != null && !rule.isEmpty()) {
            STOP_RULE = StopRule.valueOf(rule.toUpperCase());
        } else {
            int n = (NO_EARLY_STOP ? 1 : 0) + (CONVERGE_TOL > 0 ? 1 : 0)
                    + (STABLE_DECISION_SWEEPS > 0 ? 1 : 0);
            if (n > 1) {
                throw new IllegalStateException("c at most one stopping rule: noEarlyStop="
                        + NO_EARLY_STOP + " convergeTol=" + CONVERGE_TOL
                        + " stableDecisionSweeps=" + STABLE_DECISION_SWEEPS
                        + " (set -Dminicpbp.bp.stopRule to choose explicitly)");
            }
            STOP_RULE = NO_EARLY_STOP ? StopRule.FIXED
                    : CONVERGE_TOL > 0 ? StopRule.CONVERGE
                    : STABLE_DECISION_SWEEPS > 0 ? StopRule.DECISION
                    : StopRule.SHIPPED;
        }
        if (STOP_RULE == StopRule.CONVERGE && !(CONVERGE_TOL > 0))
            throw new IllegalStateException("c stopRule=converge needs -Dminicpbp.bp.convergeTol > 0");
        if (STOP_RULE == StopRule.DECISION && STABLE_DECISION_SWEEPS <= 0)
            throw new IllegalStateException("c stopRule=decision needs -Dminicpbp.bp.stableDecisionSweeps > 0");
        if (INCREMENTAL_DIRTY && !WARM_START)
            throw new IllegalStateException("c incrementalDirty requires warmStart: the reset destroys "
                    + "the stored messages whose staleness the dirty set tracks");
        if (INCREMENTAL_DIRTY && SCHEDULE != Schedule.TOPO && SCHEDULE != Schedule.RESIDUAL)
            throw new IllegalStateException("c incrementalDirty is only implemented for topo and residual, "
                    + "not " + SCHEDULE);
    }

    private BPConfig() {
    }

    /**
     * The dials one dovetail pass may set (amendment 10b). Everything not
     * named here is a property of the run, not of the pass, and stays as the
     * JVM configured it. The sweep cap lives on the solver
     * ({@code Solver.setMaxIter}) and the discrepancy cap on the search
     * ({@code LDSearch.setSinglePassCap}), so they are not fields here.
     */
    public static final class Pass {
        public final Schedule schedule;
        public final boolean warmStart;
        public final StopRule stopRule;
        public final int stableDecisionSweeps;

        public Pass(Schedule schedule, boolean warmStart, StopRule stopRule,
                    int stableDecisionSweeps) {
            this.schedule = schedule;
            this.warmStart = warmStart;
            this.stopRule = stopRule;
            this.stableDecisionSweeps = stableDecisionSweeps;
        }

        @Override public String toString() {
            return schedule + "/" + (warmStart ? "warm" : "cold") + "/" + stopRule
                    + (stopRule == StopRule.DECISION ? "" + stableDecisionSweeps : "");
        }
    }

    /**
     * Install a pass configuration. The SAME consistency checks the static
     * initializer runs are re-run here: a dovetail must not be able to reach a
     * configuration a JVM could not have been started in.
     *
     * @return true when the schedule changed, i.e. the caller must drop the
     *         solver's cached scheduler ({@code MiniCP.resetScheduler}).
     */
    public static boolean applyPass(Pass p) {
        if (p.stopRule == StopRule.DECISION && p.stableDecisionSweeps <= 0)
            throw new IllegalStateException("c stopRule=decision needs stableDecisionSweeps > 0");
        if (p.stopRule == StopRule.CONVERGE && !(CONVERGE_TOL > 0))
            throw new IllegalStateException("c stopRule=converge needs -Dminicpbp.bp.convergeTol > 0");
        if (INCREMENTAL_DIRTY && !p.warmStart)
            throw new IllegalStateException("c incrementalDirty requires warmStart");
        if (INCREMENTAL_DIRTY && p.schedule != Schedule.TOPO && p.schedule != Schedule.RESIDUAL)
            throw new IllegalStateException("c incrementalDirty is only implemented for topo and residual");
        boolean scheduleChanged = SCHEDULE != p.schedule;
        SCHEDULE = p.schedule;
        WARM_START = p.warmStart;
        STOP_RULE = p.stopRule;
        STABLE_DECISION_SWEEPS = p.stableDecisionSweeps;
        return scheduleChanged;
    }

    /** the current dials, so a pass can be restored or logged */
    public static Pass currentPass() {
        return new Pass(SCHEDULE, WARM_START, STOP_RULE, STABLE_DECISION_SWEEPS);
    }

    /** true when the schedule needs the in-place (Gauss-Seidel) factor update */
    public static boolean inPlace() {
        return SCHEDULE != Schedule.FLOOD;
    }

    /**
     * The complete configuration, printed once per JVM into the stats file. A
     * run whose log does not record whether early stopping was disabled is not
     * reproducible, so every switch that changes what BP computes is here.
     */
    public static String describe() {
        return "schedule=" + SCHEDULE + " warmStart=" + WARM_START
                + " incrementalDirty=" + INCREMENTAL_DIRTY
                + " trigger=" + (DECISION_TRIGGER ? "decision" : "ship")
                + " stopRule=" + STOP_RULE
                + " convergeTol=" + CONVERGE_TOL
                + " stableDecisionSweeps=" + STABLE_DECISION_SWEEPS
                + " noEarlyStop=" + NO_EARLY_STOP
                + " updateThreshold=" + UPDATE_THRESHOLD
                + " alwaysRunBP=" + Boolean.getBoolean("minicpbp.debug.alwaysRunBP")
                + " seed=" + SEED
                + " sortDomainValues=" + Boolean.getBoolean("minicpbp.debug.sortDomainValues")
                + " queryOnly=" + QUERY_ONLY
                + " residualTol=" + RESIDUAL_TOL
                + " dumpGraph=" + DUMP_GRAPH;
    }
}
