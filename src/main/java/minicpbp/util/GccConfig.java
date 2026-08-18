/*
 * mini-cpbp, replacing classic propagation by belief propagation
 *
 * Experiment configuration for the gcc (cardinality) counting experiment.
 * Added 2026-08-18 (see IMPLEMENTATION_LOG.md and GCC_EXPERIMENT.md).
 *
 * Everything defaults to the behaviour MiniCPBP had before the experiment
 * (the indicator+sum decomposition of Cardinality.java), so an unconfigured
 * run is the pre-experiment solver bit for bit.
 *
 * System properties
 * -----------------
 *  minicpbp.gcc.post        decomp | regin      (default decomp)
 *                           decomp: post the existing Cardinality decomposition
 *                           regin:  post CardinalityDC (flow-based DC filtering
 *                                   + selectable belief routine)
 *  minicpbp.gcc.belief      uniform | exact | bp | auto | lobianco  (default auto)
 *                           exact: count-vector DP, fails over to bp when the
 *                                  state space exceeds maxStates
 *                           auto:  exact below the state budget, bp above
 *                           lobianco: closed-form column (Lo Bianco et al.,
 *                                  JAIR 66 §4, Soules-weighted; estimator,
 *                                  not a bound — Phase 3 arm 2); occurrence
 *                                  variables get uniform messages (the
 *                                  closed form has no count channel)
 *  minicpbp.gcc.maxStates   exact-DP state-space cap, prod(up_j + 1) (default 65536,
 *                           a memory bound: (n+2)*states doubles)
 *  minicpbp.gcc.opsBudget   exact-DP operation budget n*(k+1)*states (default 40000,
 *                           calibrated 2026-08-18 to the 233 us alldifferent
 *                           per-call budget; see GccPhase0 threshold table)
 *  minicpbp.gcc.bpIters     nested-BP sweep cap (default 5, the alldifferent
 *                           cap-5 recommendation)
 *  minicpbp.gcc.harvest     file to dump sampled belief systems to
 *  minicpbp.gcc.harvestTag  model name recorded with each system
 *  minicpbp.gcc.harvestPerBucket  reservoir size per bucket (default 2000)
 */

package minicpbp.util;

public final class GccConfig {

    public enum Post {DECOMP, REGIN}

    public enum BeliefRoutine {UNIFORM, EXACT, BP, AUTO, LOBIANCO}

    public static final Post POST;
    public static final BeliefRoutine BELIEF;
    public static final int MAX_STATES;
    public static final long OPS_BUDGET;
    public static final int BP_ITERS;
    public static final String HARVEST_FILE;
    public static final String HARVEST_TAG;
    public static final int HARVEST_PER_BUCKET;

    static {
        POST = Post.valueOf(System.getProperty("minicpbp.gcc.post", "decomp").toUpperCase());
        BELIEF = BeliefRoutine.valueOf(System.getProperty("minicpbp.gcc.belief", "auto").toUpperCase());
        MAX_STATES = Integer.parseInt(System.getProperty("minicpbp.gcc.maxStates", "65536"));
        OPS_BUDGET = Long.parseLong(System.getProperty("minicpbp.gcc.opsBudget", "40000"));
        BP_ITERS = Integer.parseInt(System.getProperty("minicpbp.gcc.bpIters", "5"));
        HARVEST_FILE = System.getProperty("minicpbp.gcc.harvest", "");
        HARVEST_TAG = System.getProperty("minicpbp.gcc.harvestTag", "unknown");
        HARVEST_PER_BUCKET = Integer.parseInt(System.getProperty("minicpbp.gcc.harvestPerBucket", "2000"));
    }

    private GccConfig() {
    }

    public static boolean harvesting() {
        return !HARVEST_FILE.isEmpty();
    }

    public static String describe() {
        return "post=" + POST + " belief=" + BELIEF + " maxStates=" + MAX_STATES
                + " opsBudget=" + OPS_BUDGET + " bpIters=" + BP_ITERS;
    }
}
