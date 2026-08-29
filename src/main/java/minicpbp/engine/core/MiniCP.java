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

package minicpbp.engine.core;

import minicpbp.cp.Factory;
import minicpbp.search.Objective;
import minicpbp.state.StateInt;
import minicpbp.state.StateManager;
import minicpbp.state.StateStack;
import minicpbp.util.exception.InconsistencyException;
import minicpbp.util.Procedure;
import minicpbp.util.Belief;
import minicpbp.util.StdBelief;
import minicpbp.util.LogBelief;
import minicpbp.util.Log;
import minicpbp.engine.constraints.LinEqSystemModP;

import java.util.*;

public class MiniCP implements Solver {

    private Queue<Constraint> propagationQueue = new ArrayDeque<>();
    private List<Procedure> fixPointListeners = new LinkedList<>();
    private List<Procedure> beliefPropaListeners = new LinkedList<>();
    private List<PropagateListener> propagateConstraintListeners = new LinkedList<>();
    private List<DomainOpListener> domainOpListeners = new LinkedList<>();

    private final StateManager sm;

    private StateStack<IntVar> variables;
    private StateStack<Constraint> constraints;

    private Random rand;

    //******** PARAMETERS ********
    // SP  /* support propagation (aka standard constraint propagation) */
    // BP  /* belief propagation */
    // SBP /* first apply support propagation, then belief propagation */
    private static PropaMode mode = PropaMode.SBP;
    // nb of BP iterations performed
    private static int beliefPropaMaxIter = 10;
    // apply damping to variable-to-constraint messages
    private static boolean damping = true;
    // damping factor in interval [0,1] where 1 is equivalent to no damping
    private static double dampingFactor = 0.75;
    // entropy threshold for a variable; below it we should consider that its value is almost certain
    private final double MIN_VAR_ENTROPY = 1.0E-3;
    // entropy tolerance beyond which it is considered different
    private final double ENTROPY_TOLERANCE = 0.01;
    // reset marginals, local beliefs, and previous outside belief before applying BP at each search-tree node
    private static final boolean resetMarginalsBeforeBP = true;
    // take action upon zero/one beliefs: remove/assign the corresponding value
    private static final boolean actOnZeroOneBelief = false;
    /**
     * Relative decrease of the metric to trigger BP; in interval [0,1] where 0
     * triggers whenever any domain changed at all.
     * <p>
     * Exposed as {@code -Dminicpbp.bp.updateThreshold} (F8). Two properties of
     * the metric matter when reading a measurement made under it: it is a
     * global scalar over <em>all</em> registered variables, auxiliaries
     * included, so one variable's collapse can hide every other variable's
     * stability and vice versa; and its denominator {@code sumDomainSizes} is a
     * trailed {@code StateInt}, i.e. the value left by the last ancestor on
     * this path that actually ran BP, not by the parent. Marginals a node
     * branches on are therefore a function of the path, not of the domains.
     */
    private static double beliefUpdateThreshold = minicpbp.util.BPConfig.UPDATE_THRESHOLD;
    /**
     * 2026-08-19 (TODO.md item 3): forces BP at every search node, disabling
     * the "reuse current marginals" shortcut below. With the shortcut on,
     * the marginals a node sees are inherited from whichever ancestor last
     * ran BP, so branching is a function of the PATH, not of the domains.
     */
    private static final boolean ALWAYS_RUN_BP = Boolean.getBoolean("minicpbp.debug.alwaysRunBP");
    private static final boolean SKIP_REUSE_NORMALIZE = Boolean.getBoolean("minicpbp.debug.skipReuseNormalize");
    private static final boolean SCRUB_OUTSIDE_BELIEF = Boolean.getBoolean("minicpbp.debug.scrubOutsideBelief");
    // representation of beliefs: either standard (StdBelief: [0..1]) or log (LogBelief: [-infinity..0])
    private final Belief beliefRep = new StdBelief();
    // SAME   /* constraints all have the same weight; = 1.0 (default) */
    // ARITY  /* a constraint's weight is related to its arity; = 1 + (arity - min_arity)/total_nb_of_vars */
    private static final ConstraintWeighingScheme Wscheme = ConstraintWeighingScheme.SAME;

    //****************************


    // for message damping
    private boolean prevOutsideBeliefRecorded = false;
    private boolean tuneDamping = true;

    // for weighing constraints
    private int minArity;

    // metric to decide whether or not to update beliefs at a search tree node
    private StateInt sumDomainSizes;
    private long trigger = 0;
    private long potentialTrigger = 0;

    // BP scheduling (BP_SCHEDULING.md phase 0.1): the schedule is a policy, not
    // a property of the engine. The default is FloodingScheduler, which is the
    // historical synchronous sweep unchanged.
    private BPGraph bpGraph;
    private BPScheduler scheduler;
    private boolean graphDumped = false;

    /* incremental dirty seeding (BP_WARM_START_EXPERIMENT.md section 1.3):
     * a monotone counter of real invocations, and the trailed epoch of the last
     * one on the current path. */
    private int bpEpoch = 0;
    private StateInt bpPathEpoch;

    /** the array the branching heuristic scans, for a decision-directed stop */
    private IntVar[] branchingOrder;

    public MiniCP(StateManager sm) {
        this.sm = sm;
        variables = new StateStack<>(sm);
        constraints = new StateStack<>(sm);
        // F9: -Dminicpbp.seed makes the RNG reproducible. It does NOT make a run
        // deterministic on its own: the sparse set restores membership but not
        // the ORDER of its elements, and randomValue() indexes into fillArray's
        // output, so -Dminicpbp.debug.sortDomainValues is needed as well. Time
        // that flag before putting it in an arm — it fires per fillArray call.
        rand = (minicpbp.util.BPConfig.SEED == null)
                ? new Random() : new Random(minicpbp.util.BPConfig.SEED);
        sumDomainSizes = sm.makeStateInt(Integer.MAX_VALUE);
        bpPathEpoch = sm.makeStateInt(0);
    }

