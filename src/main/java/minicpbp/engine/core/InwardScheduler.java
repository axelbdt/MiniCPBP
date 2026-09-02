/*
 * mini-cpbp: the inward (collect-only) schedule.
 *
 * The frugality hypothesis: once the branching variable is fixed by dom/wdeg
 * (whose inputs do not move during sweeps), the only marginal value selection
 * reads is that variable's. The topo schedule rooted at the decision variable
 * (probe Q, amendment 13) already delivers it on its FIRST sweep -- reading
 * the spanning forest backwards is leaves-to-THIS-root, the collect phase --
 * but it still alternates outward sweeps and still executes every component
 * of the forest. This schedule keeps only what that one marginal needs:
 *
 *   - one leaves-to-root pass per "sweep", never an outward pass;
 *   - only the decision variable's component, order[0..rootComponentEnd),
 *     since no message from another component can reach the root's marginal.
 *
 * Relative to topo it is therefore two deltas that an experiment can compose
 * or isolate: (topo rooted, 1-sweep cap) -> (inward, 1-sweep cap) isolates the
 * component restriction, since the executed prefix is otherwise identical and
 * the skip/clean/spreadDirty logic is copied verbatim.
 *
 * What is knowingly given up (BPConfig guards enforce the consequences):
 *
 *   - non-root marginals are half-updated -- fresh from their subtree side
 *     only -- so the schedule refuses to run under a stop rule that reads
 *     them (shipped, converge); fixed and decision are allowed;
 *   - a wipeout in a component other than the root's goes unseen, weakening
 *     the problemEntropy() == 0 correctness stop by construction. This is a
 *     measured cost of the experiment, not an oversight;
 *   - requires rootAtDecision: without the hint the root is posting-order
 *     arbitrary and the pass serves no particular marginal (BPGraph then
 *     reports the whole forest as the prefix, so behaviour degrades to a
 *     full inward sweep rather than to nonsense);
 *   - factor updates stay undirected: updateMessagesInPlace computes ALL
 *     outgoing messages of a factor, so the saving is per-pass and
 *     per-component, not per-edge. A directed per-edge update is a separate
 *     rung of the ladder, to be built only if this one earns it.
 *
 * Iterated (maxSweeps > 1) it is fixed-order sequential BP over the prefix,
 * legitimate around cycles but not this schedule's point. The whole-graph
 * quiescence test rarely fires here, because factors outside the prefix stay
 * dirty; the sweep budget is the effective bound, and under warm start +
 * incrementalDirty the un-executed remainder is handed to the next invocation
 * by persistStale, exactly as a topo sweep that was stopped early would.
 */

package minicpbp.engine.core;

import minicpbp.util.BPConfig;
import minicpbp.util.BPStats;

final class InwardScheduler implements BPScheduler {

    private final BPGraph graph;
    private final double tol;

    InwardScheduler(BPGraph graph) {
        this.graph = graph;
        this.tol = BPConfig.RESIDUAL_TOL;
    }

    @Override
    public String name() {
        return "inward";
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
        int end = graph.rootComponentEnd();
        for (int iter = 1; iter <= maxSweeps; iter++) {
            // every sweep reads the prefix backwards: leaves-to-root on the
            // decision variable's component, so a factor's inward message is
            // computed after the messages it summarises and the root's
            // incident factors read fresh messages from the whole component
            for (int i = end - 1; i >= 0; i--) {
                int f = graph.orderAt(i);
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
            // whole-graph test, as in topo: with components outside the prefix
            // still dirty it rarely fires, and maxSweeps is the real bound
            boolean quiescent = graph.dirtyCount() == 0;
            if (monitor.afterSweep(iter)) break;
            if (quiescent) break;
        }
    }
}
