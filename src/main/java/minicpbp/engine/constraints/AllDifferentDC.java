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

package minicpbp.engine.constraints;

import minicpbp.util.Log;

import minicpbp.engine.core.AbstractConstraint;
import minicpbp.engine.core.IntVar;
import minicpbp.util.AllDiffConfig;
import minicpbp.util.AllDiffStats;
import minicpbp.util.AssignmentBP;
import minicpbp.util.BeliefMatrixHarvester;
import minicpbp.util.GraphUtil;
import minicpbp.util.GraphUtil.Graph;
import minicpbp.util.Permanent;
import minicpbp.util.SoulesUB3;
import minicpbp.state.StateSparseSet;
import minicpbp.util.exception.InconsistencyException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Domain Consistent AllDifferent Constraint
 * <p>
 * Algorithm described in
 * "A filtering algorithm for constraints of difference in CSPs" J-C. Régin, AAAI-94
 */
public class AllDifferentDC extends AbstractConstraint {

    private IntVar[] x;

    private final MaximumMatching maximumMatching;

    private final int nVar;
    private int nVal;

    // residual graph
    private ArrayList<Integer>[] in;
    private ArrayList<Integer>[] out;
    private int nNodes;
    private Graph g = new Graph() {
        @Override
        public int n() {
            return nNodes;
        }

        @Override
        public Iterable<Integer> in(int idx) {
            return in[idx];
        }

        @Override
        public Iterable<Integer> out(int idx) {
            return out[idx];
        }
    };

    private int[] match;
    private boolean[] matched;

    private int minVal;
    private int maxVal;

    private static final int exactPermanentThreshold = 6;
    // Experiment (2026-08-16): alternative counting routines, selected by
    // AllDiffConfig. All default to the pre-existing behaviour.
    private Permanent.DpWorkspace dpWorkspace; // subset-DP scratch, allocated on demand
    private AssignmentBP bp;                   // Williams & Lau single-scan BP
    private double[][] minors;                 // all-minors output buffer
    private long prevBpClamps, prevBpCancels, prevBpNonFinite; // for delta accounting
    private double[][] beliefs;
    private StateSparseSet freeVars; // holds an index for vars
    private StateSparseSet freeVals; // holds the actual values of freeVals
    private SoulesUB3 soules;
    private int[] c;
    private int[] permutation;
    private int[] varIndices;
    private int[] vals;

    public AllDifferentDC(IntVar... x) {
        super(x[0].getSolver(), x);
        setName("AllDifferentDC");
        this.x = x;
        maximumMatching = new MaximumMatching(x);
        match = new int[x.length];
        this.nVar = x.length;

        freeVars = new StateSparseSet(getSolver().getStateManager(), x.length, 0);
        // accumulate values from domains
        SortedSet<Integer> allVals = new TreeSet<Integer>();
        for (IntVar var : x) {
            int s = var.fillArray(domainValues);
            for (int j = 0; j < s; j++) {
                allVals.add(domainValues[j]);
            }
        }
        // remove assigned variables and their values from further consideration
        for (int i = 0; i < x.length; i++) {
            if (x[i].isBound()) {
                freeVars.remove(i);
                int val = x[i].min();
                allVals.remove(val);
                // apply basic fwd checking (because we may not call propagate())
                for (int k = 0; k < i; k++) {
                    x[k].remove(val);
                }
                for (int k = i + 1; k < x.length; k++) {
                    x[k].remove(val);
                }
            }
        }
        if (freeVars.isEmpty()) {
            freeVals = new StateSparseSet(getSolver().getStateManager(), 0, 0); // make it empty as well
            return; // special case of all variables in its scope already being bound
        }
        freeVals = new StateSparseSet(getSolver().getStateManager(), allVals.last().intValue() - allVals.first().intValue() + 1, allVals.first().intValue());
        // remove missing intermediate values from interval domain
        for (int i = allVals.first().intValue() + 1; i < allVals.last().intValue(); i++) {
            if (!allVals.contains(i))
                freeVals.remove(i);
        } // from now on freeVals will be maintained as a superset of the available values,
        // only removing values as they are taken on by a variable

        // allocate enough space for the data structures, even though we will need less and less as we go down the search tree
        beliefs = new double[freeVals.size()][freeVals.size()];
        c = new int[freeVals.size()];
        permutation = new int[freeVals.size()];
        varIndices = new int[freeVars.size()];
        vals = new int[freeVals.size()];
        if (freeVals.size() <= AllDiffConfig.EXACT_MAX_DIM) {
            setExactWCounting(true);
        } else {
            setExactWCounting(false); // actually, it will be exact below the threshold, which may happen lower in the search tree
        }
        soules = new SoulesUB3(freeVals.size());
        setupExperimentRoutines();
    }

