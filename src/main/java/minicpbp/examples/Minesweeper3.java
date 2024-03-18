package minicpbp.examples;

import java.util.Arrays;

import minicpbp.cp.Factory;
import minicpbp.engine.core.BoolVar;
import minicpbp.engine.core.IntVar;
import minicpbp.engine.core.Solver;

public class Minesweeper3 {

	public static void main(String[] args) {
		Solver cp = Factory.makeSolver();

		// Variables
		BoolVar a2 = Factory.makeBoolVar(cp);
		a2.setName("A2");

		BoolVar b1 = Factory.makeBoolVar(cp);
		b1.setName("B1");

		BoolVar b2 = Factory.makeBoolVar(cp);
		b2.setName("B2");

		BoolVar b3 = Factory.makeBoolVar(cp);
		b3.setName("B3");

		// BoolVar c3 = Factory.makeBoolVar(cp);
		// c3.setName("C3");

		// Constraint
		IntVar[] a1 = { a2, b1, b2 };
		cp.post(Factory.sum(a1, 1));

		IntVar[] a3 = { a2, b2, b3 };
		cp.post(Factory.sum(a3, 2));

		IntVar[] c1 = { b1, b2 };
		cp.post(Factory.sum(c1, 1));

		// IntVar[] c2 = { b1, b2, b3, c3 };
		// cp.post(Factory.sum(c2, 3));

		// IntVar[] totalCount = { a2, b1, b2, b3, c3 };
		// cp.post(Factory.sum(totalCount, 3));

		IntVar[] c2 = { b1, b2, b3 };
		cp.post(Factory.sum(c2, 2));

		IntVar[] totalCount = { a2, b1, b2, b3 };
		cp.post(Factory.sum(totalCount, 2));

		/* */
		// The number of mines in the matrix mines is equal to nbMines given by the
		// example
		// if (nbMines >= 0) // otherwise that number has been left unspecified
		// cp.post(Factory.sum(flatten(mines), nbMines));
		/* */

		cp.fixPoint();
		cp.setTraceBPFlag(true);
		cp.setTraceSearchFlag(true);
		cp.vanillaBP(5);

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
