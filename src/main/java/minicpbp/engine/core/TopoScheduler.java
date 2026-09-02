/*
 * mini-cpbp: the topology-aware schedule (BP_SCHEDULING.md sections 2.1, 2.2;
 * BP_SCHEDULING_TODO.md; ported from Infer.NET's scheduling transform).
 *
 * What is taken from Infer.NET
 * ----------------------------
 * Infer.NET does not iterate a fixed loop over all message operators. It builds
 * a dependency graph over message computations, decomposes it into strongly
 * connected components, emits the acyclic part once in topological order, and
 * for each cyclic component searches an order that minimises the number of
 * stale ("backward") edges per cycle -- a message read from the previous
 * iteration rather than this one. It then prunes every computation that cannot
 * reach a requested output.
 *
 * Three of those four ideas transfer directly, and the fourth has a linear-time
 * equivalent here:
 *
 *   - decomposition. The effective factor graph of a search node (BPGraph) is
 *     peeled to its two-core. Outside the core the graph is acyclic, so two
 *     passes are exact and no iteration is needed at all.
 *   - order. A spanning forest is built and the factors are executed in
 *     discovery order on odd sweeps and in reverse on even sweeps. On an
 *     acyclic component that is exactly the leaves-to-root then root-to-leaves
 *     schedule of exact belief propagation. On a cyclic one it leaves exactly
 *     one stale edge per independent cycle, which is the minimum any sequential
 *     order can leave: precisely the quantity Infer.NET's greedy cycle search
 *     optimises, obtained here from the forest in linear time because MiniCPBP
 *     rebuilds its schedule at every search node instead of compiling it once.
 *   - staleness as a first-class notion. Infer.NET labels an edge backward when
 *     its consumer reads the previous iteration's value. Here that label is
 *     dynamic: a factor is executed only while some message it reads has
 *     actually moved (residual above tolerance), which subsumes Infer.NET's
 *     "Fresh" dependency and terminates the invocation as soon as no message is
 *     stale anywhere.
 *   - pruning. OptimiseForVariables becomes: drop the factors from which no
 *     message can reach the marginal of a declared branching variable.
 *
 * What is deliberately not taken: the operator-attribute taxonomy (Required,
 * Trigger, SkipIfUniform, NoInit, Cancels), the min-cut rotation of back edges,
 * the separate initialisation schedule and the repair pass that duplicates
 * statements. Those price the cost of *initialising* a message and the risk of
 * reading a uniform one -- questions that arise because Infer.NET compiles one
 * schedule ahead of time for a model whose operators have very different
 * initialisation semantics. MiniCPBP resets or warm-starts every message
 * uniformly at the start of an invocation and rebuilds the schedule per node,
 * so there is no initialisation choice left to optimise.
 */

package minicpbp.engine.core;

import minicpbp.util.BPConfig;
import minicpbp.util.BPStats;

final class TopoScheduler implements BPScheduler {

    private final BPGraph graph;
    private final double tol;

    TopoScheduler(BPGraph graph) {
        this.graph = graph;
        this.tol = BPConfig.RESIDUAL_TOL;
    }

    @Override
    public String name() {
        return "topo";
    }

    @Override
    public void beginInvocation(boolean fullDirty) {
        graph.rebuild(BPConfig.QUERY_ONLY);
        if (BPConfig.INCREMENTAL_DIRTY) graph.dirtyChanged(fullDirty);
        else graph.dirtyAll();
    }

    @Override
    public void endInvocation() {
        if (BPConfig.INCREMENTAL_DIRTY) graph.persistStale();
    }

    @Override
    public void run(int maxSweeps, SweepMonitor monitor) {
        int n = graph.factorCount();
        for (int iter = 1; iter <= maxSweeps; iter++) {
            // odd sweeps run the forest backwards, i.e. leaves to root: a
            // factor's inward message must be computed after the messages it
            // summarises. Even sweeps run root to leaves. On an acyclic
            // component that pair is exact BP and the third sweep finds nothing
            // stale.
            boolean inward = (iter % 2 == 1);
            for (int i = 0; i < n; i++) {
                int f = graph.orderAt(inward ? n - 1 - i : i);
                if (graph.isPruned(f)) {
                    BPStats.factorSkipsPruned++;
                    continue;
                }
                if (!graph.isDirty(f)) {
                    if (graph.isFrozen(f)) BPStats.factorSkipsFrozen++;
                    else BPStats.factorSkipsClean++;
                    continue;
                }
                graph.clean(f);
                Constraint c = graph.factorAt(f);
                if (!c.isActive() || !c.bpParticipant()) continue;
                double residual = c.updateMessagesInPlace(graph);
                BPStats.factorUpdates++;
                if (residual > tol) graph.spreadDirty(f, tol);
            }
            graph.normalizeMarginals();
            BPStats.sweeps++;
            boolean quiescent = graph.dirtyCount() == 0;
            if (monitor.afterSweep(iter)) break;
            if (quiescent) break; // no message is stale: further sweeps are no-ops
        }
    }
}
