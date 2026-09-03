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


package minicpbp.engine.constraints;

import minicpbp.cp.Factory;
import minicpbp.engine.constraints.Profile.Rectangle;
import minicpbp.engine.core.AbstractConstraint;
import minicpbp.engine.core.IntVar;
import minicpbp.util.CandidateTable;
import minicpbp.util.CumulativeBP;
import minicpbp.util.CumulativeBlockBP;
import minicpbp.util.CumulativeTimeTable;
import minicpbp.util.ExactlyOneRows;
import minicpbp.util.IntervalPackingDP;
import minicpbp.util.PackingFactor;
import minicpbp.util.ResourceMDD;
import minicpbp.util.SchedStats;
import minicpbp.util.SchedulingConfig;
import minicpbp.util.exception.InconsistencyException;
import minicpbp.util.exception.NotImplementedException;

import java.util.ArrayList;

import static minicpbp.cp.Factory.minus;
import static minicpbp.cp.Factory.plus;

/**
 * Cumulative constraint with time-table filtering.
 * <p>
 * Beliefs (DISJUNCTIVE_CUMULATIVE_PLAN.md): the routine is selected by
 * {@code -Dminicpbp.sched.belief=uniform|timetable|bp|auto} (default
 * uniform, today's behaviour). Exactly one object per posted constraint
 * counts — the primary one; the mirror copy posted over the views
 * {@code minus(plus(start[i], dur[i]))} and the copies a {@link Disjunctive}
 * posts for its mirror emit a silent uniform, since views forward messages
 * to the same base variables and a second copy of the same factor would
 * square its message.
 */
public class Cumulative extends AbstractConstraint implements minicpbp.engine.core.ValueCounter {

    private final IntVar[] start;
    private final int[] duration;
    private final IntVar[] end;
    private final int[] demand;
    private final int capa;
    private final boolean postMirror;
    /** whether this object is the one that emits the (non-uniform) belief */
    private final boolean counting;

    // ----- counting scratch (allocated on first use) -----
    private int[][] dom;
    private int[] domSize;
    private double[][] a;
    private double[][] out;
    private CumulativeBP bp;
    private CumulativeBlockBP blockBp; // amendment A3: used when SchedulingConfig.BP_BLOCK >= 2
    private CumulativeTimeTable timetable;
    // ----- MDD_COUNTING_PLAN.md: candidate-interval engine (belief=mdd) -----
    private CandidateTable table;
    private ExactlyOneRows rows;
    private PackingFactor packing;
    private boolean packingIsInterval;
    private double[] logMsg; // amendment A4: direct log messages by candidate id


    /**
     * Creates a cumulative constraint with a time-table filtering.
     * At any time-point t, the sum of the demands
     * of the activities overlapping t do not overlap the capacity.
     *
     * @param start    the start time of each activities
     * @param duration the duration of each activities (non negative)
     * @param demand   the demand of each activities, non negative
     * @param capa     the capacity of the constraint
     */
    public Cumulative(IntVar[] start, int[] duration, int[] demand, int capa) {
        this(start, duration, demand, capa, true, true);
    }

    /**
     * @param postMirror whether to post the mirrored copy (filtering from the right)
     * @param counting   whether this object emits the counting belief; the mirror never does
     */
    Cumulative(IntVar[] start, int[] duration, int[] demand, int capa, boolean postMirror, boolean counting) {
        super(start[0].getSolver(), start);
        setName("Cumulative");
        this.start = start;
        this.duration = duration;
        this.end = Factory.makeIntVarArray(start.length, i -> plus(start[i], duration[i]));
        this.demand = demand;
        this.capa = capa;
        this.postMirror = postMirror;
        this.counting = counting;
        setExactWCounting(false);
        // amendment A2: a copy that never emits a non-uniform belief need not
        // be a factor of the BP graph at all; it keeps filtering
        if (!counting && SchedulingConfig.BP_LEAN) setBpParticipant(false);
    }


    @Override
    public void post() {
        for (int i = 0; i < start.length; i++) {
            start[i].propagateOnBoundChange(this);
        }

        if (postMirror) {
            IntVar[] startMirror = Factory.makeIntVarArray(start.length, i -> minus(end[i]));
            getSolver().post(new Cumulative(startMirror, duration, demand, capa, false, false), false);
        }

        propagate();
    }

    @Override
    public void propagate() {
        Profile profile = buildProfile();
        for (int i = 0; i < profile.size(); i++) {
            if (profile.get(i).height() > capa) {
                throw InconsistencyException.INCONSISTENCY;
            }
        }

        for (int i = 0; i < start.length; i++) {
            if (!start[i].isBound()) {
                // j is the index of the profile rectangle overlapping t
                int j = profile.rectangleIndex(start[i].min());
                int t = start[i].min();
                while (j < profile.size()
                        && profile.get(j).start() < Math.min(t + duration[i], start[i].max())) {
                    if (capa - demand[i]
                            <  profile.get(j).height()) {
                        t = Math.min(profile.get(j).end(), start[i].max());
                    }
                    j++;
                }
                start[i].removeBelow(t);
            }
        }
    }

