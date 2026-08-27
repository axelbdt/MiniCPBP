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

import minicpbp.state.StateBool;
import minicpbp.state.StateDoubleArray;

import minicpbp.util.Belief;
import minicpbp.util.Log;

import minicpbp.util.exception.NotImplementedException;

/**
 * Abstract class most of the constraints
 * should extend.
 */
public abstract class AbstractConstraint implements Constraint {

    /* the in-place update leaves the marginal unnormalised; these bound the
     * scale it is allowed to drift to before a normalisation is forced */
    private static final double SCALE_FLOOR = 1.0E-100;
    private static final double SCALE_CEIL = 1.0E100;

    private String name;
    /**
     * The solver in which the constraint is created
     */
    private final Solver cp;
    private boolean scheduled = false;
    private final StateBool active;
    /** incremental dirty seeding: is the stored message stale on this path? */
    private final StateBool bpStale;

    // one restorable row per scope position (BP_COST_PROFILE.md Part 4 item
    // 5, deep version): the row snapshots once per level on first write, so a
    // localBelief read is a plain array access instead of a StateDouble
    // double-indirection plus virtual call, and a row rewrite costs one trail
    // entry instead of one per cell
    private StateDoubleArray[] localBelief;
    private double[][] outsideBelief;
    // needed for message damping. Deliberately NOT trailed (BP_COST_PROFILE.md
    // Part 4 item 4): it is written during sweep k and read during sweep k+1 of
    // the SAME invocation, behind cp.prevOutsideBeliefRecorded(), and that flag
    // is set false at every invocation entry (coldReset, warmEntry,
    // BPtuneDamping). No backtrack can occur between two sweeps of one
    // invocation, so no value is ever read across a restore, and trailing it
    // only doubled the trailed-write traffic of every damped sweep.
    private double[][] prevOutsideBelief;
    // scratch state of the in-place (Gauss-Seidel) factor update, allocated on
    // first use so the flooding schedule pays nothing for it
    private double[][] cavityBelief;  // variable-to-constraint message before damping
    private double[][] prevLocalBelief; // local belief as it was before updateBelief()
    private double[] msgResidual;     // per scope position, how far the last message moved
    private boolean[] cavityUniform;  // per scope position, was the cavity replaced by uniform?
    private boolean[] cavityExact;    // ... or is it the true quotient on every value?
    private boolean[] cavityZero;     // ... and is that true quotient zero on every value?
    private boolean duplicateScope;   // two scope positions share one base variable
    private double weight; // an optional nonnegative weight applied to the constraint's local belief
    protected Belief beliefRep;
    private int[] ofs;
    protected IntVar[] vars; // all the variables in the scope of the constraint
    private int maxDomainSize;
    protected int[] domainValues; // an array large enough to hold any domain of vars
    protected double[] beliefValues; // an auxiliary array as large as domainValues
    private boolean exactWCounting = false;
    private boolean updateBeliefWarningPrinted = false;
    private boolean weightedCountingWarningPrinted = false;

    private int failureCount;

    public AbstractConstraint(Solver cp, IntVar[] vars) {
        this.cp = cp;
        active = cp.getStateManager().makeStateBool(true);
        // nothing has been computed yet, so the stored message (uniform) is not
        // the message this factor would produce: it must run at least once
        bpStale = cp.getStateManager().makeStateBool(true);
        beliefRep = cp.getBeliefRep();
        this.vars = new IntVar[vars.length];
        System.arraycopy(vars,0,this.vars,0,vars.length); // required if constraint sets up offseted vars in the same array
        switch (cp.getWeighingScheme()) {
            case SAME:
                weight = 1.0;
                break;
            case ARITY:
                // will be set in MiniCP.computeMinArity()
                break;
        }
        localBelief = new StateDoubleArray[vars.length];
        ofs = new int[vars.length];
        outsideBelief = new double[vars.length][];
        prevOutsideBelief = new double[vars.length][];

        maxDomainSize = 0;
        for (int i = 0; i < vars.length; i++) {
            vars[i].registerConstraint(this);
            ofs[i] = vars[i].min();
            // no belief yet; initialized to ONE (certainly true) in order to
            // retrieve the first var-to-constraint msg correctly
            localBelief[i] = cp.getStateManager().makeStateDoubleArray(
                    vars[i].max() - vars[i].min() + 1, beliefRep.one());
            outsideBelief[i] = new double[vars[i].max() - vars[i].min() + 1];
            prevOutsideBelief[i] = new double[outsideBelief[i].length];
            java.util.Arrays.fill(prevOutsideBelief[i], beliefRep.one()); // arbitrary
            maxDomainSize = Math.max(maxDomainSize, vars[i].max() - vars[i].min() + 1);
        }
        domainValues = new int[maxDomainSize];
        beliefValues = new double[maxDomainSize];
        failureCount = 0;
    }

