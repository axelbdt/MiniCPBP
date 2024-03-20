package minicpbp.examples;

import java.util.Arrays;

import minicpbp.cp.Factory;
import minicpbp.engine.core.IntVar;
import minicpbp.engine.core.Solver;
import minicpbp.search.DFSearch;

import static minicpbp.cp.BranchingScheme.minEntropy;
import static minicpbp.cp.Factory.makeDfs;

public class TestBPEqs {

	public static void main(String[] args) {
		Solver cp = Factory.makeSolver();

		// Variables
		IntVar x1 = Factory.makeIntVar(cp, 0, 2);
		x1.setName("x1");
		IntVar x2 = Factory.makeIntVar(cp, 0, 2);
		x2.setName("x2");
		IntVar x3 = Factory.makeIntVar(cp, 0, 2);
		x3.setName("x3");

		// Constraint
		IntVar[] c = { x1, x2, x3 };
		int[] vals = { 0, 1, 2 };
		int[] o = { 1, 2, 0 };
		cp.post(Factory.cardinality(c, vals, o));

		cp.fixPoint();
		cp.setTraceBPFlag(true);
		cp.setTraceSearchFlag(true);
		DFSearch dfs = makeDfs(cp, minEntropy(new IntVar[] {x1,x2,x3})); // to set them as branching vars for problemEntropy()
		cp.beliefPropa();
//		cp.vanillaBP(10);

		/*
		 * DFSearch dfs = Factory.makeDfs(cp, maxMarginal(flatten(mines)));
		 * dfs.onSolution(() -> { for (int i = 0; i < r; i++)
		 * System.out.println(Arrays.deepToString(mines[i])); } ); SearchStatistics
		 * stats = dfs.solve(stat -> stat.numberOfSolutions() >= 1);
		 * System.out.println(stats);
		 */
	}

	public static IntVar[] flatten(IntVar[][] x) {
		return Arrays.stream(x).flatMap(Arrays::stream).toArray(IntVar[]::new);
	}
}