    public Profile buildProfile() {
        ArrayList<Rectangle> mandatoryParts = new ArrayList<Rectangle>();
        for (int i = 0; i < start.length; i++) {
            if (end[i].min() > start[i].max()) {
                int s = start[i].max();
                int e = end[i].min();
                int d = demand[i];
                mandatoryParts.add(new Rectangle(s, e, d));
            }
        }
        return new Profile(mandatoryParts.toArray(new Profile.Rectangle[0]));
    }

    // ------------------------------------------------------------------
    // beliefs
    // ------------------------------------------------------------------

    @Override
    public void updateBelief() {
        if (SchedulingConfig.BELIEF == SchedulingConfig.BeliefRoutine.UNIFORM) {
            super.updateBelief(); // today's behaviour, warning included
            return;
        }
        if (!counting) {
            resetLocalBelief(); // silent uniform: the primary copy counts
            return;
        }
        long t0 = System.nanoTime();
        SchedStats.calls++;
        int n = start.length;
        if (dom == null) {
            dom = new int[n][];
            domSize = new int[n];
            a = new double[n][];
            out = new double[n][];
            for (int i = 0; i < n; i++) {
                int cap = start[i].max() - start[i].min() + 1;
                dom[i] = new int[cap];
                a[i] = new double[cap];
                out[i] = new double[cap];
            }
        }
        // ---- collapse: sorted domains and outside beliefs in the standard representation ----
        for (int i = 0; i < n; i++) {
            if (demand[i] > capa && duration[i] > 0) {
                SchedStats.inconsistent++;
                SchedStats.nanos += System.nanoTime() - t0;
                throw InconsistencyException.INCONSISTENCY;
            }
            int lo = start[i].min(), hi = start[i].max();
            if (dom[i].length < hi - lo + 1) { // cannot happen (domains only shrink); defensive
                dom[i] = new int[hi - lo + 1];
                a[i] = new double[hi - lo + 1];
                out[i] = new double[hi - lo + 1];
            }
            int s = 0;
            if (start[i].isBound()) {
                dom[i][0] = lo;
                a[i][0] = 1.0;
                s = 1;
            } else {
                for (int v = lo; v <= hi; v++) {
                    if (start[i].contains(v)) {
                        dom[i][s] = v;
                        a[i][s] = beliefRep.rep2std(outsideBelief(i, v));
                        s++;
                    }
                }
            }
            domSize[i] = s;
        }
        // ---- routine ----
        SchedulingConfig.BeliefRoutine routine = SchedulingConfig.BELIEF;
        boolean done = false;
        if (routine == SchedulingConfig.BeliefRoutine.MDD) {
            done = updateBeliefMdd(n);
            // a decline or a numerical failure falls through to the 4A engine below
        }
        if (!done && (routine == SchedulingConfig.BeliefRoutine.BP || routine == SchedulingConfig.BeliefRoutine.AUTO
                || routine == SchedulingConfig.BeliefRoutine.MDD)) {
            long budget = (routine == SchedulingConfig.BeliefRoutine.AUTO) ? SchedulingConfig.OPS_BUDGET : 0L;
            int status;
            long ops;
            int sweepsDone;
            boolean conv;
            if (SchedulingConfig.BP_BLOCK >= 2) {
                if (blockBp == null) blockBp = new CumulativeBlockBP(SchedulingConfig.BP_BLOCK);
                status = blockBp.run(n, dom, domSize, a, duration, demand, capa,
                        SchedulingConfig.BP_ITERS, SchedulingConfig.BP_EPS, SchedulingConfig.BP_MIN_SWEEPS,
                        budget, out);
                ops = blockBp.opsPerSweep();
                sweepsDone = blockBp.lastSweeps();
                conv = blockBp.lastConverged();
            } else {
                if (bp == null) bp = new CumulativeBP();
                status = bp.run(n, dom, domSize, a, duration, demand, capa,
                        SchedulingConfig.BP_ITERS, SchedulingConfig.BP_EPS, SchedulingConfig.BP_MIN_SWEEPS,
                        budget, out);
                ops = bp.opsPerSweep();
                sweepsDone = bp.lastSweeps();
                conv = bp.lastConverged();
            }
            if (ops > SchedStats.maxOpsPerSweep) SchedStats.maxOpsPerSweep = ops;
            if (status == CumulativeBP.OK) {
                SchedStats.bpCalls++;
                SchedStats.bpSweeps += sweepsDone;
                if (conv) SchedStats.bpConverged++;
                done = true;
            } else if (status == CumulativeBP.DECLINED) {
                SchedStats.bpDeclined++;
            } else {
                SchedStats.bpNumericalFallbacks++;
            }
        }
        if (!done) {
            if (timetable == null) timetable = new CumulativeTimeTable();
            timetable.run(n, dom, domSize, duration, demand, capa, out);
            SchedStats.timetableCalls++;
        }
        // ---- emit ----
        for (int i = 0; i < n; i++) {
            if (start[i].isBound()) continue; // handled by normalizeBelief
            for (int k = 0; k < domSize[i]; k++) {
                setLocalBelief(i, dom[i][k], beliefRep.std2rep(out[i][k]));
            }
        }
        SchedStats.nanos += System.nanoTime() - t0;
    }