    public IntVar[] getScope() {
        return vars;
    }

    public int arity() {
        return vars.length;
    }

    public int dynamicArity() {
        int k = 0;
        for (int i = 0; i < vars.length; i++) {
            if (!vars[i].isBound()) k++;
        }
        return k;
    }

    /*public double getWeight() {
		switch(cp.getWeighingScheme()) {
			case SAME:
				return 1.0;
			case ARITY:
				// assumes all model variables have already been declared/registered
				return 1.0 + ((double) vars.length - (double) cp.minArity())/ ((double) cp.getVariables().size());
			case ANTI:
                return 1.0 - ((double) vars.length - (double) cp.minArity()) / ((double) cp.getVariables().size());
			default:
				throw new NotImplementedException();
			}

	}*/

	public void incrementFailureCount() {
		failureCount+=1;
	}

	public int getFailureCount() {
		return failureCount;
	}

    public void post() {}

    public Solver getSolver() {
        return cp;
    }

    public void propagate() {}

    public void setScheduled(boolean scheduled) {
        this.scheduled = scheduled;
    }

    public boolean isScheduled() {
        return scheduled;
    }

    public void setActive(boolean active) {
        if (!active && this.active.value() && minicpbp.util.BPConfig.INCREMENTAL_DIRTY) {
            // A deactivated factor is divided out of every neighbour's marginal
            // by the warm-entry rebuild, so every scope variable's cavity for
            // every OTHER incident factor changes. Entailment is detected inside
            // this constraint's own propagate(), so only the trigger variable is
            // guaranteed to have changed its domain: mark the whole scope
            // (BP_WARM_START_EXPERIMENT.md section 1.3).
            for (int i = 0; i < vars.length; i++) vars[i].getBaseVar().bpTouch();
        }
        this.active.setValue(active);
    }

    public boolean isActive() {
        return active.value();
    }

    public boolean bpStale() {
        return bpStale.value();
    }

    public void setBpStale(boolean stale) {
        bpStale.setValue(stale);
    }

    protected void setExactWCounting(boolean exact) {
        this.exactWCounting = exact;
    }

    protected boolean isExactWCounting() {
        return exactWCounting;
    }

    public void setWeight(double w) {
        assert w >= 0 : "c A constraint's weight should be nonnegative";
        weight = w;
    }

    public double weight() {
        return weight;
    }

    protected double localBelief(int i, int val) {
        return localBelief[i].read()[val - ofs[i]];
    }

    protected double setLocalBelief(int i, int val, double b) {
        // 2026-08-19 (TODO.md item 1): constraints with signed belief circuits
        // (complement/subtract: NegTableCT, Maximum, ...) can emit -epsilon
        // where the exact value is 0 (floating-point cancellation). Negative
        // beliefs violate the probability contract downstream (Soules U^3
        // requires a nonnegative matrix; crashed LoBiancoBound on
        // CarSequencing). Clamp at the framework boundary, counted, so no
        // producer needs case-by-case fixes and the violation rate is
        // measured. NaN is deliberately NOT masked here.
        if (beliefRep.rep2std(b) < 0) {
            minicpbp.util.BeliefClampStats.recordClamp(beliefRep.rep2std(b));
            b = beliefRep.zero();
        }
        localBelief[i].update()[val - ofs[i]] = b;
        return b;
    }

    protected double outsideBelief(int i, int val) {
        return outsideBelief[i][val - ofs[i]];
    }

    protected double setOutsideBelief(int i, int val, double b) {
        outsideBelief[i][val - ofs[i]] = b;
        return b;
    }

    protected double prevOutsideBelief(int i, int val) {
        return prevOutsideBelief[i][val - ofs[i]];
    }

    protected double setPrevOutsideBelief(int i, int val, double b) {
        prevOutsideBelief[i][val - ofs[i]] = b;
        return b;
    }

    interface getBelief {
        double get(int i, int val);
    }

    interface setBelief {
        double set(int i, int val, double b);
    }

