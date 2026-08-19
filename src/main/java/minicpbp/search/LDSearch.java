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
 *
 * mini-cpbp, replacing classic propagation by belief propagation 
 * Copyright (c)  2019. by Gilles Pesant
 */

package minicpbp.search;

import minicpbp.state.StateManager;
import minicpbp.util.exception.InconsistencyException;
import minicpbp.util.exception.NotImplementedException;
import minicpbp.util.Procedure;
import minicpbp.engine.core.IntVar;

import java.util.function.Predicate;
import minicpbp.util.Log;
import java.util.function.Supplier;

import org.antlr.v4.parse.ANTLRParser.throwsSpec_return;

/**
 * Limited Discrepancy Search Branch and Bound implementation
 */
public class LDSearch extends Search{

    private Supplier<Procedure[]> branching;
    private LimitedDiscrepancyBranching LDSbranching;
    private boolean geometric; // true if the sequence of max discrepancies follows a geometric progression (with ratio=2); false if it follows an arithmetic progression (with difference=1)
    private int discrepancyUB;
    private StateManager sm;
    // 2026-08-19 (LDS amendment, GCC_EXPERIMENT.md §10): exact discrepancy of
    // the path to the last solution found, -1 if none. This is the
    // guidance-quality metric — how many times the heuristic's first value
    // choice had to be refused before a solution appeared.
    private int solutionDiscrepancy = -1;

    public int solutionDiscrepancy() {
        return solutionDiscrepancy;
    }

    // 2026-08-19 (TODO.md item 3, run-length accounting): naive LDS re-expands
    // the tree prefix on every pass, so the cumulative node count is NOT
    // comparable to a DFS tree size. Per-pass deltas separate the quantities:
    // the final pass (cap >= discrepancyUB) is a complete fresh depth-first
    // traversal and its node count IS directly comparable to DFS; the earlier
    // passes are the revisit overhead. Rows: {cap, nodes, failures, solutions}.
    private final java.util.List<long[]> passStats = new java.util.ArrayList<>();

    // 2026-08-19 (TODO.md item 3): total domain size at each pass's root.
    // All LDS passes run inside ONE state level, so any domain reduction made
    // at a pass root (belief-driven filtering in
    // AbstractConstraint.sendMessages, or propagation) is NOT undone between
    // passes: later passes start from a strictly stronger root state. This
    // probe measures exactly that carried information.
    private java.util.function.IntSupplier rootDomainSizeProbe;

    public void setRootDomainSizeProbe(java.util.function.IntSupplier p) {
        this.rootDomainSizeProbe = p;
    }

    /** per-pass "cap:nodes:failures:solutions:rootDomainSizeSum" joined by ';' */
    public String passSummary() {
        StringBuilder sb = new StringBuilder();
        for (long[] p : passStats) {
            if (sb.length() > 0) sb.append(';');
            sb.append(p[0]).append(':').append(p[1]).append(':').append(p[2]).append(':').append(p[3])
              .append(':').append(p[4]);
        }
        return sb.toString();
    }

    /**
     * Creates a Limited Discrepancy Search object with a given branching
     * that defines the search tree dynamically.
     *
     * @param sm the state manager that will be saved and restored
     *           at each node of the search tree
     * @param branching a generator of closures in charge of defining the ordered
     *                  children nodes at each node of the depth-first-search tree.
     *                  When it returns an empty array, a solution is found.
     *                  A backtrack occurs when a {@link InconsistencyException}
     *                  is thrown.
     * @param geometric to indicate whether the progression of maxDiscrepancy is geometric
     * @param discrepancyUB an upper bound on the number of discrepancies in the rightmost branch of a complete search tree
     */
    public LDSearch(StateManager sm, Supplier<Procedure[]> branching, boolean geometric, int discrepancyUB) {
        this.sm = sm;
        this.branching = branching;
	this.geometric = geometric;
	this.discrepancyUB = discrepancyUB;
    }
	
