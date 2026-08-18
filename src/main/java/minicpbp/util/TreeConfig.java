/*
 * mini-cpbp, replacing classic propagation by belief propagation
 *
 * Experiment configuration for the tree-constraint counting experiment.
 * Added 2026-08-18, round 5 (see IMPLEMENTATION_LOG.md, TREE_GAC_DESIGN.md
 * and TREE_EXPERIMENT.md). Modeled on BinPackingConfig/GccConfig.
 *
 * The Tree constraint is NEW in this round: no shipped model posts it, so
 * there is no pre-experiment behaviour to preserve. The default belief
 * routine is EXACT (the constraint's raison d'etre — one Matrix-Tree
 * determinant + one adjugate per updateBelief call); arm U of Session D
 * overrides it to UNIFORM explicitly.
 *
 * System properties
 * -----------------
 *  minicpbp.tree.belief      uniform | exact   (default exact)
 *                            exact: directed Matrix-Tree / Matrix-Forest
 *                                   determinant; all leave-one-out messages
 *                                   from one adjugate (TreeMatrixDP); falls
 *                                   back to uniform above maxN or on
 *                                   numerical failure
 *  minicpbp.tree.maxN        node-count cap for the O(n^3) real path
 *                            (default 300)
 *  minicpbp.tree.ntreesMaxN  node-count cap for the O(n^4) complex DFT path
 *                            used when the NTREES variable is undetermined
 *                            with more than one potential root (default 40)
 */

package minicpbp.util;

public final class TreeConfig {

    public enum BeliefRoutine {UNIFORM, EXACT}

    public static final BeliefRoutine BELIEF;
    public static final int MAX_N;
    public static final int NTREES_MAX_N;

    static {
        BELIEF = BeliefRoutine.valueOf(System.getProperty("minicpbp.tree.belief", "exact").toUpperCase());
        MAX_N = Integer.parseInt(System.getProperty("minicpbp.tree.maxN", "300"));
        NTREES_MAX_N = Integer.parseInt(System.getProperty("minicpbp.tree.ntreesMaxN", "40"));
    }

    private TreeConfig() {
    }

    public static String describe() {
        return "belief=" + BELIEF + " maxN=" + MAX_N + " ntreesMaxN=" + NTREES_MAX_N;
    }
}
