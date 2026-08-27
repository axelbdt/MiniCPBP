/*
 * mini-cpbp — in-process dovetail over LDS passes.
 *
 * Registered by BP_PROBE_PROTOCOL.md amendment 10b (probe N phase 2), which
 * is next.md's N1 build pulled forward. Probe L established arbitration by
 * search — a failed cheap pass is ground truth about that pass's guidance,
 * bought for one root-to-leaf descent — but implemented it as one JVM per
 * pass, which charged every pass a parse+build floor and forced a hand-set
 * budget table. Both were artifacts of the orchestration, not of the
 * mechanism. This class is the mechanism, inside the solver:
 *
 *   - one process, one parsed model, one floor for the whole dovetail;
 *   - a Luby pass schedule (1 1 2 1 1 2 4 1 1 2 1 1 2 4 8 ...) over a unit
 *     calibrated from the first measured pass, so no budget table is hand
 *     set. Luby is optimal within a log factor without knowing the runtime
 *     distribution, which is exactly the situation probe N phase 1 measured
 *     (dispersion of 12-37x, light-tailed, no distribution known in advance);
 *   - each pass in its own state level, so passes do not inherit each other's
 *     root filtering — an in-process dovetail must not quietly become one
 *     search with changing parameters;
 *   - per-pass configuration (schedule, warm/cold entry, stop rule, sweep
 *     cap, discrepancy cap, RNG seed), which is what the arm ladder and the
 *     seed ladder both are;
 *   - sound completion: a single pass may claim UNSAT only when it ended with
 *     zero truncations, i.e. it really did exhaust its tree.
 *
 * The diversity source is a parameter, not a design commitment: a list of
 * arms cycles over the passes, and an arm may ask for a fresh seed on every
 * pass. Arms x seeds, arms only, seeds only are all expressible.
 */

package minicpbp.search;

import minicpbp.engine.core.MiniCP;
import minicpbp.engine.core.Solver;
import minicpbp.util.BPConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

public final class DovetailSearch {

    /** one configuration the dovetail cycles over */
    public static final class Arm {
        public final String label;
        public final BPConfig.Pass cfg;
        public final int maxIter;
        /** fixed seed, or null to leave the RNG as the JVM set it */
        public final Long seed;
        /** reseed with the pass ordinal before every pass (the seed ladder) */
        public final boolean freshSeedPerPass;

        public Arm(String label, BPConfig.Pass cfg, int maxIter, Long seed,
                   boolean freshSeedPerPass) {
            this.label = label;
            this.cfg = cfg;
            this.maxIter = maxIter;
            this.seed = seed;
            this.freshSeedPerPass = freshSeedPerPass;
        }
    }

    public static final class PassResult {
        public final int index;
        public final int lubyMult;
        public final int cap;
        public final String label;
        public final long seed;
        public final String status;   // SAT | UNSAT | TRUNCATED
        public final long nodes, failures, ms;
        public final int truncations;

        PassResult(int index, int lubyMult, int cap, String label, long seed,
                   String status, long nodes, long failures, long ms, int truncations) {
            this.index = index; this.lubyMult = lubyMult; this.cap = cap;
            this.label = label; this.seed = seed; this.status = status;
            this.nodes = nodes; this.failures = failures; this.ms = ms;
            this.truncations = truncations;
        }

        @Override public String toString() {
            return index + ":" + lubyMult + ":k" + cap + ":" + label + ":s" + seed
                    + ":" + status + ":" + nodes + "n:" + ms + "ms";
        }
    }

    public static final class Result {
        public String status = "TIMEOUT";     // SAT | UNSAT | TIMEOUT
        public String decidingLabel = "";
        public int decidingCap = -1;
        public long decidingSeed = -1;
        public long totalMs = 0;
        public long totalNodes = 0;
        public final List<PassResult> passes = new ArrayList<>();
    }

    /**
     * Luby sequence, 1-indexed: 1 1 2 1 1 2 4 1 1 2 1 1 2 4 8 ...
     * Pass j gets luby(j) units of wall; the discrepancy cap of that pass is
     * ladder[log2(luby(j))], so the cheap passes repeat and the expensive
     * ones arrive on the Luby schedule instead of a hand-set table.
     */
    public static int luby(int i) {
        int k = 1;
        while (true) {
            if (i == (1 << k) - 1) return 1 << (k - 1);
            if (i < (1 << k) - 1) { i = i - (1 << (k - 1)) + 1; k = 1; continue; }
            k++;
        }
    }

    /**
     * Fraction of the global budget after which the dovetail stops climbing the
     * Luby ladder and spends everything left on one completing pass. Without
     * it, a small calibrated unit means the ladder never reaches a pass that
     * could return UNSAT.
     */
    public static final double FINAL_PASS_RESERVE = 0.6;

    private DovetailSearch() {
    }

