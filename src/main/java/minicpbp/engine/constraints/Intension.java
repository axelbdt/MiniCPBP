/*
 * mini-cpbp, replacing classic propagation by belief propagation
 * Copyright (c)  2019. by Gilles Pesant
 */

package minicpbp.engine.constraints;

import minicpbp.engine.core.AbstractConstraint;
import minicpbp.engine.core.Constraint;
import minicpbp.engine.core.HiddenGraph;
import minicpbp.engine.core.IntVar;
import minicpbp.engine.core.Solver;
import minicpbp.state.StateStack;
import minicpbp.util.exception.InconsistencyException;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

import static minicpbp.cp.Factory.makeIntVar;

/**
 * Encapsulated intension constraint.
 *
 * <p>The expression's reified decomposition (auxiliary Boolean variables,
 * primitive/reified constraints, root Boolean fixed to true) is built exactly
 * as the flat decomposition would build it, but inside a {@link HiddenGraph}:
 * the auxiliary variables and primitive constraints share the solver's
 * reversible state (backtracking restores them for free) yet do not appear in
 * the outer constraint network — the outer factor graph contains this single
 * {@code Intension} factor over the boundary variables.
 *
 * <p><b>Filtering.</b> Each outer boundary variable {@code x} has an internal
 * copy {@code y}. {@code propagate()} (1) removes from each {@code y} the
 * values its {@code x} has lost, (2) runs the internal primitive propagators
 * to a local fixpoint (they are scheduled into a local queue, never into the
 * solver's), and (3) removes from each {@code x} the values its {@code y} has
 * lost. This reproduces the flat decomposition's filtering: the same
 * propagators reach the same fixpoint, with the channeling
 * outer&nbsp;&rarr;&nbsp;Intension&nbsp;&rarr;&nbsp;outer replacing direct
 * sharing of the boundary variables. BP beliefs are never used to filter.
 *
 * <p><b>Nested BP.</b> {@code updateBelief()} computes the factor's weighted
 * messages by sum-product over the internal graph: each outer
 * variable&rarr;Intension cavity (the {@code outsideBelief} collected by
 * {@link AbstractConstraint#receiveMessages()}) is injected as a unary
 * evidence factor on the internal copy, the internal root Boolean is fixed to
 * true (a bound variable, hence conditioned), and flooding sweeps run over
 * the hidden factor graph. The sweeps stop when the normalized outgoing
 * messages to the boundary variables are settled,
 * {@code max_i TV(m_i^(t), m_i^(t-1)) < 0.01} with
 * {@code TV(p,q) = 1/2 sum_v |p(v)-q(v)|} (the convention of
 * {@link minicpbp.util.BinPackingBP}, {@link minicpbp.util.GccBP} and
 * {@link minicpbp.util.AssignmentBP}), subject to a minimum of
 * {@value #MIN_SWEEPS} sweeps and the solver's hard iteration cap
 * ({@link Solver#getMaxIter()}). Internal messages are kept warm between
 * calls: the internal local beliefs are reversible state, so they are
 * restored consistently on backtracking.
 *
 * <p>This constraint is not an exact weighted counter
 * ({@code isExactWCounting() == false}), so the zero/one shortcut of
 * {@link AbstractConstraint#sendMessages()} never turns approximate nested-BP
 * zeros into domain removals.
 */
public class Intension extends AbstractConstraint {

    /** stop threshold on the boundary-message total variation */
    private static final double TV_EPS = 0.01;
    /** minimum number of internal sweeps before the stability test may fire */
    private static final int MIN_SWEEPS = 2;

    private final IntVar[] outer;   // boundary variables (the outer scope)
    private final IntVar[] inner;   // their internal copies
    private final IntVar root;      // internal root Boolean, fixed to true
    // the hidden factor graph, on reversible stacks: reified primitives post
    // derived constraints DURING propagation (e.g. IsLessOrEqualVar posts
    // LessOrEqual once its Boolean is bound), and on the flat path those
    // posts live in the solver's StateStack, popped on backtracking; the
    // hidden graph must follow the same reversible semantics
    private final StateStack<Constraint> hiddenConstraints;
    private final StateStack<IntVar> hiddenVars;
    private final UnaryEvidence[] evidence;
    private final ArrayDeque<Constraint> localQueue = new ArrayDeque<>();