    /* 2026-08-25 (BP_COST_PROFILE.md Lever C): monomorphic normalizations that
     * reuse an already-filled domainValues[0..s-1].
     *
     * normalizeBelief() calls vars[i].fillArray() itself and reaches the belief
     * arrays through the getBelief/setBelief functional interfaces, which are
     * instantiated from six distinct lambda pairs and are therefore megamorphic
     * at the call site. In updateMessagesInPlace the domain has just been
     * filled, so the fillArray is pure waste: the hot path walked each domain
     * ~14 times where 3 passes suffice. Semantics are identical to
     * normalizeBelief, including setLocalBelief's negative clamp. */
    private void normalizeOutsideCached(int i, int s) {
        double[] ob = outsideBelief[i];
        int o = ofs[i];
        if (s == 1) { // variable is bound
            ob[domainValues[0] - o] = beliefRep.one();
            return;
        }
        for (int j = 0; j < s; j++)
            beliefValues[j] = ob[domainValues[j] - o];
        double normalizingConstant = beliefRep.summation(beliefValues, s);
        if (beliefRep.isZero(normalizingConstant)) // soon-to-be-empty domain
            return;
        for (int j = 0; j < s; j++)
            ob[domainValues[j] - o] = beliefRep.divide(beliefValues[j], normalizingConstant);
    }

    private void normalizeLocalCached(int i, int s) {
        int o = ofs[i];
        if (s == 1) { // variable is bound
            localBelief[i].update()[domainValues[0] - o] = beliefRep.one();
            return;
        }
        double[] lb = localBelief[i].read();
        for (int j = 0; j < s; j++)
            beliefValues[j] = lb[domainValues[j] - o];
        double normalizingConstant = beliefRep.summation(beliefValues, s);
        if (beliefRep.isZero(normalizingConstant)) // soon-to-be-empty domain
            return;
        lb = localBelief[i].update();
        for (int j = 0; j < s; j++) {
            double b = beliefRep.divide(beliefValues[j], normalizingConstant);
            if (beliefRep.rep2std(b) < 0) { // as in setLocalBelief
                minicpbp.util.BeliefClampStats.recordClamp(beliefRep.rep2std(b));
                b = beliefRep.zero();
            }
            lb[domainValues[j] - o] = b;
        }
    }

    private void dampenMessagesCached(int i, int s) {
        double lambda = beliefRep.std2rep(cp.dampingFactor());
        double oneMinusLambda = beliefRep.complement(lambda);
        double[] ob = outsideBelief[i];
        double[] pb = prevOutsideBelief[i];
        int o = ofs[i];
        for (int j = 0; j < s; j++) {
            int k = domainValues[j] - o;
            ob[k] = beliefRep.add(beliefRep.multiply(lambda, ob[k]),
                    beliefRep.multiply(oneMinusLambda, pb[k]));
        }
        normalizeOutsideCached(i, s);
    }

    private void normalizeBelief(int i, getBelief f1, setBelief f2) {
        int s = vars[i].fillArray(domainValues);
        if (s == 1) { // variable is bound
            f2.set(i, domainValues[0], beliefRep.one());
            return;
        }
        for (int j = 0; j < s; j++) {
            beliefValues[j] = f1.get(i, domainValues[j]);
        }
        double normalizingConstant = beliefRep.summation(beliefValues, s);
        if (beliefRep.isZero(normalizingConstant)) // temporary state of a soon-to-be-empty domain
            return;
        for (int j = 0; j < s; j++) {
            int val = domainValues[j];
            f2.set(i, val, beliefRep.divide(f1.get(i, val), normalizingConstant));
            assert f1.get(i, val) <= beliefRep.one() && f1.get(i, val) >= beliefRep.zero() : "c Should be normalized! f1.get(i,val) = " + f1.get(i, val)
                    + " (constraint " + getName() + ", var " + vars[i].getName() + ", val " + val + ", normalizingConstant " + normalizingConstant + ")";
        }
    }

    public void resetLocalBelief() {
        for (int i = 0; i < vars.length; i++) {
            int s = vars[i].fillArray(domainValues);
            double uniform = beliefRep.divide(beliefRep.one(),(double) s);
            for (int j = 0; j < s; j++) {
                setLocalBelief(i, domainValues[j], uniform);
            }
        }
    }
    public void resetOutsideBelief() {
        for (int i = 0; i < vars.length; i++) {
            int s = vars[i].fillArray(domainValues);
            double uniform = beliefRep.divide(beliefRep.one(),(double) s);
            for (int j = 0; j < s; j++) {
                setOutsideBelief(i, domainValues[j], uniform);
            }
        }
    }

