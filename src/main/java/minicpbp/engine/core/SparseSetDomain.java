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

import minicpbp.state.StateSparseWeightedSet;
import minicpbp.util.Belief;

import java.util.NoSuchElementException;
import java.util.Random;
import java.util.HashMap;
import java.util.ArrayList;
import java.lang.Math;

/**
 * Implementation of a domain with a sparse-set
 */
public class SparseSetDomain implements IntDomain {
    private StateSparseWeightedSet domain;
    private int[] domainValues; // an array large enough to hold the domain
    private double[] beliefValues; // an auxiliary array as large as domainValues
    private HashMap<Integer, ArrayList<Double>> impactValues; //a map containing the registered impact of an assignement
    private Solver cp;
    private Belief beliefRep;
    static Random rand;

    /* the zero-aware product, see IntDomain. Plain arrays indexed by v - ofs:
     * not reversible, rebuilt by resetMarginals at every BP invocation entry. */
    private final int ofs;
    /* The product of the non-zero messages, held as mantissa * 2^exponent. A
     * plain double cannot hold it: it is a product over every incident message,
     * so it decays with the degree of the variable and reaches exactly zero,
     * where it becomes indistinguishable from a logical zero -- a wrong answer
     * rather than a lost digit. Measured before this representation existed: on
     * DeBruijn-2-7, 179 of 62 865 marginals differed from the exact product by
     * more than 1e-3, every one a false zero of that kind. Splitting off a
     * binary exponent costs nothing in precision, since scaling by a power of
     * two is exact. */
    private final double[] nzMant;
    private final int[] nzExp;
    private final int[] zeroCnt;     // how many messages are exactly zero
    /* the largest nzExp over the live domain, the reference the cavity is
     * expressed against, cached because one factor reads a whole domain in a row */
    private int nzRef;
    private boolean nzRefValid;

    public SparseSetDomain(Solver cp, int min, int max) {
        domain = new StateSparseWeightedSet(cp, max - min + 1, min);
        domainValues = new int[max - min + 1];
        beliefValues = new double[max - min + 1];
        ofs = min;
        nzMant = new double[max - min + 1];
        nzExp = new int[max - min + 1];
        zeroCnt = new int[max - min + 1];
        impactValues = new HashMap<Integer, ArrayList<Double>>();
        this.cp = cp;
        beliefRep = cp.getBeliefRep();
        rand = cp.getRandomNbGenerator();
        java.util.Arrays.fill(nzMant, 1.0);
    }

    /**
     * 2026-08-19 (TODO.md item 3, run-length anomaly): the sparse set restores
     * MEMBERSHIP on backtrack but not the ORDER of its elements
     * (StateSparseSet.exchangePositions writes untrailed arrays). Every
     * consumer of fillArray therefore sees a history-dependent enumeration:
     * belief DPs accumulate in a different order (ulp-level differences) and
     * valueWithMaxMarginal / selectMin break exact ties by that order. This
     * debug flag canonicalizes the enumeration to isolate the effect.
     */
    private static final boolean SORT_DOMAIN_VALUES =
            Boolean.getBoolean("minicpbp.debug.sortDomainValues");

    @Override
    public int fillArray(int[] dest) {
        int s = domain.fillArray(dest);
        if (SORT_DOMAIN_VALUES) java.util.Arrays.sort(dest, 0, s);
        return s;
    }

    @Override
    public int min() {
        return domain.min();
    }

    @Override
    public int max() {
        return domain.max();
    }

    @Override
    public int size() {
        return domain.size();
    }

    @Override
    public boolean contains(int v) {
        return domain.contains(v);
    }

    @Override
    public boolean isBound() {
        return domain.size() == 1;
    }

    @Override
    public void remove(int v, DomainListener l) {
        if (domain.contains(v)) {
            boolean maxChanged = max() == v;
            boolean minChanged = min() == v;
            domain.remove(v);
            if (domain.size() == 0)
                l.empty();
            l.change();
            if (maxChanged) l.changeMax();
            if (minChanged) l.changeMin();
            if (domain.size() == 1) l.bind();
            l.op(DomainOpKind.REMOVE, v);
        }
    }

    @Override
    public void removeAllBut(int v, DomainListener l) {
        if (domain.contains(v)) {
            if (domain.size() != 1) {
                boolean maxChanged = max() != v;
                boolean minChanged = min() != v;
                domain.removeAllBut(v);
                if (domain.size() == 0)
                    l.empty();
                l.bind();
                l.change();
                if (maxChanged) l.changeMax();
                if (minChanged) l.changeMin();
                l.op(DomainOpKind.ASSIGN, v);
            }
        } else {
            domain.removeAll();
            l.empty();
        }
    }