    // outgoing boundary messages, standard representation, indexed
    // [i][val - msgOfs[i]]; plain (non-reversible) scratch: prevOutMsg is
    // reseeded from the current internal state at every updateBelief() call
    private final double[][] outMsg;
    private final double[][] prevOutMsg;
    private final int[] msgOfs;

    // instrumentation
    private long nbBPCalls;
    private long nbBPConverged;

    /**
     * @param cp          the solver
     * @param outerVars   the boundary variables; must be distinct (dedupe
     *                    repeated variables before construction — repeated
     *                    occurrences in the expression map to one copy)
     * @param rootBuilder builds the reified decomposition over the internal
     *                    copies (same order as {@code outerVars}) and returns
     *                    the root Boolean as a 0/1 variable; every variable
     *                    and constraint it creates through the usual Factory
     *                    methods is captured into the hidden graph
     */
    public Intension(Solver cp, IntVar[] outerVars, Function<IntVar[], IntVar> rootBuilder) {
        super(cp, outerVars);
        setName("intension");
        for (int i = 0; i < outerVars.length; i++)
            for (int j = i + 1; j < outerVars.length; j++)
                if (outerVars[i] == outerVars[j])
                    throw new IllegalArgumentException("Intension scope must not repeat variables");
        this.outer = new IntVar[outerVars.length];
        System.arraycopy(outerVars, 0, this.outer, 0, outerVars.length);
        this.inner = new IntVar[outer.length];
        this.evidence = new UnaryEvidence[outer.length];
        this.outMsg = new double[outer.length][];
        this.prevOutMsg = new double[outer.length][];
        this.msgOfs = new int[outer.length];

        hiddenConstraints = new StateStack<>(cp.getStateManager());
        hiddenVars = new StateStack<>(cp.getStateManager());

        cp.beginHiddenCapture();
        IntVar r;
        try {
            for (int i = 0; i < outer.length; i++) {
                Set<Integer> dom = new HashSet<>();
                int s = outer[i].fillArray(domainValues);
                for (int j = 0; j < s; j++)
                    dom.add(domainValues[j]);
                inner[i] = makeIntVar(cp, dom);
                inner[i].setName("intension-copy-" + outer[i].getName());
                evidence[i] = new UnaryEvidence(cp, inner[i]);
                cp.post(evidence[i]);
                msgOfs[i] = inner[i].min();
                outMsg[i] = new double[inner[i].max() - inner[i].min() + 1];
                prevOutMsg[i] = new double[outMsg[i].length];
            }
            r = rootBuilder.apply(inner);
            r.assign(1); // condition the root Boolean to true
        } finally {
            absorb(cp.endHiddenCapture());
        }
        this.root = r;
        setExactWCounting(false);
    }

    /**
     * Takes ownership of a captured subgraph: hidden variables and
     * constraints go onto the reversible stacks, new constraints are diverted
     * to the local scheduler, and whatever got scheduled during the capture
     * joins the local queue. Called for the construction-time capture and for
     * every capture around a hidden {@code propagate()} (reified primitives
     * post derived constraints at propagation time).
     */
    private void absorb(HiddenGraph g) {
        for (IntVar v : g.variables())
            hiddenVars.push(v);
        for (Constraint c : g.constraints()) {
            c.setLocalScheduler(this::scheduleLocal);
            c.setWeight(1.0); // hidden factors are not visited by computeMinArity()
            hiddenConstraints.push(c);
        }
        localQueue.addAll(g.pending());
    }

    private void scheduleLocal(Constraint c) {
        localQueue.add(c);
    }

