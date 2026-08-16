/*
 * mini-cpbp, replacing classic propagation by belief propagation
 * Copyright (c)  2019. by Gilles Pesant
 *
 * Soules (2003) U^3 upper bound on the permanent of a nonnegative matrix.
 *
 * Extracted verbatim from AllDifferentDC on 2026-08-16 (same expressions, same
 * evaluation order, so the numbers are bit-identical) so that the offline
 * Phase 1 comparison and the solver run the *same* code rather than two copies
 * that can drift apart. See IMPLEMENTATION_LOG.md, 2026-08-16.
 */

package minicpbp.util;

public final class SoulesUB3 {

    private final double[] gamma;
    private final double[] rowMax;
    private final double[] rowMaxSecondBest;

    public SoulesUB3(int n) {
        // precompute gamma function up to n+1, to account for small floating-point errors
        int gamma_threshold = 100; // value of n beyond which we approximate n!
        double factorial = 1.0;
        gamma = new double[n + 2];
        gamma[0] = 1.0;
        for (int i = 1; (i <= n + 1) && (i <= gamma_threshold); i++) {
            factorial *= (double) i;
            gamma[i] = Math.pow(factorial, 1.0 / ((double) i));
        }
        for (int i = gamma_threshold + 1; i <= n + 1; i++) {
            // from n>gamma_threshold, Stirling's formula is a decent approximation of factorial which will avoid intermediate overflow
            gamma[i] = (double) i / Math.E * Math.pow(2 * Math.PI * i, 1.0 / ((double) 2 * i));
        }
        rowMax = new double[n];
        rowMaxSecondBest = new double[n];
    }

    /**
     * permanent upper bound U^3 for nonnegative matrices (from Soules 2003)
     * for matrix m without row of var and column of val.
     * var = val = -1 gives the bound for the whole matrix.
     */
    public double ub3(double[][] beliefs, int var, int val, int dim, int nbDummyRows) {
        double U3 = 1.0;
        double rowSum, rowMx, tmp;
        int tmpFloor, tmpCeil;
        int dummyRowCount = nbDummyRows;

        for (int i = 0; i < dim; i++) {
            if (i != var) { // exclude row of var whose belief we are computing
                rowSum = rowMx = 0;
                for (int j = 0; j < dim; j++) {
                    tmp = beliefs[i][j];
                    if (j != val) { // exclude column of val whose belief we are computing
                        rowSum += tmp;
                        if (tmp > rowMx)
                            rowMx = tmp;
                    }
                }
                if (rowMx == 0)
                    return 0;
                tmp = rowSum / rowMx;
                tmpFloor = (int) Math.floor(tmp);
                tmpCeil = (int) Math.ceil(tmp);
                U3 *= rowMx * (gamma[tmpFloor] + (tmp - tmpFloor) * (gamma[tmpCeil] - gamma[tmpFloor]));
                if (dummyRowCount > 1) {
                    // that upper bound should be divided by (# dummy rows)!
                    U3 /= (double) dummyRowCount;
                    dummyRowCount--;
                }
            }
        }
        return U3;
    }

    public void precomputeRowMax(double[][] beliefs, int dim) {
        double tmp;
        for (int i = 0; i < dim; i++) {
            rowMax[i] = rowMaxSecondBest[i] = 0;
            for (int j = 0; j < dim; j++) {
                tmp = beliefs[i][j];
                if (tmp > rowMax[i]) {
                    rowMaxSecondBest[i] = rowMax[i];
                    rowMax[i] = tmp;
                } else if (tmp > rowMaxSecondBest[i]) {
                    rowMaxSecondBest[i] = tmp;
                }
            }
        }
    }

    /**
     * Same bound as {@link #ub3} but assuming each row of beliefs sums to one;
     * requires {@link #precomputeRowMax} to have been called on this matrix.
     */
    public double ub3Faster(double[][] beliefs, int var, int val, int dim, int nbDummyRows) {
        double U3 = 1.0;
        double rSum, rMax, tmp;
        int tmpFloor, tmpCeil;
        int dummyRowCount = nbDummyRows;

        for (int i = 0; i < dim; i++) {
            if (i != var) { // exclude row of var whose belief we are computing
                rSum = 1.0 - beliefs[i][val]; // each row of m (beliefs) sums to one
                rMax = (rowMax[i] == beliefs[i][val] ? rowMaxSecondBest[i] : rowMax[i]);
                if (rMax == 0)
                    return 0;
                tmp = rSum / rMax;
                tmpFloor = (int) Math.floor(tmp);
                tmpCeil = (int) Math.ceil(tmp);
                U3 *= rMax * (gamma[tmpFloor] + (tmp - tmpFloor) * (gamma[tmpCeil] - gamma[tmpFloor]));
                if (dummyRowCount > 1) {
                    // that upper bound should be divided by (# dummy rows)!
                    U3 /= dummyRowCount;
                    dummyRowCount--;
                }
            }
        }
        return U3;
    }
}
