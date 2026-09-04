/*
 * mini-cpbp, replacing classic propagation by belief propagation
 *
 * Experiment configuration for the among/count counting experiment
 * (WP2 of AMONG_GCC_OPTIMIZATION_PLAN.md), added 2026-09-03.
 *
 * System properties
 * -----------------
 *  minicpbp.among.post   decomp | direct   (default decomp)
 *      decomp: post AmongVar (indicator variables y + a Sum constraint),
 *              the pre-experiment behaviour, bit for bit.
 *      direct: post AmongCount (no auxiliary variables; exact count DP at
 *              k = 1 for the beliefs).
 */

package minicpbp.util;

public final class AmongConfig {

    public enum Post {DECOMP, DIRECT}

    public static final Post POST;

    static {
        POST = Post.valueOf(System.getProperty("minicpbp.among.post", "decomp").toUpperCase());
    }

    private AmongConfig() {
    }

    public static String describe() {
        return "among.post=" + POST;
    }
}