    public MiniCP(StateManager sm, long seed) {
        this.sm = sm;
        variables = new StateStack<>(sm);
        constraints = new StateStack<>(sm);
        rand = new Random(seed);
        sumDomainSizes = sm.makeStateInt(Integer.MAX_VALUE);
        bpPathEpoch = sm.makeStateInt(0);
    }

    @Override
    public int bpEpoch() {
        return bpEpoch;
    }

    @Override
    public int bpPathEpoch() {
        return bpPathEpoch.value();
    }

    @Override
    public void setBranchingOrder(IntVar[] x) {
        branchingOrder = x;
    }

    public long trigger() {return trigger;}
    public long potentialTrigger() {return potentialTrigger;}

    /**
     * The BP schedule in force, created on first use from
     * -Dminicpbp.bp.schedule (default: the historical flooding sweep).
     */
    private BPScheduler scheduler() {
        if (scheduler == null) {
            minicpbp.util.BPStats.install();
            switch (minicpbp.util.BPConfig.SCHEDULE) {
                case FLOOD:
                    scheduler = new FloodingScheduler(this);
                    break;
                case SEQ:
                    scheduler = new SequentialScheduler(bpGraph(), false);
                    break;
                case SEQFB:
                    scheduler = new SequentialScheduler(bpGraph(), true);
                    break;
                case TOPO:
                    scheduler = new TopoScheduler(bpGraph());
                    break;
                case RESIDUAL:
                    scheduler = new ResidualScheduler(bpGraph());
                    break;
                default:
                    scheduler = new FloodingScheduler(this);
            }
        }
        return scheduler;
    }

    private BPGraph bpGraph() {
        if (bpGraph == null) bpGraph = new BPGraph(this);
        return bpGraph;
    }

    @Override
    public StateManager getStateManager() {
        return sm;
    }

    @Override
    public StateStack<IntVar> getVariables() {
        return variables;
    }

    @Override
    public StateStack<Constraint> getConstraints() {
        return constraints;
    }

    @Override
    public Random getRandomNbGenerator() { return rand; }

    /**
     * Amendment 10b: reseed the solver RNG between dovetail passes, so that
     * one JVM can run the seed ladder a randomized heuristic needs. A
     * branching heuristic that captured {@code rand} at construction keeps the
     * SAME generator object, so it sees the new seed — but a heuristic that
     * cached derived state would not, which is why DovetailSearch rebuilds the
     * branching from a factory on every pass.
     */
    public void reseed(long seed) {
        rand.setSeed(seed);
    }

    /**
     * Amendment 10b: drop the cached BP schedule so the next invocation
     * rebuilds it from the current {@code BPConfig.SCHEDULE}. Only needed when
     * a pass changes the schedule.
     */
    public void resetScheduler() {
        scheduler = null;
    }

    @Override
    public Belief getBeliefRep() {
        return beliefRep;
    }

    @Override
    public void registerVar(IntVar x) {
        variables.push(x);
    }

    public void setMode(PropaMode mode) {
        MiniCP.mode = mode;
    }

    public PropaMode getMode() {
        return mode;
    }

    public ConstraintWeighingScheme getWeighingScheme() {
        return Wscheme;
    }

    public void setTraceBPFlag(boolean traceBP) {
        Log.setTraceBP(traceBP);
    }

    public void setTraceSearchFlag(boolean traceSearch) {
        Log.setTraceSearch(traceSearch);
    }

    public void setTraceEntropyFlag(boolean traceEntropy) {
        Log.setTraceEntropy(traceEntropy);
    }

    public void setMaxIter(int maxIter) {
        MiniCP.beliefPropaMaxIter = maxIter;
    }

    public boolean dampingMessages() {
        return damping;
    }

    public void setDamp(boolean damp) {
        MiniCP.damping = damp;
    }

    public double dampingFactor() {
        return dampingFactor;
    }

    public void setDampingFactor(double dampingFactor) {
        MiniCP.dampingFactor = dampingFactor;
    }

    public boolean prevOutsideBeliefRecorded() {
        return prevOutsideBeliefRecorded;
    }

    public boolean actingOnZeroOneBelief() {
        return actOnZeroOneBelief;
    }

    public boolean tracingSearch() {
        return Log.isTracingSearch();
    }

    public void schedule(Constraint c) {
        if (c.isActive() && !c.isScheduled()) {
            c.setScheduled(true);
            propagationQueue.add(c);
        }
    }

    @Override
    public void onFixPoint(Procedure listener) {
        fixPointListeners.add(listener);
    }

    private void notifyFixPoint() {
        fixPointListeners.forEach(s -> s.call());
    }

    @Override
    public void onPropagateConstraint(PropagateListener listener) {
        propagateConstraintListeners.add(listener);
    }

    @Override
    public void onDomainOp(DomainOpListener listener) {
        domainOpListeners.add(listener);
    }

    @Override
    public void notifyDomainOp(IntVar x, DomainOpKind kind, int v) {
        if (domainOpListeners.isEmpty()) return;
        for (DomainOpListener l : domainOpListeners) {
            l.onOp(x, kind, v);
        }
    }

    @Override
    public void computeMinArity() {
        this.minArity = Integer.MAX_VALUE;
        Iterator<Constraint> iterator = constraints.iterator();
        Constraint c;
        while (iterator.hasNext()) {
            c = iterator.next();
            if (this.minArity > c.arity())
                this.minArity = c.arity();
        }
       iterator = constraints.iterator();
        while (iterator.hasNext()) {
            c = iterator.next();
            double w = 1.0 + ((double) (c.arity() - this.minArity))/ ((double) constraints.size());
            c.setWeight(w);
        }

    }

    @Override
    public int minArity() {
        return this.minArity;
    }

    @Override
    public void fixPoint() {
        notifyFixPoint();
        try {
            while(!propagationQueue.isEmpty()) {
                Constraint c = propagationQueue.remove();
                try {
                    propagate(c);
                }
                catch(InconsistencyException e) {
                    // empty the queue and unset the scheduled status
                    c.incrementFailureCount();
                    while (!propagationQueue.isEmpty())
                        propagationQueue.remove().setScheduled(false);
                    throw e;
                }

            }
        }
        catch (NoSuchElementException e) {}
    }