    private SearchStatistics solve(SearchStatistics statistics, Predicate<SearchStatistics> limit) {
        sm.withNewState(() -> {
	    int maxDiscrepancy = 1;
            try {
		if (discrepancyUB==0) { // special case of all vars already being fixed
		    LDSbranching = new LimitedDiscrepancyBranching(branching, 0);
		    ldsPass(0, statistics, limit);
		}
                else
		    // 2026-08-19 completeness fix: the last pass must reach
		    // discrepancyUB exactly. The previous geometric loop
		    // (while maxD <= UB, maxD *= 2) stopped after the largest
		    // power of two <= UB, leaving the highest-discrepancy
		    // paths unexplored while setCompleted() below still
		    // declared the search complete — an unsound UNSAT when UB
		    // is not a power of two. The cap keeps the geometric
		    // schedule but clamps the final pass to UB.
		    while (true) { // nb discrepancies of rightmost branch <= nb vars * (domain size - 1)
			int cap = Math.min(maxDiscrepancy, discrepancyUB);
			LDSbranching = new LimitedDiscrepancyBranching(branching, cap);
			// System.out.println("LDS: on search tree with max discrepancy = "+cap);
			ldsPass(cap, statistics, limit);
			// System.out.println(statistics);
			if (cap >= discrepancyUB)
			    break;
			if (geometric)
			    maxDiscrepancy *= 2;
			else
			    maxDiscrepancy++;
		    }
                statistics.setCompleted();
            } catch (StopSearchException ignored) {
 		//System.out.println("c LDS: currently on search tree with max discrepancy = "+maxDiscrepancy);
            } catch (StackOverflowError e) {
                throw new NotImplementedException("c lds with explicit stack needed to pass this test");
            }
        });
        return statistics;
    }


    /**
     * Effectively start a depth first search
     * looking for every solution.
     *
     * @return an object with the statistics on the search
     */
    public SearchStatistics solve() {
        SearchStatistics statistics = new SearchStatistics();
        return solve(statistics, stats -> false);
    }

    /**
     * Effectively start a depth first search
     * with a given predicate called at each node
     * to stop the search when it becomes true.
     *
     * @param limit a predicate called at each node
     *             that stops the search when it becomes true
     * @return an object with the statistics on the search
     */
    public SearchStatistics solve(Predicate<SearchStatistics> limit) {
        SearchStatistics statistics = new SearchStatistics();
        return solve(statistics, limit);
    }

    /**
     * Executes a closure prior to effectively
     * starting a depth first search
     * with a given predicate called at each node
     * to stop the search when it becomes true.
     * The state manager saves the state
     * before executing the closure
     * and restores it after the search.
     * Any {@link InconsistencyException} that may
     * be throw when executing the closure is also catched.
     *
     * @param limit a predicate called at each node
     *             that stops the search when it becomes true
     * @param subjectTo the closure to execute prior to the search starts
     * @return an object with the statistics on the search
     */
    public SearchStatistics solveSubjectTo(Predicate<SearchStatistics> limit, Procedure subjectTo) {
        SearchStatistics statistics = new SearchStatistics();
        sm.withNewState(() -> {
            try {
                subjectTo.call();
                solve(statistics, limit);
            } catch (InconsistencyException e) {
            }
        });
        return statistics;
    }

    public SearchStatistics solveRestarts(Predicate<SearchStatistics> limit, int nbFailCutof, double restartFactor) {
        throw new NotImplementedException();
    }

    public void initializeImpact(IntVar... x) {
        throw new NotImplementedException();
    }

    public void initializeImpactDomains(IntVar... x) {
        throw new NotImplementedException();
    }

    /**
     * Effectively start a branch and bound
     * depth first search with a given objective.
     *
     * @param obj the objective to optimize that is tightened each
     *            time a new solution is found
     * @return an object with the statistics on the search
     */
    public SearchStatistics optimize(Objective obj) {
        return optimize(obj, stats -> false);
    }

