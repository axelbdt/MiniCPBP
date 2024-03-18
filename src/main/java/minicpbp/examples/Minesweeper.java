package minicpbp.examples;

import java.util.ArrayList;
import java.util.Arrays;

import minicpbp.cp.Factory;
import minicpbp.engine.core.BoolVar;
import minicpbp.engine.core.IntVar;
import minicpbp.engine.core.Solver;
import minicpbp.util.io.InputReader;

public class Minesweeper {

	public static void main(String[] args) {
		// Initializing the data
		InputReader reader = new InputReader("src/main/java/minicpbp/examples/data/MineSweeper/ex2.txt");
		int r = reader.getInt(); // rows
		int c = reader.getInt(); // columns
		int nbMines = reader.getInt();
		int[][] gameArray = reader.getMatrix(r, c);

		Solver cp = Factory.makeSolver();

		// Create IntVar array for the game + initialize mines
		BoolVar[][] mines = new BoolVar[r][c];
		for (int i = 0; i < r; i++) {
			for (int j = 0; j < c; j++) {
				mines[i][j] = Factory.makeBoolVar(cp);
				mines[i][j].setName("cell_" + i + "_" + j);
			}
		}
		// Constraints
		for (int i = 0; i < r; i++) {
			for (int j = 0; j < c; j++) {
				if (gameArray[i][j] >= 0) {
					ArrayList<IntVar> neighbors = new ArrayList<>();
					for (int a = -1; a <= 1; a++) {
						for (int b = -1; b <= 1; b++) {
							if (i + a >= 0 && j + b >= 0 && i + a < r && j + b < c)
								neighbors.add(mines[i + a][j + b]);
						}
					}
					// If a square is uncovered, then its value is equal to the number of mines
					// around it
					cp.post(Factory.sum(neighbors.toArray(new IntVar[0]), gameArray[i][j]));
					// If a square is uncovered, then it is not a mine
					mines[i][j].assign(0);
				}
			}
		}
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