    private void dampenMessages(int i) {
        double lambda = beliefRep.std2rep(cp.dampingFactor());
        double oneMinusLambda = beliefRep.complement(lambda);
        int s = vars[i].fillArray(domainValues);
        for (int j = 0; j < s; j++) {
            int val = domainValues[j];
            setOutsideBelief(i, val, beliefRep.add(beliefRep.multiply(lambda, outsideBelief(i, val)), beliefRep.multiply(oneMinusLambda, prevOutsideBelief(i, val))));
        }
        normalizeBelief(i, (j, val) -> outsideBelief(j, val), (j, val, b) -> setOutsideBelief(j, val, b));
    }

    public void receiveMessages() {
        for (int i = 0; i < vars.length; i++) {
            if (vars[i].isBound()) {
                setOutsideBelief(i, vars[i].min(), beliefRep.one());
            } else {
                int s = vars[i].fillArray(domainValues);
                for (int j = 0; j < s; j++) {
                    int val = domainValues[j];
                    assert localBelief(i, val) <= beliefRep.one() && localBelief(i, val) >= beliefRep.zero() : "c Should be normalized! localBelief(i,val) = " + localBelief(i, val);
                    setOutsideBelief(i, val, vars[i].sendMessage(val, beliefRep.pow(localBelief(i, val), this.weight)));
                }
                normalizeBelief(i, (j, val) -> outsideBelief(j, val),
                        (j, val, b) -> setOutsideBelief(j, val, b));
                if (cp.dampingMessages()) {
                    if (cp.prevOutsideBeliefRecorded())
                        dampenMessages(i);
                    for (int j = 0; j < s; j++) {
                        int val = domainValues[j];
                        setPrevOutsideBelief(i, val, outsideBelief(i, val));
                    }
                }
            }
        }
    }

    public void sendMessages() {
        updateBelief();
 //       System.out.println(getName()+".sendMessages()");
        for (int i = 0; i < vars.length; i++) {
            if (!vars[i].isBound()) { // if the variable is bound, it is pointless to send a "certainly true" message
                normalizeBelief(i, (j, val) -> localBelief(j, val),
                        (j, val, b) -> setLocalBelief(j, val, b));
                int s = vars[i].fillArray(domainValues);
 //               System.out.print(vars[i].getName()+": ");
                for (int j = 0; j < s; j++) {
                    int val = domainValues[j];
                    double localB = localBelief(i, val);
 //                   System.out.print(val+" "+localB+", ");
                    assert localB <= beliefRep.one() && localB >= beliefRep.zero() : "c Should be normalized! localB = " + localB;
                    if (getSolver().actingOnZeroOneBelief() && isExactWCounting()) {
                        if (beliefRep.isZero(localB)) { // no support from this constraint
  			                // System.out.println(getName()+".sendMessages(): removing value "+val+" from the domain of "+vars[i].getName()+vars[i].toString()+" because its local belief is ZERO");
                            vars[i].remove(val); // standard domain consistency filtering
                            getSolver().fixPoint();
                        } else if (beliefRep.isOne(localB)) { // backbone var for this constraint (and hence for all of them)
  			                // System.out.println(getName()+".sendMessages(): assigning value "+val+" from the domain of "+vars[i].getName()+vars[i].toString()+" because its local belief is ONE");
                            vars[i].assign(val);
                            getSolver().fixPoint();
                            break; // all other values in this loop will have been removed from the domain
                        } else
                            vars[i].receiveMessage(val, beliefRep.pow(localB, this.weight));
                    } else
                        vars[i].receiveMessage(val, beliefRep.pow(localB, this.weight));
                }
            }
        }
//        System.out.println();
    }

