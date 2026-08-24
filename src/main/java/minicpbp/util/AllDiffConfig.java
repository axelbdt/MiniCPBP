/*
 * mini-cpbp, replacing classic propagation by belief propagation
 *
 * Experiment configuration for AllDifferentDC's counting routines.
 * Added 2026-08-16 (see IMPLEMENTATION_LOG.md).
 *
 * Everything defaults to the behaviour MiniCPBP had before the experiment
 * (Heap's algorithm below the threshold, Soules U^3 above it), so an
 * unconfigured run is arm A of Phase 3 bit for bit.
 *
 * System properties
 * -----------------
 *  minicpbp.alldiff.exact        heap | ryser | dp        (default heap)
 *  minicpbp.alldiff.exactMaxDim  largest nbVal handled exactly (default 7,
 *                                which is the pre-existing test
 *                                nbVal - 1 <= exactPermanentThreshold = 6)
 *  minicpbp.alldiff.approx       soules | bp              (default soules)
 *  minicpbp.alldiff.bpIters      hard sweep cap for BP    (default 5)
 *  minicpbp.alldiff.bpEps        stability threshold on R_t, the max over
 *                                variables of the total-variation distance
 *                                between the normalised solver-facing
 *                                beliefs of two consecutive sweeps
 *                                (default 0.01; <= 0 disables early stopping
 *                                and always runs the full cap)
 *  minicpbp.alldiff.bpMinCold    minimum sweeps, cold start (default 2)
 *  minicpbp.alldiff.bpMinWarm    minimum sweeps, warm start (default 1)
 *  minicpbp.alldiff.bpWarmStart  true | false             (default true)
 *  minicpbp.alldiff.harvest      file to dump sampled belief matrices to
 *  minicpbp.alldiff.harvestTag   model name recorded with each matrix
 *  minicpbp.alldiff.harvestPerBucket  reservoir size per nbVal bucket (default 2000)
 *  minicpbp.alldiff.stats        file to append per-run counters to
 */

package minicpbp.util;

public final class AllDiffConfig {

    public enum ExactRoutine {HEAP, RYSER, DP}

    public enum ApproxRoutine {SOULES, BP}

    public static final ExactRoutine EXACT;
    public static final int EXACT_MAX_DIM;
    public static final ApproxRoutine APPROX;
    public static final int BP_ITERS;
    public static final double BP_EPS;
    public static final int BP_MIN_COLD;
    public static final int BP_MIN_WARM;
    public static final boolean BP_WARM_START;
    public static final String HARVEST_FILE;
    public static final String HARVEST_TAG;
    public static final int HARVEST_PER_BUCKET;
    public static final String STATS_FILE;

    static {
        EXACT = ExactRoutine.valueOf(System.getProperty("minicpbp.alldiff.exact", "heap").toUpperCase());
        EXACT_MAX_DIM = Integer.parseInt(System.getProperty("minicpbp.alldiff.exactMaxDim", "7"));
        APPROX = ApproxRoutine.valueOf(System.getProperty("minicpbp.alldiff.approx", "soules").toUpperCase());
        BP_ITERS = Integer.parseInt(System.getProperty("minicpbp.alldiff.bpIters", "5"));
        BP_EPS = Double.parseDouble(System.getProperty("minicpbp.alldiff.bpEps", "0.01"));
        BP_MIN_COLD = Integer.parseInt(System.getProperty("minicpbp.alldiff.bpMinCold",
                Integer.toString(AssignmentBP.DEFAULT_MIN_SWEEPS_COLD)));
        BP_MIN_WARM = Integer.parseInt(System.getProperty("minicpbp.alldiff.bpMinWarm",
                Integer.toString(AssignmentBP.DEFAULT_MIN_SWEEPS_WARM)));
        BP_WARM_START = Boolean.parseBoolean(System.getProperty("minicpbp.alldiff.bpWarmStart", "true"));
        HARVEST_FILE = System.getProperty("minicpbp.alldiff.harvest", "");
        HARVEST_TAG = System.getProperty("minicpbp.alldiff.harvestTag", "unknown");
        HARVEST_PER_BUCKET = Integer.parseInt(System.getProperty("minicpbp.alldiff.harvestPerBucket", "2000"));
        STATS_FILE = System.getProperty("minicpbp.alldiff.stats", "");
    }

    private AllDiffConfig() {
    }

    public static boolean harvesting() {
        return !HARVEST_FILE.isEmpty();
    }

    public static String describe() {
        return "exact=" + EXACT + " exactMaxDim=" + EXACT_MAX_DIM + " approx=" + APPROX
                + " bpIters=" + BP_ITERS + " bpEps=" + BP_EPS
                + " bpMinCold=" + BP_MIN_COLD + " bpMinWarm=" + BP_MIN_WARM
                + " warmStart=" + BP_WARM_START;
    }
}