    @Override
    public void removeBelow(int value, DomainListener l) {
        if (domain.min() < value) {
            domain.removeBelow(value);
            switch (domain.size()) {
                case 0:
                    l.empty();
                    break;
                case 1:
                    l.bind();
                default:
                    l.changeMin();
                    l.change();
                    break;
            }
            l.op(DomainOpKind.REMOVE_BELOW, value);
        }
    }

    @Override
    public void removeAbove(int value, DomainListener l) {
        if (domain.max() > value) {
            domain.removeAbove(value);
            switch (domain.size()) {
                case 0:
                    l.empty();
                    break;
                case 1:
                    l.bind();
                default:
                    l.changeMax();
                    l.change();
                    break;
            }
            l.op(DomainOpKind.REMOVE_ABOVE, value);
        }
    }

    @Override
    public int randomValue() {
        if (domain.isEmpty())
            throw new NoSuchElementException();
        int s = fillArray(domainValues);
        return domainValues[rand.nextInt(s)];
    }


    @Override
    public int biasedWheelValue() {
        if (domain.isEmpty())
            throw new NoSuchElementException();
        int s = fillArray(domainValues);
	    // to avoid this linear-time step, could replace max by upper bound 1
	    // alternatively, could decide to maintain max marginal of domain
        double max = beliefRep.zero();
        for (int j = 0; j < s; j++) {
            int v = domainValues[j];
            if (marginal(v) > max) {
                max = marginal(v);
            }
        }
	    // stochastic acceptance algorithm
	    while (true) {
            int v = domainValues[rand.nextInt(s)];
	        if (rand.nextDouble() < marginal(v)/max)
		    return v;
	    }
    }

    @Override
    public double marginal(int v) {
        return domain.weight(v);
    }

    @Override
    public void setMarginal(int v, double m) { domain.setWeight(v, m); }

    @Override
    public void resetMarginals() {
        int s = fillArray(domainValues);
        for (int j = 0; j < s; j++) {
            setMarginal(domainValues[j], beliefRep.one());
        }
        // the whole array, not just the live domain: a value removed at this
        // node is back in the domain at the next one, and these are not trailed
        java.util.Arrays.fill(nzMant, 1.0);
        java.util.Arrays.fill(nzExp, 0);
        java.util.Arrays.fill(zeroCnt, 0);
        nzRefValid = false;
    }

    /* The band the mantissa is kept in. Wide, because leaving it costs an
     * exponent update and an invalidation of the cached reference, and the whole
     * point of the fast path below is that a message near 1 -- which is what a
     * normalised message usually is -- never leaves it. */
    private static final double NZ_HI = 1e19;
    private static final double NZ_LO = 1e-19;

    /* Realigning is what makes this representation safe, but it must not be on
     * the hot path: messageReplaced runs once per belief cell per factor update,
     * the single most frequent operation in the solver, and an unnecessary
     * exponent update also invalidates the cached reference exponent and forces
     * a domain scan in the next cavity read. Measured cost of realigning every
     * time, against holding the product in a plain double: EFPA-3-7-7-07 7099 ->
     * 8413 ms. So the common case is one multiply and one range test. */
    private void nzStore(int i, double p, double operand, boolean divide) {
        nzRefValid = false; // a boolean store, not a rescan: nzRefExp is lazy
        if (p >= NZ_LO && p <= NZ_HI) { // in band: nothing else to do
            nzMant[i] = p;
            return;
        }
        int e = Math.getExponent(operand);
        double aligned = Math.scalb(operand, -e); // exact: a power of two
        nzMant[i] = divide ? nzMant[i] / aligned : nzMant[i] * aligned;
        nzExp[i] += divide ? -e : e;
        int em = Math.getExponent(nzMant[i]);
        if (nzMant[i] != 0.0 && (em > 512 || em < -512)) {
            nzMant[i] = Math.scalb(nzMant[i], -em);
            nzExp[i] += em;
        }
        minicpbp.util.BPStats.nzRescales++;
    }

    private void nzMul(int i, double msg) {
        nzStore(i, nzMant[i] * msg, msg, false);
    }

    private void nzDiv(int i, double msg) {
        nzStore(i, nzMant[i] / msg, msg, true);
    }