    /**
     * In-place (Gauss-Seidel) counterpart of {@code receiveMessages()} followed
     * by {@code sendMessages()}: the outgoing messages are published into the
     * marginals of the scope immediately, so a factor executed later in the
     * same sweep reads them. The flooding schedule instead resets every
     * marginal between the two phases and rebuilds it as the product of all
     * local beliefs, which is why information there needs a whole sweep to
     * cross one factor.
     * <p>
     * Marginal bookkeeping. With the marginal held as
     * {@code b(v) = prod_c local_c(v)}, replacing this constraint's message
     * means dividing the old factor out and multiplying the new one in. The
     * quotient {@code b(v)/local_this(v)} is the cavity distribution, which
     * {@code receiveMessages()} already computes as the variable-to-constraint
     * message; it is kept here before damping is applied, since damping must
     * affect what the factor reads and not what the search reads.
     * <p>
     * The quotient is not always the true cavity, and only then is a rebuild
     * owed. The rule is one line: <em>resync exactly when the vector that was
     * multiplied into the marginal was not the true cavity.</em> Three cases,
     * each counted:
     * <ul>
     * <li>the old message was exactly zero for some value.
     * {@code IntVar.sendMessage} answers uniform there, which is the right
     * input for a factor but the wrong factor for reconstructing a marginal.
     * It only matters where that message stops being zero — while it stays
     * zero the product is zero either way — and then the variable is handed to
     * {@code resync}, which rebuilds the exact product over its factors;</li>
     * <li>the quotient left the representable range because the message divided
     * out was denormal. The vector carries no information and a weighted counter
     * fed a vector of zeros answers NaN rather than zero, so "uniform" is used
     * instead. It is the right <em>input</em> for the counter but it is not the
     * cavity, so publishing {@code cavity * local} would drop every other
     * factor's message from the product and break the invariant for the rest of
     * the invocation; that variable is resynced, counted as
     * {@code BPStats.resyncCavityFallback} (BP_WARM_START_EXPERIMENT.md D4).
     * Measured frequency: zero on Ramsey and EFPA, 26 in 31M on Sports;</li>
     * <li>the quotient is zero on every value. This is <em>not</em> a failure:
     * with no zero divisor involved, every value of the variable is excluded by
     * some factor other than this one, and the zero vector is the exact cavity.
     * The counter is fed uniform because it cannot consume a zero vector, but
     * the marginal is published from the true zero cavity and no rebuild is
     * owed. Likewise a zero product built from an exact cavity is the exact
     * marginal. Together these were 93.5% of the resyncs on
     * RamseyPartition-3-24 (BP_COST_PROFILE.md Lever B).</li>
     * </ul>
     *
     * @param resync recomputes a variable's marginal from all its factors, or
     *               null to accept the uniform-cavity approximation
     * @return the largest absolute change, in standard representation, of any
     * message this constraint sent: the factor residual used by residual
     * scheduling
     */
    public double updateMessagesInPlace(MarginalResync resync) {
        if (cavityBelief == null) {
            cavityBelief = new double[vars.length][];
            prevLocalBelief = new double[vars.length][];
            msgResidual = new double[vars.length];
            cavityUniform = new boolean[vars.length];
            cavityExact = new boolean[vars.length];
            cavityZero = new boolean[vars.length];
            for (int i = 0; i < vars.length; i++) {
                cavityBelief[i] = new double[outsideBelief[i].length];
                prevLocalBelief[i] = new double[outsideBelief[i].length];
            }
            // duplicateScope is a by-product of the base->positions index
            if (posOfBase == null) buildPosOfBase();
        }
        // phase 1: read the cavity distributions, remembering the messages that
        // are about to be overwritten
        for (int i = 0; i < vars.length; i++) {
            cavityUniform[i] = false;
            cavityZero[i] = false;
            cavityExact[i] = false;
            if (vars[i].isBound()) {
                setOutsideBelief(i, vars[i].min(), beliefRep.one());
                continue;
            }
            int s = vars[i].fillArray(domainValues);
            for (int j = 0; j < s; j++) {
                int val = domainValues[j];
                double old = beliefRep.pow(localBelief(i, val), this.weight);
                prevLocalBelief[i][val - ofs[i]] = old;
                assert !beliefRep.isZero(old) || vars[i].zeroMsgCount(val) >= 1
                        : "message zero on " + vars[i].getName() + "=" + val
                        + " but the zero-aware product does not count it";
            }
            // The cavity comes from the zero-aware product, so a zero divisor
            // needs no placeholder and the marginal needs no rescaling for one.
            // The cavity is a quotient, so it can leave the representable range
            // where the message being divided out is denormal. That vector
            // carries no information, and feeding one to a weighted counter
            // produces NaN rather than zero (setLocalBelief deliberately does
            // not mask NaN, and SumDC's forward/backward DP propagates it), so
            // "uniform" is used instead -- the answer IntVar.sendMessage already
            // gives for a single zero-valued message -- and phase 3 repairs the
            // marginal. Measured on RamseyPartition-3-24 and EFPA-3-7-7-07 with
            // topo + warm start: zero occurrences, and 26 in 31M positions on
            // SportsScheduling-10 (BP_COST_PROFILE.md Lever B).
            boolean bad = false, empty = true;
            for (int j = 0; j < s; j++) {
                int val = domainValues[j];
                double c = vars[i].cavity(val, prevLocalBelief[i][val - ofs[i]]);
                if (!isFinite(beliefRep.rep2std(c))) bad = true;
                else if (!beliefRep.isZero(c)) empty = false;
                setOutsideBelief(i, val, c);
            }
            // The cavity is now exact on every value unless the quotient left
            // the representable range. Phase 3 needs to know, because a marginal
            // built from an exact cavity is the true product, whatever it is,
            // and never needs repair.
            cavityExact[i] = !bad;
            if (bad) {
                minicpbp.util.BPStats.cavityFallbacks++;
                // The uniform answer is the right input for a weighted counter,
                // but it is NOT the cavity: writing cavity*local into the
                // marginal then drops every other factor's message from the
                // product, which breaks b(v) = prod_c local_c(v) by a
                // non-constant factor and leaves it broken in the trail for the
                // whole subtree — D2's mechanism in miniature
                // (BP_WARM_START_EXPERIMENT.md D4). Phase 3 resyncs instead.
                cavityUniform[i] = true;
                double uniform = beliefRep.divide(beliefRep.one(), (double) s);
                for (int j = 0; j < s; j++) setOutsideBelief(i, domainValues[j], uniform);
                for (int j = 0; j < s; j++)
                    cavityBelief[i][domainValues[j] - ofs[i]] = uniform;
            } else if (empty) {
                // An all-zero cavity is not a failure: it is the exact answer.
                // No divisor was zero here (a zero divisor yields a nonzero
                // uniform, which would have cleared `empty`), so every value's
                // quotient is a true quotient, and every one came out zero --
                // every value of this variable is excluded by some factor other
                // than this one. Two consequences, and the second is where the
                // cost was: the zero vector goes into cavityBelief so phase 3
                // publishes the correct zero product, and the counter is fed
                // uniform because it cannot consume an all-zero input. Before
                // 2026-08-25 both got uniform and the marginal was rebuilt from
                // scratch over every incident factor, which on
                // RamseyPartition-3-24 was 1 433 289 of 3 220 886 resyncs, each
                // recomputing the same zero (BP_COST_PROFILE.md Lever B).
                minicpbp.util.BPStats.cavityExactZero++;
                cavityZero[i] = true;
                for (int j = 0; j < s; j++)
                    cavityBelief[i][domainValues[j] - ofs[i]] = beliefRep.zero();
                double uniform = beliefRep.divide(beliefRep.one(), (double) s);
                for (int j = 0; j < s; j++) setOutsideBelief(i, domainValues[j], uniform);
            } else {
                normalizeOutsideCached(i, s);
                for (int j = 0; j < s; j++) {
                    int val = domainValues[j];
                    cavityBelief[i][val - ofs[i]] = outsideBelief(i, val);
                }
            }
            if (cp.dampingMessages()) {
                if (cp.prevOutsideBeliefRecorded())
                    dampenMessagesCached(i, s);
                for (int j = 0; j < s; j++) {
                    int val = domainValues[j];
                    setPrevOutsideBelief(i, val, outsideBelief(i, val));
                }
            }
        }
        // phase 2: the weighted counting
        updateBelief();
        // phase 3: publish the new messages into the marginals
        double residual = 0.0;
        for (int i = 0; i < vars.length; i++) {
            msgResidual[i] = 0.0;
            if (vars[i].isBound()) continue; // a "certainly true" message changes nothing
            int s = vars[i].fillArray(domainValues);
            normalizeLocalCached(i, s);
            double mass = 0.0;
            for (int j = 0; j < s; j++) {
                int val = domainValues[j];
                double b = beliefRep.pow(localBelief(i, val), this.weight);
                double old = prevLocalBelief[i][val - ofs[i]];
                double delta = Math.abs(beliefRep.rep2std(b) - beliefRep.rep2std(old));
                if (delta > msgResidual[i]) msgResidual[i] = delta;
                // the zero-aware product tracks messages, not marginals, so it
                // is updated whatever the cavity turned out to be
                vars[i].messageReplaced(val, old, b);
                double m = beliefRep.multiply(cavityBelief[i][val - ofs[i]], b);
                mass += beliefRep.rep2std(m);
                if (Double.isNaN(m)) {
                    // name the producer: IntVar.setMarginal only knows the
                    // variable, and a NaN here means the constraint's belief
                    // circuit produced one (setLocalBelief deliberately does
                    // not mask NaN, only negatives)
                    throw new ArithmeticException("NaN message from "
                            + getClass().getSimpleName() + " (" + getName() + ") to "
                            + vars[i].getName() + " on value " + val
                            + ": cavity=" + cavityBelief[i][val - ofs[i]] + " localBelief=" + b);
                }
                vars[i].setMarginal(val, m);
            }
            if (msgResidual[i] > residual) residual = msgResidual[i];
            // cavity * local is the product over every factor whenever the
            // cavity is exact, so the marginal it publishes needs no repair,
            // whatever it is -- zero included. Only two things can still leave a
            // marginal that is not the product: a cavity that left the
            // representable range and had to be replaced by uniform, and a scope
            // that holds two views of one variable, where the second write
            // discards the first message instead of multiplying it in.
            boolean noMass = mass <= 0.0;
            assert !cavityZero[i] || noMass : "an all-zero cavity must give a zero product";
            if (noMass) minicpbp.util.BPStats.zeroMassLeftAlone++;
            if (resync != null && (duplicateScope || (cavityUniform[i] && !noMass))) {
                minicpbp.util.BPStats.marginalResyncs++;
                if (duplicateScope) minicpbp.util.BPStats.resyncDuplicateScope++;
                if (cavityUniform[i] && !noMass) minicpbp.util.BPStats.resyncCavityFallback++;
                resync.resync(vars[i].getBaseVar());
            } else if (!noMass && (mass < SCALE_FLOOR || mass > SCALE_CEIL)) {
                vars[i].normalizeMarginals(); // keep the product away from underflow
            }
        }
        return residual;
    }

