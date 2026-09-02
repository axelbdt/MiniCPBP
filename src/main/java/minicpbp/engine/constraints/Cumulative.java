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
import minicpbp.util.CumulativeBP;
import minicpbp.util.CumulativeBlockBP;
import minicpbp.util.CumulativeTimeTable;
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
public class Cumulative extends AbstractConstraint {

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
        if (routine == SchedulingConfig.BeliefRoutine.BP || routine == SchedulingConfig.BeliefRoutine.AUTO) {
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

}