    @Override
    public void post() {
        for (IntVar x : outer)
            x.propagateOnDomainChange(this);
        propagate();
    }

    @Override
    public void propagate() {
        // 1. synchronize outer domains into the internal boundary copies
        for (int i = 0; i < outer.length; i++)
            syncRemove(outer[i], inner[i]);
        // 2. run the internal propagators to a local fixpoint
        localFixPoint();
        // 3. reflect internal boundary removals on the outer variables
        for (int i = 0; i < outer.length; i++)
            syncRemove(inner[i], outer[i]);
    }

    /** removes from {@code to} every value absent from {@code from} */
    private void syncRemove(IntVar from, IntVar to) {
        int s = to.fillArray(domainValues);
        for (int j = 0; j < s; j++)
            if (!from.contains(domainValues[j]))
                to.remove(domainValues[j]);
    }

    /**
     * Local counterpart of {@link Solver#fixPoint()} over the hidden
     * constraints: they are diverted here by their local scheduler, so they
     * never enter the outer propagation queue.
     */
    private void localFixPoint() {
        Solver cp = getSolver();
        try {
            while (!localQueue.isEmpty()) {
                Constraint c = localQueue.poll();
                c.setScheduled(false);
                if (c.isActive()) {
                    // a hidden propagate may post derived constraints (the
                    // reified-primitive pattern); capture them so they join
                    // the hidden graph instead of the outer network
                    cp.beginHiddenCapture();
                    try {
                        c.propagate();
                    } finally {
                        absorb(cp.endHiddenCapture());
                    }
                }
            }
        } catch (InconsistencyException e) {
            // empty the queue and unset the scheduled status, as fixPoint does
            while (!localQueue.isEmpty())
                localQueue.poll().setScheduled(false);
            throw e;
        }
    }

    @Override
    public void updateBelief() {
        // inject the outer cavities as unary evidence on the internal copies
        for (int i = 0; i < outer.length; i++) {
            int s = inner[i].fillArray(domainValues);
            if (outer[i].isBound()) {
                int v = outer[i].min();
                for (int j = 0; j < s; j++) {
                    int val = domainValues[j];
                    evidence[i].set(val, val == v ? beliefRep.one() : beliefRep.zero());
                }
            } else {
                for (int j = 0; j < s; j++) {
                    int val = domainValues[j];
                    evidence[i].set(val, outer[i].contains(val) ? outsideBelief(i, val) : beliefRep.zero());
                }
            }
        }
        // nested sum-product over the hidden graph, warm-started from the
        // internal local beliefs left by the previous call on this path.
        // Entry repair (the outer warmEntry's counterpart): marginals are not
        // reversible state, so after a backtrack they may be stale w.r.t. the
        // restored domains; renormalizing over the current domains makes them
        // a valid warm seed for the first receiveMessages of this call.
        for (int k = 0; k < hiddenVars.size(); k++)
            hiddenVars.get(k).normalizeMarginals();
        nbBPCalls++;
        boundaryMessageChange(); // seed prevOutMsg from the current state
        int cap = Math.max(MIN_SWEEPS, getSolver().getMaxIter());
        for (int t = 1; t <= cap; t++) {
            sweepHidden();
            double r = boundaryMessageChange();
            if (t >= MIN_SWEEPS && r < TV_EPS) {
                nbBPConverged++;
                break;
            }
        }
        // publish the outgoing boundary messages as this factor's local belief
        for (int i = 0; i < outer.length; i++) {
            int s = outer[i].fillArray(domainValues);
            for (int j = 0; j < s; j++) {
                int val = domainValues[j];
                double m = inner[i].contains(val)
                        ? beliefRep.std2rep(outMsg[i][val - msgOfs[i]])
                        : beliefRep.zero(); // removed internally by exact filtering
                setLocalBelief(i, val, m);
            }
        }
    }

