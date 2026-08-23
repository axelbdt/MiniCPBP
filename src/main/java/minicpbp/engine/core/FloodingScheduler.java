/*
 * mini-cpbp: the reference BP schedule, kept byte-identical to the behaviour
 * MiniCPBP had before BP scheduling became configurable (BP_SCHEDULING.md
 * phase 0.1: "move the current BPiteration() behaviour behind an explicit
 * scheduler interface without changing its semantics").
 *
 * Every measurement of another schedule is reported against this one.
 */

package minicpbp.engine.core;

import minicpbp.util.BPStats;

import java.util.Iterator;

public final class FloodingScheduler implements BPScheduler {

    private final Solver cp;

    public FloodingScheduler(Solver cp) {
        this.cp = cp;
    }

    @Override
    public String name() {
        return "flood";
    }

    @Override
    public void beginInvocation(boolean fullDirty) {
    }

    @Override
    public void run(int maxSweeps, SweepMonitor monitor) {
        for (int iter = 1; iter <= maxSweeps; iter++) {
            sweep();
            BPStats.sweeps++;
            if (monitor.afterSweep(iter)) break;
        }
    }

    /**
     * One synchronous iteration: variables to constraints, then constraints to
     * variables.
     */
    void sweep() {
        Constraint c;
        Iterator<Constraint> iteratorC = cp.getConstraints().iterator();
        while (iteratorC.hasNext()) {
            c = iteratorC.next();
            if (c.isActive())
                c.receiveMessages();
        }
        Iterator<IntVar> iterator = cp.getVariables().iterator();
        while (iterator.hasNext()) {
            iterator.next().resetMarginals(); // prepare to receive all the messages from constraints
        }
        iteratorC = cp.getConstraints().iterator();
        while (iteratorC.hasNext()) {
            c = iteratorC.next();
            if (c.isActive()) {
                c.sendMessages();
                BPStats.factorUpdates++;
            }
        }
        iterator = cp.getVariables().iterator();
        while (iterator.hasNext()) {
            iterator.next().normalizeMarginals();
        }
    }
}