    /**
     * Allocates the scratch space needed by the experimental counting routines
     * (see IMPLEMENTATION_LOG.md, 2026-08-16). Nothing is allocated for the
     * default configuration (Heap + Soules).
     */
    private void setupExperimentRoutines() {
        AllDiffStats.install();
        int dim = freeVals.size();
        if (AllDiffConfig.EXACT != AllDiffConfig.ExactRoutine.HEAP) {
            int maxDim = Math.min(dim, AllDiffConfig.EXACT_MAX_DIM);
            if (AllDiffConfig.EXACT == AllDiffConfig.ExactRoutine.DP && maxDim >= 1)
                dpWorkspace = new Permanent.DpWorkspace(Math.min(maxDim, 24));
            minors = new double[dim][dim];
        }
        if (AllDiffConfig.APPROX == AllDiffConfig.ApproxRoutine.BP) {
            bp = new AssignmentBP(dim, dim);
            if (minors == null) minors = new double[dim][dim];
        }
    }

    @Override
    public void post() {
        switch (getSolver().getMode()) {
            case BP:
                break;
            case SP:
            case SBP:
                for (int i = 0; i < nVar; i++) {
                    x[i].propagateOnDomainChange(this);
                }
                updateRange();
                matched = new boolean[nVal];
                nNodes = nVar + nVal + 1;
                in = new ArrayList[nNodes];
                out = new ArrayList[nNodes];
                for (int i = 0; i < nNodes; i++) {
                    in[i] = new ArrayList<>();
                    out[i] = new ArrayList<>();
                }
                propagate();
        }
    }

    private void updateRange() {
        minVal = Integer.MAX_VALUE;
        maxVal = Integer.MIN_VALUE;
        for (int i = 0; i < nVar; i++) {
            minVal = Math.min(minVal, x[i].min());
            maxVal = Math.max(maxVal, x[i].max());
        }
        nVal = maxVal - minVal + 1;
    }


    private void updateGraph() {
        nNodes = nVar + nVal + 1;
        int sink = nNodes - 1;
        for (int i = 0; i < nNodes; i++) {
            in[i].clear();
            out[i].clear();
        }
        Arrays.fill(matched, 0, nVal, false);
        for (int i = 0; i < x.length; i++) {
            in[i].add(match[i] - minVal + x.length);
            out[match[i] - minVal + nVar].add(i);
            matched[match[i] - minVal] = true;
        }
        for (int i = 0; i < nVar; i++) {
            for (int v = x[i].min(); v <= x[i].max(); v++) {
                if (x[i].contains(v) && match[i] != v) {
                    in[v - minVal + nVar].add(i);
                    out[i].add(v - minVal + nVar);
                }
            }
        }
        for (int v = minVal; v <= maxVal; v++) {
            if (!matched[v - minVal]) {
                in[sink].add(v - minVal + nVar);
                out[v - minVal + nVar].add(sink);
            } else {
                in[v - minVal + nVar].add(sink);
                out[sink].add(v - minVal + nVar);
            }
        }
    }


    @Override
    public void propagate() {
        // update the maximum matching
        int size = maximumMatching.compute(match);
        if (size < x.length) {
            throw new InconsistencyException();
        }
        // update the range of values
        updateRange();
        // update the residual graph
        updateGraph();
        // compute SCC's
        int[] scc = GraphUtil.stronglyConnectedComponents(g);
        for (int i = 0; i < nVar; i++) {
            for (int v = minVal; v <= maxVal; v++) {
                if (match[i] != v && scc[i] != scc[v - minVal + nVar]) {
                    x[i].remove(v);
                }
            }
        }
    }