    @Override
    public void onBeliefPropa(Procedure listener) {
        beliefPropaListeners.add(listener);
    }

    private void notifyBeliefPropa() {
        beliefPropaListeners.forEach(s -> s.call());
    }

    @Override
    public int nbBranchingVariables() {
        int count = 0;
        Iterator<IntVar> iterator = variables.iterator();
        while (iterator.hasNext()) {
            if(iterator.next().isForBranching())
                count += 1;
        }
        return count;
    }

    /**
     * Belief Propagation
     * standard version, with two distinct message-passing phases
     * first from variables to constraints, and then from constraints to variables
     */
    @Override
    public void beliefPropa() {
        long t0 = System.nanoTime();
        try {
            beliefPropaImpl();
        } finally {
            minicpbp.util.BPStats.bpNanos += System.nanoTime() - t0;
        }
    }

    private void beliefPropaImpl() {
 //       System.out.println(variables.size()+" variables");
 //       System.out.println(constraints.size()+" constraints");
        Constraint c;
        // First decide whether we trigger BP or simply reuse current marginals
        int sum = 0;
        Iterator<IntVar> iterator = variables.iterator();
        while (iterator.hasNext()) {
            sum += iterator.next().size();
        }
        potentialTrigger++;
        minicpbp.util.BPStats.calls++;
        // Probe K (BP_PROBE_PROTOCOL.md amendment 7): the decision-keyed
        // trigger replaces the 5 % gate below. Static final, folded when off.
        if (!ALWAYS_RUN_BP && minicpbp.util.BPConfig.DECISION_TRIGGER) {
            if (decisionTriggerSkip())
                return; // marginals already renormalized inside the test
            trigger++;
            minicpbp.util.BPStats.invocations++;
            sumDomainSizes.setValue(sum);
            bpEpoch++;
        }
        else if (!ALWAYS_RUN_BP && sum >= (1.0 - beliefUpdateThreshold) * sumDomainSizes.value()) { // trigger BP only if domains sufficiently changed
            // 2026-08-19 (TODO.md item 3 diagnosis): renormalizing on the
            // reuse path is not idempotent in floating point (v / sum(v)
            // drifts by ulps when sum is ~1 but not exactly 1). Repeated
            // skip-path renormalizations at positions the trail never pops
            // (e.g. the root of successive LDS passes) make marginals drift
            // across passes; entropy-scale ~1e-11 comparisons then amplify
            // ulp drift into different branching. The debug property
            // disables the renormalization to isolate that mechanism.
            if (!SKIP_REUSE_NORMALIZE) {
                iterator = variables.iterator();
                while (iterator.hasNext()) {
                    iterator.next().normalizeMarginals();
                }
            }
            return; // reuse current marginals
        }
        else {
            trigger++;
            minicpbp.util.BPStats.invocations++;
            sumDomainSizes.setValue(sum);
            bpEpoch++;
        }
        notifyBeliefPropa();
        try {
            if (tuneDamping) { // decide & tune message damping; default: once at the root node
                BPtuneDamping();
                tuneDamping = false;
            }
            // next.md P1a: on this branch the restored (reversible, and thus
            // correctly backtracked) marginals and local beliefs are thrown
            // away and BP reconverges from uniform. WARM_START keeps them and
            // continues from there instead.
            boolean fullDirty = true;
            if (resetMarginalsBeforeBP && !minicpbp.util.BPConfig.WARM_START) {
                coldReset();
            }
            else if (minicpbp.util.BPConfig.WARM_START) {
                fullDirty = warmEntry();
            }
            BPScheduler sched = scheduler();
            // Probe Q (amendment 13): hint the schedule to root its spanning
            // forest at the variable dom/wdeg branching will select, BEFORE the
            // rebuild inside beginInvocation consumes it. wdeg's inputs do not
            // move during sweeps, so one computation per invocation is exact.
            if (minicpbp.util.BPConfig.ROOT_AT_DECISION)
                bpGraph().setPreferredRoot(wdegDecisionVar());
            // the seeding must read the watermark of the PREVIOUS invocation on
            // this path, so the update below happens after beginInvocation
            sched.beginInvocation(fullDirty);
            // trailed write, so also guarded: nothing reads it otherwise
            if (minicpbp.util.BPConfig.INCREMENTAL_DIRTY
                    || minicpbp.util.BPConfig.DECISION_TRIGGER) bpPathEpoch.setValue(bpEpoch);
            if (minicpbp.util.BPConfig.DUMP_GRAPH && !graphDumped && bpGraph != null) {
                graphDumped = true;
                // stderr: harnesses redirect stdout while solving
                System.err.println("c bp graph: " + bpGraph.describe());
            }
            final double[] entropy = {1.0};
            decisionVar = null;
            decisionVal = Integer.MIN_VALUE;
            stableDecisionSweeps = 0;
            convergeSnapshotValid = false;
            final minicpbp.util.BPConfig.StopRule rule = minicpbp.util.BPConfig.STOP_RULE;
            sched.run(beliefPropaMaxIter, iter -> {
                Log.bpIteration(iter, variables);
                // Probe H (BP_PROBE_PROTOCOL.md amendment 4): per-sweep
                // decision trace; static final gate, dead code when off
                if (minicpbp.util.SweepTrace.ENABLED)
                    minicpbp.util.SweepTrace.record(this, iter);
                double previousEntropy = entropy[0];
                double currentEntropy = problemEntropy();
                entropy[0] = currentEntropy;
                double smallEntropy = smallestVariableEntropy();
                if (dampingMessages())
                    prevOutsideBeliefRecorded = true;
                Log.bpEntropy(currentEntropy, smallEntropy);
                Log.modelEntropy(variables, nbBranchingVariables());
                // Stopping criteria. Exactly one rule is in force and it
                // REPLACES the others (F6): the shipped code tested three of
                // them in sequence, so whichever sat highest in the body won,
                // NO_EARLY_STOP returned before STABLE_DECISION_SWEEPS was ever
                // read, and MIN_VAR_ENTROPY fired within one or two sweeps and
                // made the knob below it inert.
                if (currentEntropy == 0) {
                    // either all branching vars are bound or BP says there is no
                    // solution: a correctness stop, in force in every mode
                    return true;
                }
                switch (rule) {
                    case FIXED:
                        return false; // R1: measure at a fixed sweep budget
                    case CONVERGE:
                        // R2: the only criterion about movement rather than
                        // confidence. The first sweep has nothing to compare
                        // against, so it never stops there.
                        return marginalMovement() <= minicpbp.util.BPConfig.CONVERGE_TOL;
                    case DECISION:
                        return decisionSettled(); // R3, Level 2 research
                    default:
                        // R0, production as it ships. CAVEAT: only really makes
                        // sense when branching on min entropy or max marginal.
                        if (smallEntropy <= MIN_VAR_ENTROPY) {
                            // at least one variable is nearly certain of its value
                            return true;
                        }
                        // marginals probably did not change either (and won't in the future)
                        return (iter > 1) /* give it a chance to kick in */
                                && (currentEntropy == previousEntropy);
                }
            });
            sched.endInvocation();
        } catch (InconsistencyException e) {
            // empty the queue and unset the scheduled status
            while (!propagationQueue.isEmpty())
                propagationQueue.remove().setScheduled(false);
            throw e;
        }
    }

