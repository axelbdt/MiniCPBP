package minicpbp.experiment;

import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Iterator;

import xcsp.XCSP;

public class LatinSquareExperiment {
	public static void main(String[] args) {
		String fileName = "qwh-o30-h374-01-mod.xml";
		String filePath = "./src/main/java/minicpbp/experiment/instances/LatinSquare-m1-gp/" + fileName;

		try {
			XCSP xcsp = new XCSP(filePath);

			System.out.println("Solving...");

			ArrayList<String> solutions = new ArrayList<String>();
			xcsp.solve((sol, score) -> {
				solutions.add(sol);
			}, stat -> stat.isCompleted());

			System.out.println("Solved");

			try (FileWriter fw = new FileWriter("solutions.xml")) {
				System.out.println("Writing file");
				fw.write("<solutions>");
				for (Iterator iterator = solutions.iterator(); iterator.hasNext();) {
					String string = (String) iterator.next();
					fw.write(string);
					fw.write("\n");
				}
				fw.write("</solutions>");
			}

			System.out.println("File written");

		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
	}
}
