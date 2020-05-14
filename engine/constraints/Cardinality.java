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

package minicp.engine.constraints;

import java.util.Arrays;
import java.util.Set;
import java.util.HashSet;

import minicp.engine.core.AbstractConstraint;
import minicp.engine.core.IntVar;
import minicp.engine.core.Solver;
import static minicp.cp.Factory.*;

    /**
     * Creates a cardinality constraint.
     * This relation is currently enforced by decomposing it into 
     * table constraints linking each x[i] to its indicator variables y[i][j] (y[i][j]==1 iff x[i]==vals[j]) and 
     * sum constraints y[0][j]+...+y[x.length-1][j]==o[j];
     * hence it is not domain consistent
     *
     * @param x an array of variables
     * @param vals an array of values whose occurrences in x we count
     * @param o an array of variables corresponding to the number of occurrences of vals in x
     * @return a cardinality constraint
     */
public class Cardinality extends AbstractConstraint {

    private IntVar[] x;
    private int[] vals;
    private IntVar[] o;
    private Solver cp;
    private IntVar[][] y;
    private int[][][] indicatorTable;

    public Cardinality(IntVar[] x, int[] vals, IntVar[] o, IntVar dummy) {
        super(new IntVar[]{dummy}); // not a real constraint so we want a minimal footprint in the superclass' constructor, but with a domain large enough to define an appropriate domainValues array
	setName("Cardinality");
        this.x = x;
	this.vals = vals;
	this.o = o;
	cp = x[0].getSolver();
        y = new IntVar[x.length][vals.length]; // indicator variables: (y[i][j] == 1) iff (x[i] == vals[j])
	indicatorTable = new int[x.length][][];
        for (int i = 0; i < x.length; i++) {
	    for (int j = 0; j < vals.length; j++) {
		y[i][j] = makeIntVar(cp, 0, 1); 
		y[i][j].setName("y"+"["+i+"]"+"["+j+"]");
	    }
	    int s = x[i].fillArray(domainValues);
	    indicatorTable[i] = new int[s][vals.length+1];
	    for (int j = 0; j < s; j++) {
		int v = domainValues[j];
		for (int k = 0; k < vals.length; k++) {
		    indicatorTable[i][j][k] = (vals[k]==v? 1 : 0);
		}
		indicatorTable[i][j][vals.length] = v;
	    }
	}
   	setExactWCounting(false);
    }

    @Override
    public void post() {
        for (int i = 0; i < x.length; i++) {
	    IntVar[] vars = Arrays.copyOf(y[i],vals.length+1);
	    vars[vals.length] = x[i];
	    cp.post(table(vars,indicatorTable[i]));
	}
	// check for special case: all nb of occurrences are fixed and sum to the number of x[i]
	int nbOcc = 0;
	int k;
        for (k = 0; k < o.length; k++) {
	    if (o[k].isBound())
		nbOcc += o[k].min();
	    else
		break;
	}
	if (k==o.length && nbOcc==x.length) {
	    Set<Integer> V = new HashSet<Integer>();
	    for (int j = 0; j < vals.length; j++) {
		V.add(vals[j]);
	    }
	    for (int i = 0; i < x.length; i++) {
		int s = x[i].fillArray(domainValues);
		for (int j = 0; j < s; j++) {
		    int v = domainValues[j];
		    if (!V.contains(v))
			x[i].remove(v); // all variables must take a value among vals
		}
	    }
	    // one of the sum constraints is redundant; wlog leave out the first one;
	    // this appears to help convergence of the marginals over such decomposition
	    for (int j = 1; j < vals.length; j++) {
		IntVar[] vars = new IntVar[x.length];
		for (int i = 0; i < x.length; i++) {
		    vars[i] = y[i][j];
		}
		cp.post(sum(vars,o[j]));
	    }
	}
	else {
	    for (int j = 0; j < vals.length; j++) {
		IntVar[] vars = new IntVar[x.length];
		for (int i = 0; i < x.length; i++) {
		    vars[i] = y[i][j];
		}
		cp.post(sum(vars,o[j]));
	    }
	}
    }

}