    /**
     * Throws away every stored message and marginal and starts the invocation
     * from uniform. The historical behaviour of a real BP invocation.
     * <p>
     * Note what it costs and what it buys. It discards a consistent trail
     * snapshot of {marginals, localBelief} that backtracking
     * restored correctly, so no work can ever be inherited between nodes and
     * the reuse gate is the only saving available. It also makes the invariant
     * {@code b(v) = prod_c local_c(v)} hold trivially — {@code b} constant at
     * ONE, every factor uniform, hence proportional — which is why the
     * entailment leak the warm path suffers from was never visible here: this
     * wipes the whole product and rebuilds it from the active factors only.
     */
    private void coldReset() {
        Iterator<IntVar> iterator = variables.iterator();
        while (iterator.hasNext()) {
            iterator.next().resetMarginals();
        }
        Iterator<Constraint> iteratorC = constraints.iterator();
        while (iteratorC.hasNext()) {
            Constraint c = iteratorC.next();
            if (c.isActive())
                c.resetLocalBelief();
        }
        if (SCRUB_OUTSIDE_BELIEF) {
            // 2026-08-19 (TODO.md item 3 diagnosis): outsideBelief is a
            // plain (non-trailed) array; residue from previously visited
            // nodes survives backtracking. Scrub to isolate it as a
            // path-dependence carrier.
            iteratorC = constraints.iterator();
            while (iteratorC.hasNext()) {
                Constraint c = iteratorC.next();
                if (c.isActive())
                    c.resetOutsideBelief();
            }
        }
        prevOutsideBeliefRecorded = false;
    }

    /**
     * Establishes the invariant {@code b(v) = prod_c local_c(v)} over the
     * factors that are still <em>active</em>, at the entry of a warm-started
     * invocation (BP_WARM_START_EXPERIMENT.md F1, F2, F3). One pass, of the
     * same order as one flooding sweep's marginal rebuild.
     * <p>
     * Why the whole product rather than a targeted repair. Constraints
     * deactivate when entailed, and the dead factor's last message stays
     * multiplied into every neighbour's stored marginal forever: nothing
     * divides it out, and {@code BPGraph.resync} cannot, because it multiplies
     * only over active factors. A warm start therefore used to carry a spurious
     * product that grows with depth. Dividing the factor out on
     * {@code setActive(false)} would be cheaper and is riskier (division by a
     * zero-valued message), so it is an optimisation to consider only if this
     * pass shows up in the profile.
     * <p>
     * Applied under <em>every</em> schedule, including flooding. Flooding does
     * rebuild the product from the active factors at the end of each sweep, but
     * only at the end: {@code receiveMessages()} runs first and forms its
     * cavities from the contaminated marginal. And if the entry invariant
     * differed by schedule, the flood-versus-topo contrast would no longer be
     * about the schedule.
     *
     * @return true when the pass had to fall back to a cold reset, so no work
     * can be inherited and the dirty set must be seeded in full
     */
    private boolean warmEntry() {
        long t0 = System.nanoTime();
        Iterator<IntVar> iterator = variables.iterator();
        while (iterator.hasNext()) {
            iterator.next().resetMarginals();
        }
        Iterator<Constraint> iteratorC = constraints.iterator();
        while (iteratorC.hasNext()) {
            Constraint c = iteratorC.next();
            if (c.isActive())
                c.contributeMarginals();
        }
        boolean noMass = false;
        iterator = variables.iterator();
        while (iterator.hasNext()) {
            IntVar v = iterator.next();
            v.normalizeMarginals();
            if (beliefRep.isZero(v.maxMarginal())) noMass = true;
        }
        // F3: cold's first sweep is undamped, so warm's must be too. This was
        // set false only on the cold branch and inside BPtuneDamping, so after
        // the first sweep ever it stayed true and the first message of every
        // warm invocation blended in the ANCESTOR's last message — computed
        // before the branching decision and before the propagation that
        // followed it. A damping semantics nobody chose.
        prevOutsideBeliefRecorded = false;
        minicpbp.util.BPStats.warmEntryRebuilds++;
        minicpbp.util.BPStats.warmEntryNanos += System.nanoTime() - t0;
        if (!noMass) return false;
        // F2: propagation since the last invocation can have removed every value
        // that carried mass, leaving a zero vector no message passing recovers
        // from. The previous repair set that marginal to ONE on every in-domain
        // value while leaving every neighbouring localBelief untouched, which
        // breaks the invariant by a non-constant factor: the next in-place
        // update then reads a "cavity" proportional to 1/local_this, an
        // anti-message maximally contradicting what the constraint just said,
        // and writes it into the marginal for every later factor of the sweep to
        // read. A full cold reset is the only repair that keeps the invariant,
        // and it is exact.
        minicpbp.util.BPStats.warmEntryColdFallbacks++;
        coldReset();
        return true;
    }