    /**
     * Amendment A5: the configured counting routine run with uniform incoming
     * messages, read for one variable. Only the primary (counting) object
     * answers; mirrors and Disjunctive-internal copies that are not the
     * counting object decline. The routine is the one {@code sched.belief}
     * selects ({@code bp} or {@code mdd}, with the mdd flags); {@code uniform}
     * declines.
     */
    @Override
    public boolean valueScores(IntVar x, int[] values, int nVals, double[] scores) {
        if (!counting || SchedulingConfig.BELIEF == SchedulingConfig.BeliefRoutine.UNIFORM
                || SchedulingConfig.BELIEF == SchedulingConfig.BeliefRoutine.TIMETABLE) return false;
        int pos = -1;
        IntVar bx = x.getBaseVar();
        for (int i = 0; i < start.length; i++) if (start[i].getBaseVar() == bx) { pos = i; break; }
        if (pos < 0 || start[pos] != x) return false; // a view of the variable: the value map is not the identity
        long t0 = System.nanoTime();
        SchedStats.calls++;
        int n = start.length;
        if (dom == null) {
            dom = new int[n][];
            domSize = new int[n];
            a = new double[n][];
            out = new double[n][];
            for (int i = 0; i < n; i++) {
                int cap = start[i].max() - start[i].min() + 1;
                dom[i] = new int[cap];
                a[i] = new double[cap];
                out[i] = new double[cap];
            }
        }
        for (int i = 0; i < n; i++) {
            if (demand[i] > capa && duration[i] > 0) { SchedStats.nanos += System.nanoTime() - t0; return false; }
            int lo = start[i].min(), hi = start[i].max();
            if (dom[i].length < hi - lo + 1) {
                dom[i] = new int[hi - lo + 1];
                a[i] = new double[hi - lo + 1];
                out[i] = new double[hi - lo + 1];
            }
            int s = 0;
            for (int v = lo; v <= hi; v++) {
                if (start[i].contains(v)) {
                    dom[i][s] = v;
                    a[i][s] = 1.0; // uniform incoming message
                    s++;
                }
            }
            domSize[i] = s;
        }
        boolean done = false;
        if (SchedulingConfig.BELIEF == SchedulingConfig.BeliefRoutine.MDD) done = updateBeliefMdd(n);
        if (!done) {
            if (bp == null) bp = new CumulativeBP();
            int status = bp.run(n, dom, domSize, a, duration, demand, capa,
                    SchedulingConfig.BP_ITERS, SchedulingConfig.BP_EPS, SchedulingConfig.BP_MIN_SWEEPS, 0L, out);
            if (status != CumulativeBP.OK) { SchedStats.nanos += System.nanoTime() - t0; return false; }
            SchedStats.bpCalls++;
            SchedStats.bpSweeps += bp.lastSweeps();
        }
        // out[pos][k] follows dom[pos] (ascending); values[] is in fillArray order
        for (int q = 0; q < nVals; q++) {
            int v = values[q];
            int k = java.util.Arrays.binarySearch(dom[pos], 0, domSize[pos], v);
            scores[q] = (k >= 0) ? out[pos][k] : 0.0;
        }
        SchedStats.nanos += System.nanoTime() - t0;
        return true;
    }