    /**
     * How far the message this constraint last sent to the variable at scope
     * position {@code i} moved, in standard representation. Zero before the
     * first in-place update.
     */
    /** java 8 has no Double.isFinite on the primitive path we want inlined */
    private static boolean isFinite(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v);
    }

    public double messageResidual(int i) {
        return msgResidual == null ? 0.0 : msgResidual[i];
    }

    /**
     * Multiplies this constraint's current local belief into the marginals of
     * the variables of its scope whose base variable is {@code base}, which is
     * the send half of {@code sendMessages()} without the weighted counting.
     * Used to rebuild a marginal as the exact product of the messages it
     * receives.
     */
    /* 2026-08-25 (BP_COST_PROFILE.md Lever C): base variable -> the scope
     * positions that are views of it, built once because the scope never
     * changes. contributeMarginal used to scan the whole scope with a virtual
     * isBound() and getBaseVar() per position to find the one or two positions
     * that match; BPGraph.resync calls it once per incident factor per resync,
     * and on the Ramsey family the resync path is 69% of BP wall clock. */
    private java.util.IdentityHashMap<IntVar, int[]> posOfBase;

    private void buildPosOfBase() {
        java.util.IdentityHashMap<IntVar, int[]> m = new java.util.IdentityHashMap<>();
        for (int i = 0; i < vars.length; i++) {
            IntVar base = vars[i].getBaseVar();
            int[] cur = m.get(base);
            if (cur == null) {
                m.put(base, new int[]{i});
            } else {
                // Two positions of one scope may be views of the same variable
                // (c(x, minus(x))), and then they share a marginal: writing the
                // second one would discard the first message instead of
                // multiplying it in. Rare, and the scope never changes, so it is
                // detected once and those marginals are rebuilt exactly.
                int[] nxt = java.util.Arrays.copyOf(cur, cur.length + 1);
                nxt[cur.length] = i;
                m.put(base, nxt);
                duplicateScope = true;
            }
        }
        posOfBase = m;
    }

    public void contributeMarginal(IntVar base) {
        if (posOfBase == null) buildPosOfBase();
        int[] pos = posOfBase.get(base);
        if (pos == null) return; // base is not in this scope
        for (int p = 0; p < pos.length; p++) {
            int i = pos[p];
            if (vars[i].isBound()) continue;
            int s = vars[i].fillArray(domainValues);
            for (int j = 0; j < s; j++) {
                int val = domainValues[j];
                vars[i].receiveMessage(val, beliefRep.pow(localBelief(i, val), this.weight));
            }
        }
    }

    /**
     * {@code contributeMarginal} for the whole scope at once. Deliberately the
     * same arithmetic, in the same order, as {@code BPGraph.resync} performs
     * one variable at a time, so that the warm-entry rebuild and a resync of
     * the same variable produce the same number.
     */
    public void contributeMarginals() {
        for (int i = 0; i < vars.length; i++) {
            if (vars[i].isBound()) continue; // a bound variable holds no product
            int s = vars[i].fillArray(domainValues);
            for (int j = 0; j < s; j++) {
                int val = domainValues[j];
                vars[i].receiveMessage(val, beliefRep.pow(localBelief(i, val), this.weight));
            }
        }
    }

    /**
     * Returns the semantic loss, computed using weighted model counting.
     * To be defined in the actual constraint.
     * <p>
     * Default behaviour: returns zero (tautology constraint)
     */
    public double loss() {
        receiveMessagesWCounting(); // collect pmfs over the domains of the variables in the scope of the constraint
        return -Math.log(beliefRep.rep2std(weightedCounting()));
    }

    /**
     * Computes gradients for variable/value pairs from the constraints given outside beliefs.
     */
    public void gradients() {
        receiveMessagesWCounting(); // collect pmfs over the domains of the variables in the scope of the constraint
        double wc = beliefRep.rep2std(weightedCounting());
        if (wc == 0) {
            Log.gradient("*** Warning! Infinite loss; mitigating by producing some very large gradients ***");
            wc = 1.0E-10;
        }
        updateBelief();
        for (int i = 0; i < vars.length; i++) {
            Log.gradient("* "+vars[i].getName());
            normalizeBelief(i, (j, val) -> localBelief(j, val), (j, val, b) -> setLocalBelief(j, val, b));
            int s = vars[i].fillArray(domainValues);
            double sumOverDomain = 0;
            for (int j = 0; j < s; j++) {
                sumOverDomain += localBelief(i, domainValues[j]);
            }
            for (int j = 0; j < s; j++) {
                double gradient = (sumOverDomain - 2.0*localBelief(i, domainValues[j])) / wc;
                Log.gradient(domainValues[j]+": "+gradient);
            }
        }
    }

    /**
       * Updates its local belief given the outside beliefs.
       * To be defined in the actual constraint.
       * <p>
       * Default behaviour: uniform belief
       * CAVEAT: may set zero/one beliefs but should not directly remove domain values (only done in sendMessages() if actOnZeroOneBelief flag is set)
       */
    protected void updateBelief() {
        if (!updateBeliefWarningPrinted) {
            if (getName() != null) // do not print warning for unnamed constraint
                Log.warn("method updateBelief not implemented yet for " + getName() + " constraint. Using uniform belief instead.");
            updateBeliefWarningPrinted = true;
        }
        for (int i = 0; i < vars.length; i++) {
            java.util.Arrays.fill(localBelief[i].update(), beliefRep.one()); // will be normalized
        }
    }

    /**
     * Collects messages (outside beliefs) from the variables in its scope.
     * Used to compute the semantic loss and gradients via weighted counting
     */
    public void receiveMessagesWCounting() {
        for (int i = 0; i < vars.length; i++) {
            int s = vars[i].fillArray(domainValues);
            for (int j = 0; j < s; j++) {
                int val = domainValues[j];
                setOutsideBelief(i, val, vars[i].sendMessage(val, beliefRep.one()));
            }
        }
    }

    /**
     * Optionally computes and sets the marginals of auxiliary variables created in the constraint's implementation.
     * To be optionally defined in the actual constraint.
     * <p>
     * Default behaviour: does nothing
     */
    public void setAuxVarsMarginalsWCounting() {}

    /**
     * Computes and returns the weighted count of solutions (i.e. weighted model counting) given the outside beliefs.
     * To be defined in the actual constraint.
     * !!!IMPORTANT NOTE!!!: the computation may rely on the fact that variables have all their beliefs initialized to beliefRep.one() upon creation (in StateSparseWeightedSet)
     * <p>
     * Default behaviour: returns beliefRep.one() (tautology constraint)
     */
    protected double weightedCounting() {
        if (!weightedCountingWarningPrinted) {
            if (getName() != null) // do not print warning for unnamed constraint
                Log.warn("method weightedCounting not implemented yet for " + getName() + " constraint. Returning beliefRep.one() instead.");
            weightedCountingWarningPrinted = true;
        }
        return beliefRep.one();
    }

    @Override
    public String getName() {
        return this.name;
    }

    @Override
    public void setName(String name) {
        this.name = name;
    }
}