    /* R2 (F5): one snapshot of the branchable marginals, in standard
     * representation. O(sum |D|) memory and per-sweep cost, the same order as
     * problemEntropy() and smallestVariableEntropy(), both already computed
     * every sweep. */
    private double[] convergeSnapshot = new double[0];
    private int[] convergeValues = new int[0];
    private boolean convergeSnapshotValid = false;

    /**
     * The largest movement, in standard representation, of any marginal of an
     * unbound branchable variable since the previous sweep — and refreshes the
     * snapshot. Returns {@code Double.MAX_VALUE} on the first sweep of an
     * invocation, which has nothing to compare against.
     * <p>
     * Restricting it to branchable variables is BP_SCHEDULING_TODO.md's Level 1.
     * On this corpus that restriction is inert, since {@code XCSP.solve}
     * declares every model variable branchable (P36), so expect it to cost more
     * sweeps than MIN_VAR_ENTROPY rather than fewer. Its value is that the
     * comparison between schedules becomes a comparison at equal convergence
     * instead of at equal overconfidence.
     * <p>
     * The index alignment across sweeps relies on {@code fillArray} returning
     * the same order twice, which holds within one invocation because BP never
     * changes a domain ({@code actOnZeroOneBelief} is false) and the order only
     * changes when the sparse set is modified.
     */
    private double marginalMovement() {
        int k = 0;
        double max = 0.0;
        Iterator<IntVar> iterator = variables.iterator();
        while (iterator.hasNext()) {
            IntVar v = iterator.next();
            if (v.isBound() || !v.isForBranching()) continue;
            if (convergeValues.length < v.size()) convergeValues = new int[2 * v.size()];
            int s = v.fillArray(convergeValues);
            if (convergeSnapshot.length < k + s) {
                double[] b = new double[Math.max(2 * (k + s), 64)];
                System.arraycopy(convergeSnapshot, 0, b, 0, k);
                convergeSnapshot = b;
            }
            for (int j = 0; j < s; j++) {
                double m = beliefRep.rep2std(v.marginal(convergeValues[j]));
                double d = Math.abs(m - convergeSnapshot[k]);
                if (d > max) max = d;
                convergeSnapshot[k++] = m;
            }
        }
        if (!convergeSnapshotValid) {
            convergeSnapshotValid = true;
            return Double.MAX_VALUE;
        }
        return max;
    }

    /**
     * No-bells-and-whistles Belief Propagation
     * runs for a specified number of iterations, without message damping
     */
    public void vanillaBP(int nbIterations) {
        notifyBeliefPropa();
        setDamp(false);
        try {
            // F4: this used to have the reset but no warm branch, so under
            // WARM_START it neither reset, nor re-established the invariant, nor
            // repaired a zero-mass marginal — a live trap for any caller.
            if (resetMarginalsBeforeBP && !minicpbp.util.BPConfig.WARM_START) {
                coldReset();
            } else if (minicpbp.util.BPConfig.WARM_START) {
                warmEntry();
            }
            for (int iter = 1; iter <= nbIterations; iter++) {
                BPiteration();
                Log.bpIteration(iter, variables);
            }
        } catch (InconsistencyException e) {
            // empty the queue and unset the scheduled status
            while (!propagationQueue.isEmpty())
                propagationQueue.remove().setScheduled(false);
            throw e;
        }
    }

    /**
     * Propagate following the right mode
     */
    public void propagateSolver(){
        switch (this.getMode()) {
            case SP:
                this.fixPoint();
                break;
            case BP:
                this.beliefPropa();
                break;
            case SBP:
                this.fixPoint();
                this.beliefPropa();
                break;
        }
    }

    /**
     * tunes message damping for Belief Propagation according to observed entropy
     */
    private void BPtuneDamping() {
        long t0 = System.nanoTime();
        long sweeps0 = minicpbp.util.BPStats.sweeps;
        try {
            BPtuneDampingImpl();
        } finally {
            // F12: this runs once per solver instance at the root, up to 4
            // trials of maxIter sweeps, OUTSIDE the monitored loop and before
            // search.solve is entered — but inside the timed region, hence
            // inside FloorBench's setupMs. With the reuse gate on and one
            // invocation per 34k nodes it can be most of all the BP work in a
            // run, and nothing separated it until now.
            minicpbp.util.BPStats.tuneDampingNanos += System.nanoTime() - t0;
            minicpbp.util.BPStats.tuneDampingSweeps += minicpbp.util.BPStats.sweeps - sweeps0;
        }
    }

    private void BPtuneDampingImpl() {
        final double MIN_DAMPING_FACTOR = 0.5;
        final double DAMPING_FACTOR_DELTA = 0.15;
        Constraint c;
        setDamp(true);
        setDampingFactor(1.0);
        boolean dampingFactorDetermined = false;
        double previousEntropy, currentEntropy;
        double previousDeltaEntropy, currentDeltaEntropy;
        int valleyCount;
        while (!dampingFactorDetermined) {
//            System.out.println("trying DAMPING FACTOR = " + dampingFactor());
            // start afresh
           Iterator<IntVar> iterator = variables.iterator();
            while (iterator.hasNext()) {
                iterator.next().resetMarginals();
            }
            Iterator<Constraint> iteratorC = constraints.iterator();
            while (iteratorC.hasNext()) {
                c = iteratorC.next();
                if (c.isActive())
                    c.resetLocalBelief();
            }
            prevOutsideBeliefRecorded = false;
            currentEntropy = 1.0;
            currentDeltaEntropy = 0;
            valleyCount = 0;
            dampingFactorDetermined = true;
            minicpbp.util.BPStats.tuneDampingTrials++;
            // BP dive
            for (int iter = 1; iter <= beliefPropaMaxIter; iter++) {
                BPiteration();
                previousEntropy = currentEntropy;
                previousDeltaEntropy = currentDeltaEntropy;
                currentEntropy = problemEntropy();
                currentDeltaEntropy = currentEntropy - previousEntropy;
                prevOutsideBeliefRecorded = true;
 //               System.out.println("iteration " + iter + "; problem entropy = " + currentEntropy + "; previous entropy = " + previousEntropy);
                if (currentEntropy == 0) { // either all branching vars are bound or BP says there's no solution
                    break;
                }
                if (previousDeltaEntropy <= 0 && currentDeltaEntropy > ENTROPY_TOLERANCE) {
 //                   System.out.println("valley");
                    valleyCount++;
                    if (valleyCount >= 2) {
 //                       System.out.println("two valleys ==> oscillation");
                        if (dampingFactor() - DAMPING_FACTOR_DELTA >= MIN_DAMPING_FACTOR) {
                            setDampingFactor(dampingFactor() - DAMPING_FACTOR_DELTA); // increase damping
                            dampingFactorDetermined = false;
                        }
                        break;
                    }
                }
            }
        }
        Log.bpDampingFactor(dampingFactor());
        if (dampingFactor() == 1.0) {
            setDamp(false);
        }
    }