    /**
     * The candidate-interval factorisation (MDD_COUNTING_PLAN.md §1): the
     * Williams-Lau row kernel over a stable candidate table and a resource
     * factor that is the exact interval-packing DP when Cap = 1 with unit
     * demands (unless mddForce) and the resource MDD otherwise. Emits
     * {@code out[i][k] = r_a} for live candidates, 0 for alive candidates that
     * are infeasible against the committed profile of the bound jobs, 1 for
     * inert jobs.
     *
     * @return false if the call declined (exact width blow-up) or failed
     * numerically; {@code out} is then untouched
     */
    private boolean updateBeliefMdd(int n) {
        if (table == null || !table.refresh(dom, domSize, a)) {
            // first call, or (defensively) a value outside the table: (re)build
            table = new CandidateTable(n, dom, domSize, duration, demand, capa);
            table.refresh(dom, domSize, a);
            boolean unit = capa == 1;
            for (int i = 0; i < n && unit; i++) if (demand[i] != 1 && duration[i] > 0) unit = false;
            packingIsInterval = unit && !SchedulingConfig.MDD_FORCE && !SchedulingConfig.MDD_JOB_STATE;
            packing = packingIsInterval ? new IntervalPackingDP()
                    : new ResourceMDD(SchedulingConfig.MDD_WIDTH, SchedulingConfig.MDD_JOB_STATE);
            if (rows == null) rows = new ExactlyOneRows();
            rows.invalidate();
            if (SchedulingConfig.MDD_JOB_STATE && (logMsg == null || logMsg.length < table.M)) logMsg = new double[table.M];
        }
        SchedStats.mddLiveSum += table.nLive;
        if (SchedulingConfig.MDD_JOB_STATE) return updateBeliefJobState(n);
        if (table.nLiveJobs >= 2) {
            rows.run(table, packing, SchedulingConfig.MDD_ITERS, SchedulingConfig.MDD_EPS, 1,
                    SchedulingConfig.MDD_WARM, 1.0);
            if (rows.lastFailed()) {
                if (packing instanceof ResourceMDD && ((ResourceMDD) packing).lastDeclined()) SchedStats.mddDeclined++;
                else SchedStats.mddNumericalFallbacks++;
                rows.invalidate();
                return false;
            }
            SchedStats.mddSweeps += rows.lastSweeps();
            if (rows.lastConverged()) SchedStats.mddConverged++;
            if (packing instanceof ResourceMDD) {
                ResourceMDD mdd = (ResourceMDD) packing;
                if (mdd.lastWidth() > SchedStats.mddWidthMax) SchedStats.mddWidthMax = mdd.lastWidth();
                if (mdd.lastWidthBeforeMerge() > SchedStats.mddWidthBeforeMergeMax)
                    SchedStats.mddWidthBeforeMergeMax = mdd.lastWidthBeforeMerge();
                SchedStats.mddWidthSum += mdd.lastWidth();
                if (mdd.lastRelaxed()) SchedStats.mddRelaxed++;
            }
        }
        SchedStats.mddCalls++;
        if (packingIsInterval) SchedStats.intervalCalls++;
        double[] r = rows.r();
        for (int i = 0; i < n; i++) {
            if (start[i].isBound()) continue;
            if (table.inert[i]) {
                java.util.Arrays.fill(out[i], 0, domSize[i], 1.0);
                continue;
            }
            for (int c = table.jobBegin[i]; c < table.jobEnd[i]; c++) {
                if (!table.alive[c]) continue;
                out[i][table.slot[c]] = table.live[c] ? (table.nLiveJobs >= 2 ? r[c] : 1.0) : 0.0;
            }
        }
        return true;
    }

    /**
     * Amendment A4: one direct pass on the job-state diagram, outside beliefs
     * as arc weights, log cavity messages back; emitted per row after a shift
     * by the row maximum.
     */
    private boolean updateBeliefJobState(int n) {
        ResourceMDD mdd = (ResourceMDD) packing;
        if (table.nLiveJobs >= 1) {
            if (!mdd.updateDirect(table, table.weight, logMsg)) {
                if (mdd.lastDeclined()) SchedStats.mddDeclined++;
                else SchedStats.mddNumericalFallbacks++;
                return false;
            }
            SchedStats.mddSweeps++;
            if (mdd.lastWidth() > SchedStats.mddWidthMax) SchedStats.mddWidthMax = mdd.lastWidth();
            if (mdd.lastWidthBeforeMerge() > SchedStats.mddWidthBeforeMergeMax)
                SchedStats.mddWidthBeforeMergeMax = mdd.lastWidthBeforeMerge();
            SchedStats.mddWidthSum += mdd.lastWidth();
            if (mdd.lastRelaxed()) SchedStats.mddRelaxed++;
        }
        SchedStats.mddCalls++;
        for (int i = 0; i < n; i++) {
            if (start[i].isBound()) continue;
            if (table.inert[i]) {
                java.util.Arrays.fill(out[i], 0, domSize[i], 1.0);
                continue;
            }
            double mx = Double.NEGATIVE_INFINITY;
            for (int c = table.jobBegin[i]; c < table.jobEnd[i]; c++)
                if (table.live[c] && logMsg[c] > mx) mx = logMsg[c];
            for (int c = table.jobBegin[i]; c < table.jobEnd[i]; c++) {
                if (!table.alive[c]) continue;
                double v = 0.0;
                if (table.live[c] && mx > Double.NEGATIVE_INFINITY && logMsg[c] > Double.NEGATIVE_INFINITY)
                    v = Math.max(Math.exp(logMsg[c] - mx), Double.MIN_NORMAL);
                out[i][table.slot[c]] = v;
            }
        }
        return true;
    }

}
