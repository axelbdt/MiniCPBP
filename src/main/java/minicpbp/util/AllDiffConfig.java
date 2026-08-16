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
 *  minicpbp.alldiff.bpIters      iteration cap for BP     (default 10)
 *                                <= 0 selects an adaptive cap, nbVal/2
 *                                clamped to [2,20]; Phase 1 shows the
 *                                iteration count that maximises Kendall
 *                                tau grows with the matrix dimension
 *  minicpbp.alldiff.bpTol        convergence tolerance    (default 1e-6)
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
    public static final double BP_TOL;
    public static final boolean BP_WARM_START;
    public static final String HARVEST_FILE;
    public static final String HARVEST_TAG;
    public static final int HARVEST_PER_BUCKET;
    public static final String STATS_FILE;

    static {
        EXACT = ExactRoutine.valueOf(System.getProperty("minicpbp.alldiff.exact", "heap").toUpperCase());
        EXACT_MAX_DIM = Integer.parseInt(System.getProperty("minicpbp.alldiff.exactMaxDim", "7"));
        APPROX = ApproxRoutine.valueOf(System.getProperty("minicpbp.alldiff.approx", "soules").toUpperCase());
        BP_ITERS = Integer.parseInt(System.getProperty("minicpbp.alldiff.bpIters", "10"));
        BP_TOL = Double.parseDouble(System.getProperty("minicpbp.alldiff.bpTol", "1e-6"));
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
                + " bpIters=" + BP_ITERS + " bpTol=" + BP_TOL + " warmStart=" + BP_WARM_START;
    }
}