    /**
     * a single iteration of Belief Propagation:
     * from variables to constraints, and then from constraints to variables
     * <p>
     * Used by the paths that must stay on the synchronous sweep whatever
     * schedule is configured: {@code vanillaBP} (documented as
     * no-bells-and-whistles BP) and {@code BPtuneDamping}, whose oscillation
     * test is calibrated on flooding and whose outcome should not become a
     * function of the schedule under measurement.
     */
    private void BPiteration() {
        if (floodSweep == null) floodSweep = new FloodingScheduler(this);
        floodSweep.sweep();
        minicpbp.util.BPStats.sweeps++;
    }

    private FloodingScheduler floodSweep;

    /**
     * Computes a global loss function from the constraints.
     * Currently it sums the losses from each constraint.
     */
    public double globalLossFct() {
        Iterator<Constraint> iteratorC = constraints.iterator();
        while (iteratorC.hasNext()) {
            iteratorC.next().setAuxVarsMarginalsWCounting();
        }
        double loss = 0;
        Constraint c;
        iteratorC = constraints.iterator();
        while (iteratorC.hasNext()) {
            c = iteratorC.next();
            if (c.isActive()) {
                loss += c.loss();
            }
        }
        return loss;
    }

    /**
     * Computes gradients for variable/value pairs from the constraints given outside beliefs.
     */
    public void globalGradients() {
        Iterator<Constraint> iteratorC = constraints.iterator();
        while (iteratorC.hasNext()) {
            iteratorC.next().setAuxVarsMarginalsWCounting();
        }
        Constraint c;
        iteratorC = constraints.iterator();
        Log.gradientHeader();
        while (iteratorC.hasNext()) {
            c = iteratorC.next();
            if (c.isActive()) {
                Log.gradientConstraint(c.getName());
                c.gradients();
            }
        }
    }

    /**
     * computes and returns the current problem entropy (avg normalized entropy of the variables)
     * note: only considers unbound branching variables
     */
    private double problemEntropy() {
        double sumNormalizedEntropy = 0.0;
        int nbUnboundBranchingVar = 0;
        Iterator<IntVar> iterator = variables.iterator();
        while (iterator.hasNext()) {
            IntVar v = iterator.next();
            if(!v.isBound() && v.isForBranching()){
                sumNormalizedEntropy += v.entropy()/Math.log(v.size());
                nbUnboundBranchingVar ++;
            }
        }
        return (nbUnboundBranchingVar == 0 ? 0.0 : sumNormalizedEntropy / nbUnboundBranchingVar);
    }

    /**
     * Probe K: the decision-keyed cross-node trigger
     * (BP_PROBE_PROTOCOL.md amendment 7). Returns true when this BP call may
     * SKIP: no ACTIVE constraint's scope contains both the tentative decision
     * variable and a variable touched since the last invocation on this path.
     * <p>
     * The tentative decision variable is the argmin-entropy unbound branching
     * variable computed from the inherited marginals after renormalizing them
     * over the current domains — the same repair the shipped skip path
     * performs, and the same scan as {@link #decisionSettled()} (F7 ordering).
     * One-hop locality is a heuristic: changes that cannot reach the decision
     * variable through any single factor are assumed unable to flip it. The
     * first invocation on a path (pathEpoch 0) always runs.
     */
    private boolean decisionTriggerSkip() {
        int pathEpoch = bpPathEpoch.value();
        if (pathEpoch <= 0) return false; // nothing computed yet on this path
        // the shipped skip path's repair, needed before reading entropies
        Iterator<IntVar> it = variables.iterator();
        while (it.hasNext()) it.next().normalizeMarginals();
        IntVar best = null;
        double bestEntropy = Double.MAX_VALUE;
        if (branchingOrder != null) {
            for (IntVar v : branchingOrder) {
                if (v.size() <= 1) continue;
                double h = v.entropy();
                if (h < bestEntropy) {
                    bestEntropy = h;
                    best = v;
                }
            }
        } else {
            Iterator<IntVar> it2 = variables.iterator();
            while (it2.hasNext()) {
                IntVar v = it2.next();
                if (v.isBound() || !v.isForBranching()) continue;
                double h = v.entropy();
                if (h < bestEntropy) {
                    bestEntropy = h;
                    best = v;
                }
            }
        }
        if (best == null) return true; // nothing left to decide
        IntVar dec = best.getBaseVar();
        Iterator<Constraint> ic = constraints.iterator();
        while (ic.hasNext()) {
            Constraint c = ic.next();
            if (!c.isActive()) continue;
            IntVar[] scope = c.getScope();
            boolean hasDec = false, hasTouched = false;
            for (int i = 0; i < scope.length; i++) {
                IntVar b = scope[i].getBaseVar();
                if (b == dec) hasDec = true;
                if (b.bpTouchStamp() >= pathEpoch) hasTouched = true;
                if (hasDec && hasTouched) return false; // coupled: run BP
            }
        }
        return true; // no active factor couples a change to the decision
    }