    /**
     * Effectively start a branch and bound
     * depth first search with a given objective
     * and with a given predicate called at each node
     * to stop the search when it becomes true.
     *
     * @param obj the objective to optimize that is tightened each
     *            time a new solution is found
     * @param limit a predicate called at each node
     *             that stops the search when it becomes true
     * @return an object with the statistics on the search
     */
    public SearchStatistics optimize(Objective obj, Predicate<SearchStatistics> limit) {
        SearchStatistics statistics = new SearchStatistics();
        if (!obj.problemIsBound()) { // avoid in special case of problem solved by propagation alone
            onSolution(() -> {
                if (obj.tracingOptimization()) {
                    Log.optim(" (solution found in " + statistics.numberOfFailures() + " fails and " + statistics.timeElapsed() + " msecs)");
                }
                obj.tighten();
            });
        }
        return solve(statistics, limit);
    }

    /**
     * Executes a closure prior to effectively
     * starting a branch and bound depth first search
     * with a given objective to optimize
     * and a given predicate called at each node
     * to stop the search when it becomes true.
     * The state manager saves the state
     * before executing the closure
     * and restores it after the search.
     * Any {@link InconsistencyException} that may
     * be throw when executing the closure is also catched.
     *
     * @param obj the objective to optimize that is tightened each
     *            time a new solution is found
     * @param limit a predicate called at each node
     *             that stops the search when it becomes true
     * @param subjectTo the closure to execute prior to the search starts
     * @return an object with the statistics on the search
     */
    public SearchStatistics optimizeSubjectTo(Objective obj, Predicate<SearchStatistics> limit, Procedure subjectTo) {
        SearchStatistics statistics = new SearchStatistics();
        sm.withNewState(() -> {
            try {
                subjectTo.call();
                optimize(obj, limit);
            } catch (InconsistencyException e) {
            }
        });
        return statistics;
    }


    /** one LDS pass with per-pass statistics deltas recorded (see passStats) */
    private void ldsPass(int cap, SearchStatistics statistics, Predicate<SearchStatistics> limit) {
        long n0 = statistics.numberOfNodes();
        long f0 = statistics.numberOfFailures();
        long s0 = statistics.numberOfSolutions();
        long rootDom = (rootDomainSizeProbe == null) ? -1 : rootDomainSizeProbe.getAsInt();
        long[] row = {cap, 0, 0, 0, rootDom};
        passStats.add(row);
        try {
            lds(statistics, limit);
        } finally {
            row[1] = statistics.numberOfNodes() - n0;
            row[2] = statistics.numberOfFailures() - f0;
            row[3] = statistics.numberOfSolutions() - s0;
            if (Boolean.getBoolean("minicpbp.lds.trace"))
                System.err.println("LDS pass cap=" + cap + " nodes=" + row[1]
                        + " failures=" + row[2] + " solutions=" + row[3]
                        + " rootDomainSizeSum=" + rootDom
                        + " truncations=" + LDSbranching.truncations());
        }
    }

    private void lds(SearchStatistics statistics, Predicate<SearchStatistics> limit) {
        if (limit.test(statistics))
            throw new StopSearchException();
        withinNode(() -> {
            Procedure[] branches = LDSbranching.get();
            if (branches.length == 0) {
                statistics.incrSolutions();
                solutionDiscrepancy = LDSbranching.currentDiscrepancy();
                notifySolution();
            } else {
                final int total = branches.length;
                for (int i = 0; i < total; i++) {
                    final Procedure b = branches[i];
                    final int branchIndex = i;
                    sm.withNewState(() -> {
                        try {
                            statistics.incrNodes();
                            withinBranch(branchIndex, total, () -> {
                                b.call();
                                lds(statistics, limit);
                            });
                        } catch (InconsistencyException e) {
                            statistics.incrFailures();
                            notifyFailure();
                        }
                    });
                }
            }
        });
    }

    

}