    /**
     * The exponent every cavity of this variable is expressed against: the
     * largest one in the live domain. The mantissa is deliberately NOT counted,
     * so that a value whose exponent already equals the reference needs no
     * shifting at all -- the fast path in cavity(). Keeping the mantissa inside
     * a 38-decade band bounds the resulting slop, so a cavity can still only
     * round to zero when it is some 265 decades below the largest, which
     * normalising the vector would do anyway. Recomputed at most once per domain
     * read -- a factor reads a whole domain in a row with no write in between --
     * so it costs the order of the read, never of a belief cell.
     */
    private int nzRefExp() {
        if (nzRefValid) return nzRef;
        int s = domain.fillArray(domainValues);
        int mx = Integer.MIN_VALUE;
        for (int j = 0; j < s; j++) {
            int i = domainValues[j] - ofs;
            if (zeroCnt[i] > 1) continue; // cannot contribute to any cavity
            if (nzMant[i] == 0.0) continue;
            if (nzExp[i] > mx) mx = nzExp[i];
        }
        nzRef = mx;
        nzRefValid = true;
        return mx;
    }

    @Override
    public double cavity(int v, double ownMsg) {
        int i = v - ofs;
        double own = beliefRep.rep2std(ownMsg);
        int z = zeroCnt[i];
        if (own == 0.0) {
            assert z >= 1 : "a zero message must be counted";
            // the asking factor is one of the zeros; if it is the only one the
            // others multiply to nzProd, otherwise some other factor zeroes v
            if (z > 1) return beliefRep.zero();
        } else if (z > 0) {
            return beliefRep.zero(); // some other factor zeroes v
        }
        // Express the quotient against the largest exponent in the domain, so
        // the whole vector shares one scale. A value more than ~1000 binary
        // decades below the largest is returned as zero, which is what
        // normalising the vector would make of it anyway.
        double m = nzMant[i];
        // Fast path, and it is the usual one: every exponent starts at zero and
        // only the rare realignment moves it, so the shift is almost always
        // nothing and the quotient is one division. This is per belief cell, so
        // the difference is not small: EFPA-3-7-7-07 pays 19% for the careful
        // path taken unconditionally.
        if (nzExp[i] == nzRefExp()) {
            double q = own == 0.0 ? m : m / own;
            if (q > 0.0 && q < Double.MAX_VALUE) return beliefRep.std2rep(q);
        }
        int e = nzExp[i];
        if (own != 0.0) {
            int eo = Math.getExponent(own);
            m /= Math.scalb(own, -eo);
            e -= eo;
        }
        if (m == 0.0) return beliefRep.zero();
        int em = Math.getExponent(m);
        // frac in [1,2), so the shift is at most zero and the result cannot
        // overflow; it reaches zero only for a value that really is ~2^-1074
        // below the largest, which normalising the vector would also make zero
        return beliefRep.std2rep(Math.scalb(Math.scalb(m, -em), e + em - nzRefExp()));
    }

    @Override
    public void multiplyInMessage(int v, double b) {
        int i = v - ofs;
        double std = beliefRep.rep2std(b);
        if (std == 0.0) { if (++zeroCnt[i] == 2) nzRefValid = false; }
        else nzMul(i, std);
        setMarginal(v, beliefRep.multiply(marginal(v), b));
    }

    @Override
    public void messageReplaced(int v, double oldMsg, double newMsg) {
        int i = v - ofs;
        double oldStd = beliefRep.rep2std(oldMsg);
        double newStd = beliefRep.rep2std(newMsg);
        if (oldStd == 0.0) {
            assert zeroCnt[i] >= 1 : "a zero message must be counted";
            if (zeroCnt[i]-- == 2) nzRefValid = false;
        } else {
            nzDiv(i, oldStd);
        }
        if (newStd == 0.0) { if (++zeroCnt[i] == 2) nzRefValid = false; }
        else nzMul(i, newStd);
    }

    @Override
    public int zeroMsgCount(int v) {
        return zeroCnt[v - ofs];
    }

    @Override
    public void normalizeMarginals() {

        int s = fillArray(domainValues);
        if (s == 1) { // corresponding variable is bound
            setMarginal(domainValues[0], beliefRep.one());
            return;
        }
        for (int j = 0; j < s; j++) {
            beliefValues[j] = marginal(domainValues[j]);
        }
        double normalizingConstant = beliefRep.summation(beliefValues, s);
        if (beliefRep.isZero(normalizingConstant)) // all marginals are zero (actOnZeroOneBelief set to false?)
            return;
        for (int j = 0; j < s; j++) {
            int v = domainValues[j];
            setMarginal(v, beliefRep.divide(marginal(v), normalizingConstant));
            assert marginal(v) <= beliefRep.one() && marginal(v) >= beliefRep.zero() : "marginal(v) = " + marginal(v);
        }
    }