    /* decision-directed stopping (BP_SCHEDULING.md 1.4) */
    private IntVar decisionVar;
    private int decisionVal;
    private int stableDecisionSweeps;

    /**
     * True once the branching decision a min-entropy heuristic would take has
     * been the same for {@code BPConfig.STABLE_DECISION_SWEEPS} consecutive
     * sweeps.
     * <p>
     * The shipped rule stops the loop when some variable's entropy falls below
     * MIN_VAR_ENTROPY, which is a statement about how confident BP has become,
     * not about whether the decision has settled: measurement showed it stops
     * whichever schedule is most overconfident, while the marginals are still
     * far from a fixed point. What the solver needs is the decision, so this
     * watches the decision.
     * <p>
     * F7: it has to watch the decision search will actually take. The engine's
     * own {@code variables} stack is in registration order, while
     * {@code BranchingScheme.minEntropy} scans the id-sorted array built in
     * {@code XCSP.solve}; both break ties with a strict {@code <}, i.e. by
     * array order, so on tied entropies the two select different variables.
     * The array the heuristic scans is used when it has been registered.
     * <p>
     * Off-by-one, deliberately left as it is and documented instead: the
     * counter increments only on a <em>repeat</em> and the first sweep compares
     * against {@code null}, so {@code stableDecisionSweeps = k} means the same
     * decision was observed on {@code k+1} consecutive sweeps.
     * <p>
     * Residual caveat that cannot be fixed here: {@code valueWithMaxMarginal}
     * breaks its own ties by {@code fillArray} order, which the sparse set does
     * not restore on backtrack. Two calls within one invocation agree, which is
     * all this needs, but the value is not a function of the marginals alone.
     */
    /**
     * The variable dom/wdeg branching will select: strict min of
     * {@code size()/wDeg()} over the heuristic's scan order — the
     * branchingOrder array when registered, else the registration-order stack
     * restricted to branching variables. Replicates
     * {@code BranchingScheme.domWdeg}'s {@code selectMin} exactly. Shared by
     * the decisionRule=wdeg stop (probe O, amendment 11) and the
     * rootAtDecision schedule hint (probe Q, amendment 13); its inputs do not
     * move during BP sweeps, so within one invocation it is a constant.
     */
    private IntVar wdegDecisionVar() {
        IntVar best = null;
        double bestScore = Double.MAX_VALUE;
        if (branchingOrder != null) {
            for (IntVar v : branchingOrder) {
                if (v.size() <= 1) continue; // selectMin's predicate: unbound
                double s = ((double) v.size()) / ((double) v.wDeg());
                if (s < bestScore) {
                    bestScore = s;
                    best = v;
                }
            }
        } else {
            Iterator<IntVar> iterator = variables.iterator();
            while (iterator.hasNext()) {
                IntVar v = iterator.next();
                if (v.isBound() || !v.isForBranching()) continue;
                double s = ((double) v.size()) / ((double) v.wDeg());
                if (s < bestScore) {
                    bestScore = s;
                    best = v;
                }
            }
        }
        return best;
    }

    private boolean decisionSettled() {
        // Probe O (amendment 11): under decisionRule=wdeg the tracked variable
        // replicates BranchingScheme.domWdeg instead of minEntropy. The check
        // then degenerates to argmax stability of that one variable's marginal
        // — which is exactly what dom-wdeg-max-marginal branching needs watched.
        IntVar best;
        if (minicpbp.util.BPConfig.DECISION_RULE == minicpbp.util.BPConfig.DecisionRule.WDEG) {
            best = wdegDecisionVar();
        } else {
            best = null;
            double bestEntropy = Double.MAX_VALUE;
            if (branchingOrder != null) {
                for (IntVar v : branchingOrder) {
                    if (v.size() <= 1) continue; // selectMin's predicate: unbound
                    double h = v.entropy();
                    if (h < bestEntropy) {
                        bestEntropy = h;
                        best = v;
                    }
                }
            } else {
                Iterator<IntVar> iterator = variables.iterator();
                while (iterator.hasNext()) {
                    IntVar v = iterator.next();
                    if (v.isBound() || !v.isForBranching()) continue;
                    double h = v.entropy();
                    if (h < bestEntropy) {
                        bestEntropy = h;
                        best = v;
                    }
                }
            }
        }
        if (best == null) return true; // nothing left to decide
        int val = best.valueWithMaxMarginal();
        if (best == decisionVar && val == decisionVal) stableDecisionSweeps++;
        else stableDecisionSweeps = 0;
        decisionVar = best;
        decisionVal = val;
        return stableDecisionSweeps >= minicpbp.util.BPConfig.STABLE_DECISION_SWEEPS;
    }

    /**
     * computes and returns the smallest variable entropy
     * note: only considers unbound branching variables
     */
    private double smallestVariableEntropy() {
        double minEntropy = Double.MAX_VALUE;
       Iterator<IntVar> iterator = variables.iterator();
        while (iterator.hasNext()) {
            IntVar v = iterator.next();
            if (!v.isBound() && v.isForBranching()) {
                if (v.entropy() < minEntropy)
                    minEntropy = v.entropy();
            }
        }
        return minEntropy;
    }

    /**
     * computes and returns the smallest marginal
     * note: only considers unbound branching variables
     */
    private double smallestMarginal() {
        double minMarginal = Double.MAX_VALUE;
        Iterator<IntVar> iterator = variables.iterator();
        while (iterator.hasNext()) {
            IntVar v = iterator.next();
            if (!v.isBound() && v.isForBranching()) {
                if (v.minMarginal() < minMarginal)
                    minMarginal = v.minMarginal();
            }
        }
        return minMarginal;
    }
    private void propagate(Constraint c) {
        c.setScheduled(false);
        if (c.isActive()) {
            for (PropagateListener l : propagateConstraintListeners) {
                l.onPropagate(c);
            }
            c.propagate();
        }
    }

    @Override
    public Objective minimize(IntVar x) {
        return new Minimize(x);
    }

