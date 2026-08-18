/*
 * mini-cpbp, replacing classic propagation by belief propagation
 *
 * Experiment configuration for the bin_packing counting experiment.
 * Added 2026-08-18, round 4 (see IMPLEMENTATION_LOG.md and
 * BINPACKING_EXPERIMENT.md). Modeled on GccConfig.
 *
 * Everything defaults to the behaviour MiniCPBP had before the experiment
 * (the sum decomposition of BinPacking.java), so an unconfigured run is the
 * pre-experiment solver bit for bit.
 *
 * System properties
 * -----------------
 *  minicpbp.binpacking.post     decomp | counting   (default decomp)
 *                               decomp:   post the existing BinPacking sum
 *                                         decomposition
 *                               counting: post BinPackingCounting (dedicated
 *                                         filtering + selectable belief routine)
 *  minicpbp.binpacking.belief   uniform | exact | bp | auto   (default auto)
 *                               exact: load-vector DP (BinLoadDP), fails over
 *                                      to bp when the state space exceeds
 *                                      maxStates
 *                               auto:  exact below the ops budget, bp above
 *  minicpbp.binpacking.maxStates  exact-DP state-space cap prod(up_j + 1)
 *                               (default 65536, a memory bound:
 *                               (n+2)*states doubles)
 *  minicpbp.binpacking.opsBudget  exact-DP operation budget n*(m+1)*states
 *                               (default 40000, inherited from the gcc/
 *                               alldifferent 233 us per-call budget — see
 *                               exp.BinPackingPhase0 threshold table)
 *  minicpbp.binpacking.bpIters  nested-BP sweep cap (default 5, the
 *                               alldifferent cap-5 recommendation, replicated
 *                               by the gcc Phase 1 result)
 *  minicpbp.binpacking.harvest  file to dump sampled belief systems to
 *  minicpbp.binpacking.harvestTag  model name recorded with each system
 *  minicpbp.binpacking.harvestPerBucket  reservoir size per bucket (default 2000)
 */

package minicpbp.util;

public final class BinPackingConfig {

    public enum Post {DECOMP, COUNTING}

    public enum BeliefRoutine {UNIFORM, EXACT, BP, AUTO}

    public static final Post POST;
    public static final BeliefRoutine BELIEF;
    public static final int MAX_STATES;
    public static final long OPS_BUDGET;
    public static final int BP_ITERS;
    public static final String HARVEST_FILE;
    public static final String HARVEST_TAG;
    public static final int HARVEST_PER_BUCKET;

    static {
        POST = Post.valueOf(System.getProperty("minicpbp.binpacking.post", "decomp").toUpperCase());
        BELIEF = BeliefRoutine.valueOf(System.getProperty("minicpbp.binpacking.belief", "auto").toUpperCase());
        MAX_STATES = Integer.parseInt(System.getProperty("minicpbp.binpacking.maxStates", "65536"));
        OPS_BUDGET = Long.parseLong(System.getProperty("minicpbp.binpacking.opsBudget", "40000"));
        BP_ITERS = Integer.parseInt(System.getProperty("minicpbp.binpacking.bpIters", "5"));
        HARVEST_FILE = System.getProperty("minicpbp.binpacking.harvest", "");
        HARVEST_TAG = System.getProperty("minicpbp.binpacking.harvestTag", "unknown");
        HARVEST_PER_BUCKET = Integer.parseInt(System.getProperty("minicpbp.binpacking.harvestPerBucket", "2000"));
    }

    private BinPackingConfig() {
    }

    public static boolean harvesting() {
        return !HARVEST_FILE.isEmpty();
    }

    public static String describe() {
        return "post=" + POST + " belief=" + BELIEF + " maxStates=" + MAX_STATES
                + " opsBudget=" + OPS_BUDGET + " bpIters=" + BP_ITERS;
    }
}