    @Override
    public void updateBelief() {
        int nbVar, nbVal;
        // update freeVars/Vals according to bound variables
        nbVar = freeVars.fillArray(varIndices);
        for (int j = 0; j < nbVar; j++) {
            int i = varIndices[j];
            if (x[i].isBound()) {
                freeVars.remove(i);
                int val = x[i].min();
                freeVals.remove(val);
                // set trivial local belief for bound var...
                setLocalBelief(i, val, beliefRep.one());
                // ...and for other vars on that value
                for (int k = 0; k < j; k++) {
                    int l = varIndices[k];
                    if (x[l].contains(val))
                        setLocalBelief(l, val, beliefRep.zero());
                }
                for (int k = j + 1; k < nbVar; k++) {
                    int l = varIndices[k];
                    if (x[l].contains(val))
                        setLocalBelief(l, val, beliefRep.zero());
                }
            }
        }
        nbVar = freeVars.fillArray(varIndices);
        nbVal = freeVals.fillArray(vals);
        /*  upon experimentation, does not appear to speed up the computation
        if (nbVal - 1 > exactPermanentThreshold // && "domain size much smaller than nbVal"
            ) {
            // set local beliefs by computing the approximate permanent of beliefs directly from the domains (no matrix representation)
            setExactWCounting(false);
            costBasedPermanent_UB3_precomputeRowMax_sparseMatrix(nbVar);
            for (int j = 0; j < nbVar; j++) {
                int i = varIndices[j];
                int s = x[i].fillArray(domainValues);
                for (int k = 0; k < s; k++) {
                    int val = domainValues[k];
                    // note: will be normalized later in AbstractConstraint.sendMessages()
                    // put beliefs back to their original representation
                    setLocalBelief(i, val, beliefRep.std2rep(costBasedPermanent_UB3_faster_sparseMatrix(j, val, nbVar)));
                }
            }
            return;
        }
        */
        // initialize outside beliefs matrix (MUST BE IN STANDARD [0,1] REPRESENTATION)
        for (int j = 0; j < nbVar; j++) {
            int i = varIndices[j];
            for (int k = 0; k < nbVal; k++) {
                int val = vals[k];
                beliefs[j][k] = (x[i].contains(val) ? beliefRep.rep2std(outsideBelief(i, val)) : 0);
            }
        }
        // may need to add dummy rows in order to make the beliefs matrix square
        for (int j = 0; j < nbVal - nbVar; j++) {
            for (int k = 0; k < nbVal; k++) {
                // make row sum to 1 because we use this property in costBasedPermanent_UB3_faster()
                beliefs[nbVar + j][k] = 1.0 / nbVal; // (STANDARD REPRESENTATION)
            }
        }
        // optional instrumentation: sample the matrix that the counters see
        if (AllDiffConfig.harvesting())
            BeliefMatrixHarvester.offer(beliefs, nbVar, nbVal, getName());

        AllDiffStats.updateBeliefCalls++;
        // set local beliefs by computing the permanent of beliefs sub-matrices
        if (nbVal <= AllDiffConfig.EXACT_MAX_DIM) {
            // exact permanent
            AllDiffStats.exactCalls++;
            setExactWCounting(true);
            switch (AllDiffConfig.EXACT) {
                case HEAP:
                    for (int j = 0; j < nbVar; j++) {
                        int i = varIndices[j];
                        for (int k = 0; k < nbVal; k++) {
                            int val = vals[k];
                            if (x[i].contains(val)) {
                                // note: will be normalized later in AbstractConstraint.sendMessages()
                                // put beliefs back to their original representation
                                setLocalBelief(i, val, beliefRep.std2rep(costBasedPermanent_exact(j, k, nbVal)));
                            }
                        }
                    }
                    break;
                case DP:
                    Permanent.dpAllMinors(beliefs, nbVar, nbVal, 1.0 / nbVal, minors, dpWorkspace);
                    emitMinors(nbVar, nbVal);
                    break;
                case RYSER:
                    Permanent.ryserAllMinors(beliefs, nbVar, nbVal, 1.0 / nbVal, minors);
                    emitMinors(nbVar, nbVal);
                    break;
            }
        } else {
            // approximate permanent
            setExactWCounting(false);
            AllDiffStats.approxCalls++;
            if (AllDiffConfig.APPROX == AllDiffConfig.ApproxRoutine.BP) {
                if (updateBeliefBP(nbVar, nbVal)) return;
                // BP was rejected; fall through to Soules
            }
            AllDiffStats.soulesCalls++;
            soules.precomputeRowMax(beliefs, nbVal);
            for (int j = 0; j < nbVar; j++) {
                int i = varIndices[j];
                for (int k = 0; k < nbVal; k++) {
                    int val = vals[k];
                    if (x[i].contains(val)) {
                        // note: will be normalized later in AbstractConstraint.sendMessages()
                        // put beliefs back to their original representation
                        setLocalBelief(i, val, beliefRep.std2rep(soules.ub3Faster(beliefs, j, k, nbVal, nbVal - nbVar)));
  //                      setLocalBelief(i, val, beliefRep.std2rep(soules.ub3(beliefs, j, k, nbVal, nbVal - nbVar)));
                    }
                }
            }
        }
    }

