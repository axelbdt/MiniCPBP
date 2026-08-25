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

package minicpbp.util;

/**
 * Operations on beliefs/marginals/probabilities
 * Adopting a standard probability representation
 */
public class StdBelief implements Belief {
    

    private static final double ONE = 1.0;
    private static final double ZERO = 0.0;

    public  double one() {
	return ONE;
    }

    public  double zero() {
	return ZERO;
    }

    public  boolean isOne(double belief) {
	return belief == ONE;
    }

    public  boolean isZero(double belief) {
	return belief == ZERO;
    }

    public  double std2rep(double a) {
	return a;
    }

    public  double rep2std(double a) {
	return a;
    }

    public  double multiply(double a, double b) {
	return a*b;
    }

    public  double divide(double a, double b) {
	return a/b;
    }

    public  double add(double a, double b) {
	return a+b; 
    }

    public  double subtract(double a, double b) {
        return a-b;
    }

    public  double complement(double a) {
	return 1 - a;
    }

    public  double summation(double a[], int size) {
 	double sum = 0;
	for (int j=0; j<size; j++) {
	    sum += a[j];
	}
     	return sum;
    }

    public  double pow(double a, double b) {
	// 2026-08-25 (BP_COST_PROFILE.md Lever C): bit-exact short circuit, as in
	// LogBelief.pow. The exponent is the constraint weight, which is 1.0 for
	// every constraint under ConstraintWeighingScheme.SAME (the default), so
	// Math.pow was called once per belief cell to return its first argument.
	// Math.pow(a,1.0) == a exactly, so this changes no result. Worth
	// 1.20x-1.31x on the BP arm.
	return (b == 1.0) ? a : Math.pow(a,b);
    }

}
