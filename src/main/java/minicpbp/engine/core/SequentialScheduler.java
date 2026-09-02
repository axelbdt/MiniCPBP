/*
 * mini-cpbp: Gauss-Seidel belief propagation (BP_SCHEDULING.md sections 1.2,
 * 1.3; Infer.NET's Sequential / Sequential.BackwardPass).
 *
 * One factor at a time, its outgoing messages published into the marginals
 * before the next factor reads them. Under the flooding sweep a factor's
 * output cannot reach its neighbour until the following sweep, so information
 * travels one factor per sweep; here it travels as far as the order allows.
 *
 * No graph analysis and no gating: the factors are executed in posting order,
 * every one of them, every sweep. This is the baseline that isolates the
 * Gauss-Seidel effect from everything the topology-aware schedule adds.
 */

package minicpbp.engine.core;

import minicpbp.util.BPConfig;
import minicpbp.util.BPStats;

final class SequentialScheduler implements BPScheduler {

    private final BPGraph graph;
    private final boolean alternate;

    SequentialScheduler(BPGraph graph, boolean alternate) {
        this.graph = graph;
        this.alternate = alternate;
    }

    @Override
    public String name() {
        return alternate ? "seqfb" : "seq";
    }

    @Override
    public void beginInvocation(boolean fullDirty) {
        graph.rebuild(false);
    }

    @Override
    public void run(int maxSweeps, SweepMonitor monitor) {
        int n = graph.factorCount();
        for (int iter = 1; iter <= maxSweeps; iter++) {
            boolean backward = alternate && (iter % 2 == 0);
            for (int i = 0; i < n; i++) {
                Constraint c = graph.factorAt(backward ? n - 1 - i : i);
                if (!c.isActive() || !c.bpParticipant()) continue;
                c.updateMessagesInPlace(graph);
                BPStats.factorUpdates++;
            }
            graph.normalizeMarginals();
            BPStats.sweeps++;
            if (monitor.afterSweep(iter)) break;
        }
    }
}
