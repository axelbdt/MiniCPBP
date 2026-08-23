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
import minicpbp.state.StateDouble;

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

    private StateDouble[][] localBelief;
    private double[][] outsideBelief;
    private StateDouble[][] prevOutsideBelief; // needed for message damping
    // scratch state of the in-place (Gauss-Seidel) factor update, allocated on
    // first use so the flooding schedule pays nothing for it
    private double[][] cavityBelief;  // variable-to-constraint message before damping
    private double[][] prevLocalBelief; // local belief as it was before updateBelief()
    private double[] msgResidual;     // per scope position, how far the last message moved
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
        localBelief = new StateDouble[vars.length][];
        ofs = new int[vars.length];
        outsideBelief = new double[vars.length][];
        prevOutsideBelief = new StateDouble[vars.length][];

        maxDomainSize = 0;
        for (int i = 0; i < vars.length; i++) {
            vars[i].registerConstraint(this);
            ofs[i] = vars[i].min();
            localBelief[i] = new StateDouble[vars[i].max() - vars[i].min() + 1];
            outsideBelief[i] = new double[vars[i].max() - vars[i].min() + 1];
            prevOutsideBelief[i] = new StateDouble[outsideBelief[i].length];
            for (int j = 0; j < localBelief[i].length; j++) {
                localBelief[i][j] = cp.getStateManager().makeStateDouble(beliefRep.one()); // no belief yet; initialized to ONE (certainly true) in order to retrieve the first var-to-constraint msg correctly
                prevOutsideBelief[i][j] = cp.getStateManager().makeStateDouble(beliefRep.one()); // arbitrary
            }
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
        this.active.setValue(active);
    }

    public boolean isActive() {
        return active.value();
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
        return localBelief[i][val - ofs[i]].value();
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
        return localBelief[i][val - ofs[i]].setValue(b);
    }

    protected double outsideBelief(int i, int val) {
        return outsideBelief[i][val - ofs[i]];
    }

    protected double setOutsideBelief(int i, int val, double b) {
        outsideBelief[i][val - ofs[i]] = b;
        return b;
    }

    protected double prevOutsideBelief(int i, int val) {
        return prevOutsideBelief[i][val - ofs[i]].value();
    }

    protected double setPrevOutsideBelief(int i, int val, double b) {
        return prevOutsideBelief[i][val - ofs[i]].setValue(b);
    }

    interface getBelief {
        double get(int i, int val);
    }

    interface setBelief {
        double set(int i, int val, double b);
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
     * The quotient is not defined where the old message was exactly zero
     * ({@code IntVar.sendMessage} answers uniform there, which is right for a
     * factor input but wrong for reconstructing a marginal), and it is not
     * defined when the reconstructed marginal carries no mass. Both cases hand
     * the variable to {@code resync}, which recomputes the exact product over
     * the variable's factors; both are counted.
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
            for (int i = 0; i < vars.length; i++) {
                cavityBelief[i] = new double[outsideBelief[i].length];
                prevLocalBelief[i] = new double[outsideBelief[i].length];
            }
            // Two positions of one scope may be views of the same variable
            // (c(x, minus(x))), and then they share a marginal: writing the
            // second one would discard the first message instead of
            // multiplying it in. Rare, and the scope never changes, so it is
            // detected once and those marginals are rebuilt exactly.
            java.util.Set<IntVar> seen = new java.util.HashSet<>();
            for (int i = 0; i < vars.length; i++) {
                if (!seen.add(vars[i].getBaseVar())) {
                    duplicateScope = true;
                    break;
                }
            }
        }
        // phase 1: read the cavity distributions, remembering the messages that
        // are about to be overwritten
        for (int i = 0; i < vars.length; i++) {
            if (vars[i].isBound()) {
                setOutsideBelief(i, vars[i].min(), beliefRep.one());
                continue;
            }
            int s = vars[i].fillArray(domainValues);
            boolean zeroMessage = false;
            for (int j = 0; j < s; j++) {
                int val = domainValues[j];
                double old = beliefRep.pow(localBelief(i, val), this.weight);
                prevLocalBelief[i][val - ofs[i]] = old;
                if (beliefRep.isZero(old)) zeroMessage = true;
            }
            // Where the old message was zero the quotient is undefined and
            // IntVar.sendMessage answers a uniform 1/|D| instead. That answer
            // is only on the right scale if the marginal is normalised, so
            // normalise it here -- and only here, since the marginal is left
            // unnormalised the rest of the time (the cavity is normalised at
            // every step, which keeps the scale bounded, and the schedule
            // normalises once per sweep for the engine's entropy tests).
            if (zeroMessage) vars[i].normalizeMarginals();
            // The cavity is a quotient, so it can leave the representable range
            // where the message being divided out is denormal, and it can lose
            // all its mass where a domain change removed every value that
            // carried any. Both give a vector that carries no information, and
            // feeding one to a weighted counter produces NaN rather than zero
            // (setLocalBelief deliberately does not mask NaN, and SumDC's
            // forward/backward DP propagates it). Answer "uniform" instead,
            // which is what IntVar.sendMessage already answers for a single
            // zero-valued message. Both cases need warm-started marginals to
            // be reachable, and both are counted.
            boolean bad = false, empty = true;
            for (int j = 0; j < s; j++) {
                int val = domainValues[j];
                double c = vars[i].sendMessage(val, prevLocalBelief[i][val - ofs[i]]);
                if (!isFinite(beliefRep.rep2std(c))) bad = true;
                else if (!beliefRep.isZero(c)) empty = false;
                setOutsideBelief(i, val, c);
            }
            if (bad || empty) {
                minicpbp.util.BPStats.cavityFallbacks++;
                double uniform = beliefRep.divide(beliefRep.one(), (double) s);
                for (int j = 0; j < s; j++) setOutsideBelief(i, domainValues[j], uniform);
            } else {
                normalizeBelief(i, (j, val) -> outsideBelief(j, val),
                        (j, val, b) -> setOutsideBelief(j, val, b));
            }
            for (int j = 0; j < s; j++) {
                int val = domainValues[j];
                cavityBelief[i][val - ofs[i]] = outsideBelief(i, val);
            }
            if (cp.dampingMessages()) {
                if (cp.prevOutsideBeliefRecorded())
                    dampenMessages(i);
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
            normalizeBelief(i, (j, val) -> localBelief(j, val),
                    (j, val, b) -> setLocalBelief(j, val, b));
            int s = vars[i].fillArray(domainValues);
            double mass = 0.0;
            boolean resurrected = false;
            for (int j = 0; j < s; j++) {
                int val = domainValues[j];
                double b = beliefRep.pow(localBelief(i, val), this.weight);
                double old = prevLocalBelief[i][val - ofs[i]];
                double delta = Math.abs(beliefRep.rep2std(b) - beliefRep.rep2std(old));
                if (delta > msgResidual[i]) msgResidual[i] = delta;
                // A value whose message was zero has a zero marginal, so the
                // cavity could not be recovered by division and the uniform
                // answer of IntVar.sendMessage was used instead. That only
                // matters where the message stops being zero: while it stays
                // zero the product is zero either way, which is what the
                // flooding sweep computes too.
                if (beliefRep.isZero(old) && !beliefRep.isZero(b)) resurrected = true;
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
            if (resync != null && (resurrected || mass <= 0.0 || duplicateScope)) {
                minicpbp.util.BPStats.marginalResyncs++;
                resync.resync(vars[i].getBaseVar());
            } else if (mass < SCALE_FLOOR || mass > SCALE_CEIL) {
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
    public void contributeMarginal(IntVar base) {
        for (int i = 0; i < vars.length; i++) {
            if (vars[i].isBound() || vars[i].getBaseVar() != base) continue;
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
            for (int j = 0; j < localBelief[i].length; j++) {
                localBelief[i][j].setValue(beliefRep.one()); // will be normalized
            }
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
