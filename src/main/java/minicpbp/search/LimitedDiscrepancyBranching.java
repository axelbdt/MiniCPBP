/*
 * mini-cp is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License  v3
 * as published by the Free Software Foundation.
 *
 * mini-cp is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY.
 * See the GNU Lesser General Public License  for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with mini-cp. If not, see http://www.gnu.org/licenses/lgpl-3.0.en.html
 *
 * Copyright (c)  2018. by Laurent Michel, Pierre Schaus, Pascal Van Hentenryck
 */

package minicpbp.search;


import minicpbp.cp.BranchingScheme;
import minicpbp.util.Procedure;

import java.util.function.Supplier;

/**
 * Branching combinator
 * that ensures that that the alternatives created are always within the
 * discrepancy limit.
 * The discrepancy of an alternative generated
 * for a given node is the distance from the left most alternative.
 * The discrepancy of a node is the sum of the discrepancy of its ancestors.
 */
public class LimitedDiscrepancyBranching implements Supplier<Procedure[]> {

    private int curD;
    private int truncations;
    private final int maxD;

    /** number of nodes at which the discrepancy cap removed at least one alternative */
    public int truncations() {
        return truncations;
    }
    private final Supplier<Procedure[]> bs;

    /**
     * Creates a discprepancy combinator on a given branching.
     *
     * @param branching the branching on which to apply the discrepancy combinator
     * @param maxDiscrepancy the maximum discrepancy limit. Any node exceeding
     *                       that limit is pruned.
     */
    public LimitedDiscrepancyBranching(Supplier<Procedure[]> branching, int maxDiscrepancy) {
        if (maxDiscrepancy < 0) throw new IllegalArgumentException("max discrepancy should be >= 0");
        this.bs = branching;
        this.maxD = maxDiscrepancy;
    }

    /**
     * Discrepancy of the current node (sum over ancestors of the branch
     * index taken). Read at a solution node it is the exact number of times
     * the underlying heuristic's first choice was refused on the path —
     * the guidance-quality instrument of GCC_EXPERIMENT.md §10 /
     * BINPACKING_EXPERIMENT.md §8 (2026-08-19 LDS amendment).
     */
    public int currentDiscrepancy() {
        return curD;
    }

    @Override
    public Procedure[] get() {
        // Filter-out alternatives from that would exceed maxD
        // Therefore wrap each alternative
        // such that the call method of the wrapped alternatives
        // augment the curD depending on its position
        // +0 for alts[0], ..., +i for alts[i]
        Procedure[] branches = bs.get();

        int k = Math.min(maxD - curD + 1, branches.length);
        // 2026-08-19 (TODO.md item 3): count nodes where the cap actually
        // removed alternatives. A pass with zero truncations explored a
        // COMPLETE tree, so its node count is directly comparable to DFS.
        if (k < branches.length) truncations++;

        if (k == 0) return BranchingScheme.EMPTY;

        Procedure[] kFirstBranches = new Procedure[k];
        for (int i = 0; i < k; i++) {
            int bi = i;
            int d = curD + bi; // branch index
            kFirstBranches[i] = () -> {
                curD = d; // update discrepancy
                branches[bi].call();
            };
        }

        return kFirstBranches;
    }
}