    /**
     * Pushes the all-minors matrix computed by Ryser / subset DP into the
     * local beliefs, for the value pairs still in the domains.
     */
    private void emitMinors(int nbVar, int nbVal) {
        for (int j = 0; j < nbVar; j++) {
            int i = varIndices[j];
            for (int k = 0; k < nbVal; k++) {
                int val = vals[k];
                if (x[i].contains(val)) {
                    double v = minors[j][k];
                    if (v < 0) v = 0; // Ryser's inclusion-exclusion can undershoot zero by rounding
                    setLocalBelief(i, val, beliefRep.std2rep(v));
                }
            }
        }
    }

    /**
     * Approximate counting by loopy BP on the assignment factor graph
     * (Williams &amp; Lau 2014, single scan). The messages nu_{j->i} it returns
     * stand in for perm(A^{ij}); see AssignmentBP for why that, and not the
     * marginal, is what setLocalBelief() expects.
     *
     * @return true if the BP estimate was accepted, false if the caller should
     * fall back to Soules U^3
     */
    private boolean updateBeliefBP(int nbVar, int nbVal) {
        AllDiffStats.bpCalls++;
        long iterBefore = bp.nbIterations();
        // Hard cap of 5 sweeps (configurable), with early stopping once the
        // normalised solver-facing beliefs move by at most BP_EPS in total
        // variation over a sweep; see AssignmentBP for the criterion.
        int iters = AllDiffConfig.BP_ITERS > 0 ? AllDiffConfig.BP_ITERS : 5;
        boolean converged = bp.run(beliefs, nbVar, nbVal, iters, AllDiffConfig.BP_EPS,
                AllDiffConfig.BP_MIN_COLD, AllDiffConfig.BP_MIN_WARM, minors);
        AllDiffStats.bpIterations += bp.nbIterations() - iterBefore;
        AllDiffStats.bpEdges += bp.nbEdges();
        AllDiffStats.bpClamps += bp.nbClamps() - prevBpClamps;
        prevBpClamps = bp.nbClamps();
        AllDiffStats.bpCancelRecomputes += bp.nbCancelRecomputes() - prevBpCancels;
        prevBpCancels = bp.nbCancelRecomputes();
        AllDiffStats.bpNonFinite += bp.nbNonFinite() - prevBpNonFinite;
        prevBpNonFinite = bp.nbNonFinite();
        if (converged) AllDiffStats.bpConverged++;
        if (!AllDiffConfig.BP_WARM_START) bp.invalidateWarmStart();

        // sanity: every free row must keep at least one finite positive message
        for (int j = 0; j < nbVar; j++) {
            boolean anyPositive = false;
            for (int k = 0; k < nbVal; k++) {
                double v = minors[j][k];
                if (Double.isNaN(v) || Double.isInfinite(v) || v < 0) {
                    AllDiffStats.bpFallbacks++;
                    return false;
                }
                if (v > 0) anyPositive = true;
            }
            if (!anyPositive) {
                AllDiffStats.bpFallbacks++;
                return false;
            }
        }
        for (int j = 0; j < nbVar; j++) {
            int i = varIndices[j];
            for (int k = 0; k < nbVal; k++) {
                int val = vals[k];
                if (x[i].contains(val))
                    setLocalBelief(i, val, beliefRep.std2rep(minors[j][k]));
            }
        }
        return true;
    }