    /**
     * Run the dovetail.
     *
     * @param cp             the solver (its RNG and BP schedule cache are touched between passes)
     * @param searchFactory  builds a FRESH LDSearch, with a freshly built branching, per pass.
     *                       Fresh matters: a randomized heuristic captures the RNG and the
     *                       branching order at construction.
     * @param found          tells whether a solution has been recorded by the caller's hook
     * @param arms           configurations to cycle over; pass j uses arms[(j-1) % arms.size()]
     * @param ladder         discrepancy caps indexed by Luby rung
     * @param globalBudgetMs total wall for the whole dovetail
     * @param calibBudgetMs  budget of the first (cap 0) pass, which sets the unit
     * @param maxPasses      hard stop on pass count
     */
    public static Result run(Solver cp,
                             Supplier<LDSearch> searchFactory,
                             BooleanSupplier found,
                             List<Arm> arms,
                             int[] ladder,
                             long globalBudgetMs,
                             long calibBudgetMs,
                             int maxPasses) {
        Result out = new Result();
        BPConfig.Pass entry = BPConfig.currentPass();
        long unitMs = -1;
        long t0 = System.currentTimeMillis();
        try {
            for (int j = 1; j <= maxPasses; j++) {
                long spent = System.currentTimeMillis() - t0;
                long remaining = globalBudgetMs - spent;
                if (remaining <= 0 || !out.status.equals("TIMEOUT")) break;

                int mult = luby(j);
                int rung = 31 - Integer.numberOfLeadingZeros(mult);
                int cap = ladder[Math.min(rung, ladder.length - 1)];
                Arm arm = arms.get((j - 1) % arms.size());

                long budget;
                boolean last = false;
                if (unitMs < 0) {
                    budget = Math.min(calibBudgetMs, remaining);
                    cap = 0;                       // the calibration pass is the cheapest one
                } else {
                    budget = unitMs * mult;
                    // Two ways the ladder ends, and BOTH are needed. The first
                    // is the obvious one: the next Luby rung does not fit. The
                    // second is the one a first implementation got wrong — when
                    // the calibrated unit is small (a cap-0 pass on an easy
                    // model costs ~100 ms), Luby's budgets stay far below the
                    // remaining budget for thousands of passes, so the dovetail
                    // would spend its whole allowance on cheap truncated passes
                    // and NEVER run a completing one. Only a completing pass can
                    // close an UNSAT, so the tail of the budget is reserved for
                    // exactly one, at the top of the ladder.
                    if (budget > remaining
                            || spent >= (long) (FINAL_PASS_RESERVE * globalBudgetMs)) {
                        cap = ladder[ladder.length - 1];
                        budget = remaining;
                        last = true;
                    }
                }
                if (budget <= 0) break;

                // install the pass configuration
                if (BPConfig.applyPass(arm.cfg) && cp instanceof MiniCP)
                    ((MiniCP) cp).resetScheduler();
                cp.setMaxIter(arm.maxIter);
                long seedUsed = -1;
                if (arm.freshSeedPerPass) {
                    seedUsed = j;
                    if (cp instanceof MiniCP) ((MiniCP) cp).reseed(seedUsed);
                } else if (arm.seed != null) {
                    seedUsed = arm.seed;
                    if (cp instanceof MiniCP) ((MiniCP) cp).reseed(seedUsed);
                }

                LDSearch search = searchFactory.get();
                search.setSinglePassCap(cap);
                long p0 = System.currentTimeMillis();
                final long deadline = p0 + budget;
                SearchStatistics st = search.solve(
                        ss -> System.currentTimeMillis() >= deadline || found.getAsBoolean());
                long pms = System.currentTimeMillis() - p0;

                String status;
                if (found.getAsBoolean()) status = "SAT";
                else if (st.isCompleted()) status = "UNSAT";
                else status = "TRUNCATED";

                out.passes.add(new PassResult(j, mult, cap, arm.label, seedUsed, status,
                        st.numberOfNodes(), st.numberOfFailures(), pms,
                        search.lastPassTruncations()));
                out.totalNodes += st.numberOfNodes();

                if (unitMs < 0) {
                    // The unit is the cost of ONE cheap pass. A calibration pass
                    // truncated by its own budget measures the budget, not the
                    // pass, so clamp it: the instance is hard and the ladder must
                    // still start small.
                    unitMs = Math.max(1, Math.min(pms, calibBudgetMs));
                }

                if (!status.equals("TRUNCATED")) {
                    out.status = status;
                    out.decidingLabel = arm.label;
                    out.decidingCap = cap;
                    out.decidingSeed = seedUsed;
                    break;
                }
                if (last) break;
            }
        } finally {
            // leave the JVM as the dovetail found it
            if (BPConfig.applyPass(entry) && cp instanceof MiniCP)
                ((MiniCP) cp).resetScheduler();
            out.totalMs = System.currentTimeMillis() - t0;
        }
        return out;
    }
}
