/*
 * mini-cpbp: per-sweep decision trace (BP_PROBE_PROTOCOL.md, Probe H).
 *
 * Records, once per BP sweep, the min-entropy decision and its margins —
 * the quantities a root-trajectory knee detector (or a tie-aware
 * decision-stability stop rule) would consume online. Gated by a static
 * final read of -Dminicpbp.probe.sweepTrace=<file>: dead code when unset.
 *
 * Cost per sweep is O(sum of domain sizes) over the unbound branching
 * variables, the same order as the problemEntropy()/smallestVariableEntropy()
 * computations the sweep loop already performs.
 */

package minicpbp.util;

import minicpbp.engine.core.IntVar;
import minicpbp.engine.core.Solver;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;

public final class SweepTrace {

    private static final String FILE = System.getProperty("minicpbp.probe.sweepTrace", "");
    public static final boolean ENABLED = !FILE.isEmpty();

    private static BufferedWriter out;

    private SweepTrace() {
    }

    public static void record(Solver cp, int iter) {
        // min-entropy decision from normalized entropies, plus its margins
        IntVar best = null, second = null;
        double bestH = Double.MAX_VALUE, secondH = Double.MAX_VALUE;
        Belief rep = cp.getBeliefRep();
        Iterator<IntVar> it = cp.getVariables().iterator();
        while (it.hasNext()) {
            IntVar x = it.next();
            if (x.isBound() || !x.isForBranching()) continue;
            double mass = 0;
            for (int v = x.min(); v <= x.max(); v++) {
                if (x.contains(v)) mass += rep.rep2std(x.marginal(v));
            }
            double h;
            if (mass <= 0) {
                h = Math.log(x.size());
            } else {
                h = 0;
                for (int v = x.min(); v <= x.max(); v++) {
                    if (!x.contains(v)) continue;
                    double p = rep.rep2std(x.marginal(v)) / mass;
                    if (p > 0) h -= p * Math.log(p);
                }
            }
            if (h < bestH) {
                second = best;
                secondH = bestH;
                best = x;
                bestH = h;
            } else if (h < secondH) {
                second = x;
                secondH = h;
            }
        }
        if (best == null) return;
        double p1 = -1, p2 = -1, mass = 0;
        int v1 = Integer.MIN_VALUE;
        for (int v = best.min(); v <= best.max(); v++) {
            if (!best.contains(v)) continue;
            double p = rep.rep2std(best.marginal(v));
            mass += p;
            if (p > p1) {
                p2 = p1;
                p1 = p;
                v1 = v;
            } else if (p > p2) {
                p2 = p;
            }
        }
        double muVal = mass <= 0 ? 0.0 : (p1 - Math.max(p2, 0)) / mass;
        double muH = (second == null) ? Double.MAX_VALUE : secondH - bestH;
        try {
            if (out == null) {
                out = new BufferedWriter(new FileWriter(FILE, true));
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    try {
                        out.close();
                    } catch (IOException ignored) {
                    }
                }));
            }
            out.write(String.format(Locale.ROOT, "%d,%d,%s,%d,%.6g,%.6g%n",
                    BPStats.invocations, iter,
                    best.getName() == null ? "?" : best.getName(), v1, muVal, muH));
        } catch (IOException e) {
            System.err.println("sweep trace: " + e.getMessage());
        }
    }
}