    @Override
    public double weightedCounting() {
        double weightedCount = 1.0;
        // contribution of bound variables to the weighted count
        for (int i = 0; i < nVar; i++) {
            if (x[i].isBound()) {
                weightedCount *= beliefRep.rep2std(outsideBelief(i, x[i].min()));
            }
        }
        int nbVar, nbVal;
        // update freeVars/Vals according to bound variables
        nbVar = freeVars.fillArray(varIndices);
        for (int j = 0; j < nbVar; j++) {
            int i = varIndices[j];
            if (x[i].isBound()) {
                freeVars.remove(i);
                freeVals.remove(x[i].min());
            }
        }
        nbVar = freeVars.fillArray(varIndices);
        nbVal = freeVals.fillArray(vals);
        // initialize outside beliefs matrix (MUST BE IN STANDARD [0,1] REPRESENTATION)
        for (int j = 0; j < nbVar; j++) {
            int i = varIndices[j];
            for (int k = 0; k < nbVal; k++) {
                int val = vals[k];
                beliefs[j][k] = (x[i].contains(val) ? beliefRep.rep2std(outsideBelief(i, val)) : 0);
            }
        }
        // may need to add dummy rows in order to make the beliefs matrix square
        for (int j = 0; j < nbVal - nbVar; j++) {
            for (int k = 0; k < nbVal; k++) {
                beliefs[nbVar + j][k] = 1.0 ; // (STANDARD REPRESENTATION)
            }
        }
        if (nbVal <= exactPermanentThreshold) {
            // exact permanent
            setExactWCounting(true);
            weightedCount *= permanent(beliefs, nbVal);
            // that value should actually be divided by (# dummy rows)!
            for (int i=2; i <= nbVal - nbVar; i++)
                weightedCount /= (double) i;
        } else {
            // approximate permanent
            setExactWCounting(false);
            // NOTE (2026-08-16): weightedCounting() deliberately stays on Soules U^3
            // even when updateBelief() runs BP -- see IMPLEMENTATION_LOG.md.
            weightedCount *= soules.ub3(beliefs, -1, -1, nbVal, nbVal - nbVar);
        }
        Log.constraint("weighted count for "+this.getName()+" constraint: "+beliefRep.std2rep(weightedCount));
        return beliefRep.std2rep(weightedCount); // put beliefs back to their original representation
    }

    /*
     * The Soules U^3 routines that used to live here (precompute_gamma,
     * costBasedPermanent_UB3, costBasedPermanent_UB3_precomputeRowMax,
     * costBasedPermanent_UB3_faster) moved verbatim to minicpbp.util.SoulesUB3
     * on 2026-08-16 so the offline Phase 1 comparison and the solver share one
     * implementation. The abandoned *_sparseMatrix variants were dropped with
     * them; their only call site was already commented out in updateBelief().
     */

    private double costBasedPermanent_exact(int var, int val, int dim) {
        // exact permanent for matrix m without row of var and column of val
        // to be used when m is not too large

        double tmp;

        // swap row "var" and column "val" with last row & column
        for (int j = 0; j < dim; j++) { // swap rows
            tmp = beliefs[var][j];
            beliefs[var][j] = beliefs[dim - 1][j];
            beliefs[dim - 1][j] = tmp;
        }
        for (int i = 0; i < dim; i++) { // swap columns
            tmp = beliefs[i][val];
            beliefs[i][val] = beliefs[i][dim - 1];
            beliefs[i][dim - 1] = tmp;
        }

        // compute permanent of m without last row & column
        double p = permanent(beliefs, dim - 1);

        // swap back
        for (int j = 0; j < dim; j++) { // swap rows
            tmp = beliefs[var][j];
            beliefs[var][j] = beliefs[dim - 1][j];
            beliefs[dim - 1][j] = tmp;
        }
        for (int i = 0; i < dim; i++) { // swap columns
            tmp = beliefs[i][val];
            beliefs[i][val] = beliefs[i][dim - 1];
            beliefs[i][dim - 1] = tmp;
        }

        return p; // that value should actually be divided by (# dummy rows)!
    }

    // compute the permanent of a real matrix through a simple adaptation of Heap's Algorithm that generates all permutations
    private double permanent(double[][] A, int n) {
        double prod = 1.0;
        for (int i = 0; i < n; i++) {
            c[i] = 0;
            permutation[i] = i;
            prod *= A[i][i];
        }
        double perm = prod;
        int i = 0;
        while (i < n) {
            if (c[i] < i) {
                if (i % 2 == 0)
                    prod = swap(A, permutation, 0, i, n, prod);
                else
                    prod = swap(A, permutation, c[i], i, n, prod);
                perm += prod;
                c[i]++;
                i = 0;
            } else {
                c[i] = 0;
                i++;
            }
        }
        return perm;
    }

    // swap the elements at indices i and j in the permutation for Heap's algorithm
    // returns the new prod   
    private double swap(double[][] A, int[] permutation, int i, int j, int n, double prod) {
        int e = permutation[i];
        permutation[i] = permutation[j];
        permutation[j] = e;
        double newFactor = A[i][permutation[i]] * A[j][permutation[j]];
        double oldFactor = A[i][permutation[j]] * A[j][permutation[i]];
        if (newFactor == 0)
            return 0;
        else if (oldFactor == 0) {
            // cannot divide it out -- compute from scratch
            double newProd = 1.0;
            for (int k = 0; k < n; k++)
                newProd *= A[k][permutation[k]];
            return newProd;
        } else
            return prod * newFactor / oldFactor;
    }

}
