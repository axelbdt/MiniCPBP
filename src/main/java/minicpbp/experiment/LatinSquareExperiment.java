package minicpbp.experiment;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;

import xcsp.XCSP;

public class LatinSquareExperiment {
	public static void main(String[] args) {
		String fileName = "qwh-o30-h374-01.xml";
		String filePath = "./src/main/java/minicpbp/experiment/instances/LatinSquare-m1-gp/" + fileName;

		try (BufferedWriter bw = new BufferedWriter(new FileWriter("latinsquare-01-solution2.xml"))) {
			XCSP xcsp = new XCSP(filePath);

			System.out.println("Starting to solve");

			xcsp.solve((sol, score) -> {
				try {
					System.out.println(sol);
					bw.write(sol);
					bw.newLine();
				} catch (IOException e) {
					System.out.println("File error");
				}
			}, stat -> stat.isCompleted());

		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
}