    /**
     * One synchronous flooding iteration over the hidden factor graph, the
     * exact counterpart of the outer {@code FloodingScheduler.sweep()}.
     */
    private void sweepHidden() {
        int nc = hiddenConstraints.size();
        int nv = hiddenVars.size();
        for (int k = 0; k < nc; k++) {
            Constraint c = hiddenConstraints.get(k);
            if (c.isActive())
                c.receiveMessages();
        }
        for (int k = 0; k < nv; k++)
            hiddenVars.get(k).resetMarginals();
        for (int k = 0; k < nc; k++) {
            Constraint c = hiddenConstraints.get(k);
            if (c.isActive())
                c.sendMessages();
        }
        for (int k = 0; k < nv; k++)
            hiddenVars.get(k).normalizeMarginals();
    }

    /**
     * The outgoing message to boundary variable i is the internal copy's
     * marginal with the injected evidence divided out (uniform where the
     * evidence message is zero, the {@code IntVar.sendMessage} convention).
     * Returns {@code max_i TV(m_i^(t), m_i^(t-1))} of the normalized messages
     * in standard representation and overwrites {@code prevOutMsg}; the first
     * call of an invocation only seeds {@code prevOutMsg}.
     */
    private double boundaryMessageChange() {
        double maxTv = 0.0;
        for (int i = 0; i < outer.length; i++) {
            int s = inner[i].fillArray(domainValues);
            double tot = 0.0;
            for (int j = 0; j < s; j++) {
                int val = domainValues[j];
                outMsg[i][val - msgOfs[i]] =
                        beliefRep.rep2std(inner[i].sendMessage(val, evidence[i].message(val)));
                tot += outMsg[i][val - msgOfs[i]];
            }
            double tv = 0.0;
            for (int j = 0; j < s; j++) {
                int k = domainValues[j] - msgOfs[i];
                double m = (tot > 0.0) ? outMsg[i][k] / tot : 0.0;
                tv += Math.abs(m - prevOutMsg[i][k]);
                outMsg[i][k] = m;
                prevOutMsg[i][k] = m;
            }
            tv *= 0.5;
            if (tv > maxTv)
                maxTv = tv;
        }
        return maxTv;
    }

    public long nbBPCalls() {
        return nbBPCalls;
    }

    /** number of {@code updateBelief()} calls that stopped on the TV test */
    public long nbBPConverged() {
        return nbBPConverged;
    }

    /** the internal boundary copies, for tests */
    public IntVar[] internalCopies() {
        return inner;
    }

    /** the hidden constraints (reversible view), for tests */
    public StateStack<Constraint> hiddenConstraints() {
        return hiddenConstraints;
    }

    /** the hidden variables (reversible view), for tests */
    public StateStack<IntVar> hiddenVariables() {
        return hiddenVars;
    }

    /**
     * Unary evidence factor on an internal boundary copy: its local belief is
     * the injected outer cavity, so the internal marginal of the copy becomes
     * {@code evidence * prod(internal factor messages)} and the internal
     * cavity read by every internal factor includes the outer information.
     * No filtering role.
     */
    private static final class UnaryEvidence extends AbstractConstraint {
        private final double[] ev; // representation space, indexed by val - ofs
        private final int evOfs;

        UnaryEvidence(Solver cp, IntVar y) {
            super(cp, new IntVar[]{y});
            setName("intension-evidence");
            evOfs = y.min();
            ev = new double[y.max() - y.min() + 1];
            java.util.Arrays.fill(ev, beliefRep.one());
            setExactWCounting(false);
        }

        void set(int val, double b) {
            ev[val - evOfs] = b;
        }

        /** the (normalized) message this factor last sent to its variable */
        double message(int val) {
            return localBelief(0, val);
        }

        @Override
        public void post() {
        }

        @Override
        public void propagate() {
        }

        @Override
        public void updateBelief() {
            int s = vars[0].fillArray(domainValues);
            for (int j = 0; j < s; j++)
                setLocalBelief(0, domainValues[j], ev[domainValues[j] - evOfs]);
        }
    }
}