    @Override
    public Objective maximize(IntVar x) {
        return minimize(Factory.minus(x));
    }

    @Override
    public void post(Constraint c) {
        // no incremental propagation -- wait until all constraints have been posted
        post(c, false);
    }

    @Override
    public void post(Constraint c, boolean enforceFixpoint) {
        constraints.push(c);
        c.post();
        if (enforceFixpoint) {
            this.fixPoint();
        }
    }

    @Override
    public void post(BoolVar b) {
        post(b,false);
    }

    @Override
    public void post(BoolVar b, boolean enforceFixpoint){
        b.assign(true);
        if (enforceFixpoint) {
            this.fixPoint();
        }
    }

    @Override
    public IntVar[] sample(double fraction, IntVar[] vars) {
 	    final double initialAccuracy = 0.01; // relative error threshold of cell size wrt fraction
 	    final double maxNumerator = 100.0; // beyond this, we relax the accuracy
	    assert (fraction > 0) && (fraction < 1.0);
	    // the prime numbers under 100
	    int primes[] = {5,7,11,13,17,19,23,29,31,37,41,43,47,53,59,61,67,71,73,79,83,89,97}; // don't use very small primes because it leaves very little room for rhs of inequalities
	    int i;
	    // find largest domain element
	    int maxDomElt = 0;
	    for(i=0; i<vars.length; i++) {
	        if (vars[i].max() > maxDomElt)
		        maxDomElt = vars[i].max();
	    }
	    // find the smallest prime at least as large as maxDomElt
	    for(i=0; i<primes.length; i++)
	        if (primes[i] >= maxDomElt) break;
        if (i == primes.length) {
            Log.info("Domain values larger than currently recorded primes!");
            System.exit(0);
        }
        int p = primes[i];
        assert (p <= maxNumerator);
        //  	System.out.println("p = "+p);
        // compute a sufficiently accurate combination of m linear modular constraints
        double accuracy = initialAccuracy;
        int m;
        double exactNumerator, floorNum, ceilNum;
        LinkedList<Integer> factors = new LinkedList<>();
        do {
            m = 0;
            exactNumerator = fraction;
            while (factors.isEmpty()) {
                m++;
                exactNumerator *= p;
                // System.out.println("m="+m+"  exactNum="+exactNumerator+"  epsilon="+accuracy);
                if (Math.abs(exactNumerator - 1.0)/exactNumerator <= accuracy) break; // a system of equality constraints is sufficient
                if (exactNumerator > maxNumerator) {
                    // System.out.println("numerator is getting too big --- relax accuracy");
                    break;
                }
                floorNum = Math.floor(exactNumerator);
                ceilNum = Math.ceil(exactNumerator);
                if (exactNumerator - floorNum < ceilNum - exactNumerator) {
                    // try with floor first
                    factors = accurateFactorization(floorNum, exactNumerator, accuracy, m, p);
                    if (factors.isEmpty())
                        factors = accurateFactorization(ceilNum, exactNumerator, accuracy, m, p);
                }
                else {
                    // try with ceil first
                    factors = accurateFactorization(ceilNum, exactNumerator, accuracy, m, p);
                    if (factors.isEmpty())
                        factors = accurateFactorization(floorNum, exactNumerator, accuracy, m, p);
                }
            }
            accuracy *= 2;
        } while (exactNumerator > maxNumerator);
        // System.out.println("factors: "+factors.toString());
        int nbIneq = factors.size();
        int nbEq = m - nbIneq;
        // System.out.println("nb eq ; ineq "+nbEq+" ; "+nbIneq);
        // set up the linear modular constraints
        Constraint L = null;
        IntVar[] paramVars;
        if (nbEq>0) {
            int[][] Ae = new int[nbEq][vars.length];
            int[] be = new int[nbEq];
            for (i=0; i<nbEq; i++) {
                be[i] = rand.nextInt(p);
                for (int j=0; j<Ae[i].length; j++) {
                    Ae[i][j] = rand.nextInt(p);
                }
            }
            L = Factory.linEqSystemModP(Ae,vars,be,p);
            this.post(L);
            paramVars = ((LinEqSystemModP) L).getParamVars(); // parametric variables of GJE solved form
        }
        else {
            paramVars = vars;
        }
        if (nbIneq>0) {
            IntVar[] augmentedVars = Arrays.copyOf(paramVars, paramVars.length + 1);
            augmentedVars[paramVars.length] = Factory.makeIntVar(this, 1, 1);
            int[][] Ai = new int[nbIneq][augmentedVars.length];
            int[] bi = new int[nbIneq];
            for (i=0; i<nbIneq; i++) {
                bi[i] = factors.remove() - 1;
                // System.out.println("ineq rhs: "+bi[i]);
                for (int j=0; j<Ai[i].length; j++) {
                    Ai[i][j] = rand.nextInt(p);
                }
            }
            L = Factory.linIneqSystemModP(Ai,augmentedVars,bi,p);
            this.post(L);
        }
        return paramVars;
    }	    

    private LinkedList<Integer> accurateFactorization(double intNum, double exactNum, double accuracy, int m, int p) {
	    if (intNum <= 1.0)
	        return new LinkedList<>(); // cannot be factorized
	    double relError = Math.abs(exactNum - intNum) / exactNum;
        //   	System.out.println("exactNum="+exactNum+" intNum="+intNum+" rel error="+relError);
	    if (relError > accuracy)
	        return new LinkedList<>(); // not accurate enough
	    // decompose intNum into the fewest factors (and no more than m), all less than p
	    return factorize((int) intNum, m, p-1);
    }
    
    private LinkedList<Integer> factorize(int n, int m, int f) {
	    LinkedList<Integer> factors = new LinkedList<>();
	    while ((f>1) && (n>1)) {
	        while (n%f==0) {
		        factors.add(f);
		        n /= f;
	        }
	        f--;
	    }
 	    if ((n==1) && (factors.size()<=m))
	        return factors;
	    else
	        return new LinkedList<>(); // n could not be factorized (in at most m factors)
    }
}