    @Override
    public double maxMarginal() {
        if (domain.isEmpty())
            throw new NoSuchElementException();
        double max = beliefRep.zero();
        int s = fillArray(domainValues);
        for (int j = 0; j < s; j++) {
            int v = domainValues[j];
            if (marginal(v) > max) {
                max = marginal(v);
            }
        }
        return max;
    }

    @Override
    public int valueWithMaxMarginal() {
        if (domain.isEmpty())
            throw new NoSuchElementException();
        int s = fillArray(domainValues);
        int valWithMax = domainValues[0];
        double max = marginal(valWithMax);
        for (int j = 1; j < s; j++) {
            int v = domainValues[j];
            if (marginal(v) > max) {
                max = marginal(v);
                valWithMax = v;
            }
        }
        return valWithMax;
    }

    @Override
    public double minMarginal() {
        if (domain.isEmpty())
            throw new NoSuchElementException();
        double min = beliefRep.one();
        int s = fillArray(domainValues);
        for (int j = 0; j < s; j++) {
            int v = domainValues[j];
            if (marginal(v) < min) {
                min = marginal(v);
            }
        }
        return min;
    }

    @Override
    public int valueWithMinMarginal() {
        if (domain.isEmpty())
            throw new NoSuchElementException();
        int s = fillArray(domainValues);
        int valWithMin = domainValues[0];
        double min = marginal(valWithMin);
        for (int j = 1; j < s; j++) {
            int v = domainValues[j];
            if (marginal(v) < min) {
                min = marginal(v);
                valWithMin = v;
            }
        }
        return valWithMin;
    }

    @Override
    public double maxMarginalRegret() {
        if (domain.isEmpty())
            throw new NoSuchElementException();
        double max = beliefRep.zero();
        double nextMax = beliefRep.zero();
        int s = fillArray(domainValues);
        for (int j = 0; j < s; j++) {
            double m = marginal(domainValues[j]);
            if (m > max) {
                nextMax = max;
                max = m;
            } else if (m > nextMax) {
                nextMax = m;
            }
        }
        return max - nextMax;
    }

    @Override
    public double entropy() {
        double H = 0;
        int s = fillArray(domainValues);
        for (int j = 0; j < s; j++) {
            double m = beliefRep.rep2std(marginal(domainValues[j]));
	        if (m > 0 && m < 1.0)
		        H += m * Math.log(m);
        }
        return -H;
    }

    @Override
    public double impactOfValue(int value) {
        if(impactValues.containsKey(value)) {
            double sum = impactValues.get(value).stream().mapToDouble(a->a).sum();
            return sum/((double)impactValues.get(value).size());
        }
        else {
            return 1.0 - (entropy()/(Math.log((double)domain.size())));
        }
    }

    @Override
    public int valueWithMinImpact() {
        if (domain.isEmpty())
            throw new NoSuchElementException();
        int s = fillArray(domainValues);
        int valWithMin = domainValues[0];
        double min = impactOfValue(valWithMin);
        for (int j = 1; j < s; j++) {
            int v = domainValues[j];
            if (impactOfValue(v) < min) {
                min = impactOfValue(v);
                valWithMin = v;
            }
        }
        return valWithMin;
    }

    @Override
    public int valueWithMaxImpact() {
        if (domain.isEmpty())
            throw new NoSuchElementException();
        int s = fillArray(domainValues);
        int valWithMax = domainValues[0];
        double max = impactOfValue(valWithMax);
        for (int j = 1; j < s; j++) {
            int v = domainValues[j];
            if (impactOfValue(v) > max) {
                max = impactOfValue(v);
                valWithMax = v;
            }
        }
        return valWithMax;
    }

    @Override
    public double impact() {
        int s = fillArray(domainValues);
        double impact = 0.0;
        for(int j = 0; j < s; j++) {
           // System.out.println("value : " + domainValues[j] + " , impact : " + impactOfValue(domainValues[j]));
            impact += 1 - impactOfValue(domainValues[j]);
        }
        //System.out.println("impact : " +impact);
        return impact;
    }

    @Override
    public void registerImpact(int value, double impact) {
        if(!impactValues.containsKey(value)) {
            ArrayList<Double> impactList = new ArrayList<Double>();
            impactValues.put(value, impactList);
        }
        impactValues.get(value).add(impact);
    }

    @Override
    public String toString() {
        return domain.toString();
    }

}
