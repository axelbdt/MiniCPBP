/*
 * mini-cpbp: residual (asynchronous, priority-driven) belief propagation
 * (BP_SCHEDULING.md section 2.3, the strong generic baseline that any later
 * CP-semantic scheduler has to beat).
 *
 * The order is not fixed in advance. Each factor carries the largest movement
 * of any message it has received since it last ran; the largest such residual
 * runs next. A factor whose inputs have not moved never runs, and the
 * invocation ends when no residual is above tolerance.
 *
 * Priority is per factor, not per message, because one updateBelief() computes
 * every outgoing message of a constraint: the schedulable unit here is the
 * weighted-counting call, not the scalar message. The seed order is the
 * spanning-forest order, so the first sweep behaves like the topology-aware
 * schedule and the priority queue takes over afterwards.
 */

package minicpbp.engine.core;

import minicpbp.util.BPConfig;
import minicpbp.util.BPStats;

final class ResidualScheduler implements BPScheduler {

    private static final double SEED_PRIORITY = 1.0E9;

    private final BPGraph graph;
    private final double tol;

    /** binary max-heap over factor indices, keyed by pending residual */
    private int[] heap = new int[64];
    private int[] posInHeap = new int[64];
    private double[] priority = new double[64];
    private int heapSize;

    ResidualScheduler(BPGraph graph) {
        this.graph = graph;
        this.tol = BPConfig.RESIDUAL_TOL;
    }

    @Override
    public String name() {
        return "residual";
    }

    @Override
    public void beginInvocation(boolean fullDirty) {
        graph.rebuild(BPConfig.QUERY_ONLY);
        int n = graph.factorCount();
        if (heap.length < n) {
            heap = new int[Math.max(n, heap.length * 2)];
            posInHeap = new int[heap.length];
            priority = new double[heap.length];
        }
        // Which factors start stale. Without incremental seeding that is all of
        // them, because the marginals were just reset or restored and nothing
        // records what changed; with it, only what changed since the last
        // invocation on this path plus what that invocation left un-executed.
        // Either way they are ordered by the spanning forest so the first pass
        // is the exact one on an acyclic component.
        if (BPConfig.INCREMENTAL_DIRTY) graph.dirtyChanged(fullDirty);
        else graph.dirtyAll();
        heapSize = 0;
        for (int f = 0; f < n; f++) posInHeap[f] = -1;
        for (int i = 0; i < n; i++) {
            int f = graph.orderAt(i);
            if (graph.isPruned(f)) {
                BPStats.factorSkipsPruned++;
                continue;
            }
            if (!graph.isDirty(f)) {
                BPStats.factorSkipsClean++;
                continue;
            }
            // above any real residual (messages are probabilities, so a residual
            // is at most 1), and ordered so that the first pass runs the forest
            // from the leaves inwards, which is the exact pass on an acyclic
            // component
            priority[f] = SEED_PRIORITY + i;
            push(f);
        }
    }

    @Override
    public void endInvocation() {
        if (!BPConfig.INCREMENTAL_DIRTY) return;
        // whatever is still queued was never executed against its current inputs
        int n = graph.factorCount();
        for (int f = 0; f < n; f++) {
            graph.factorAt(f).setBpStale(posInHeap[f] >= 0 || graph.isPruned(f));
        }
    }

    @Override
    public void run(int maxSweeps, SweepMonitor monitor) {
        int n = graph.factorCount();
        if (n == 0) return;
        long budget = (long) maxSweeps * n; // same total work allowance as maxSweeps flooding sweeps
        long done = 0;
        int sweep = 0;
        while (done < budget) {
            if (heapSize == 0) break; // no message is stale anywhere
            int f = pop();
            Constraint c = graph.factorAt(f);
            done++;
            if (!c.isActive() || !c.bpParticipant()) continue;
            double residual = c.updateMessagesInPlace(graph);
            BPStats.factorUpdates++;
            if (residual > tol) spread(f);
            if (done % n == 0) { // one sweep's worth of work: let the engine look
                graph.normalizeMarginals();
                sweep++;
                BPStats.sweeps++;
                if (monitor.afterSweep(sweep)) return;
            }
        }
        if (done % n != 0) { // report the partial sweep the invocation ended on
            graph.normalizeMarginals();
            BPStats.sweeps++;
            monitor.afterSweep(sweep + 1);
        }
    }

    /** raises the priority of every factor that reads a message this factor moved */
    private void spread(int f) {
        Constraint c = graph.factorAt(f);
        for (int k = graph.scopeBegin(f); k < graph.scopeEnd(f); k++) {
            double r = c.messageResidual(graph.scopePosAt(k));
            if (r <= tol) continue;
            int v = graph.scopeVarAt(k);
            for (int j = graph.incBegin(v); j < graph.incEnd(v); j++) {
                int g = graph.incFactorAt(j);
                if (g == f || graph.isFrozen(g) || graph.isPruned(g)) continue;
                bump(g, r);
            }
        }
    }

    private void bump(int f, double r) {
        if (posInHeap[f] >= 0) {
            if (r <= priority[f]) return;
            priority[f] = r;
            siftUp(posInHeap[f]);
        } else {
            priority[f] = r;
            push(f);
        }
    }

    /* ---- heap ------------------------------------------------------------- */

    private void push(int f) {
        heap[heapSize] = f;
        posInHeap[f] = heapSize;
        heapSize++;
        siftUp(heapSize - 1);
    }

    private int pop() {
        int top = heap[0];
        posInHeap[top] = -1;
        heapSize--;
        if (heapSize > 0) {
            heap[0] = heap[heapSize];
            posInHeap[heap[0]] = 0;
            siftDown(0);
        }
        return top;
    }

    private void siftUp(int i) {
        int f = heap[i];
        while (i > 0) {
            int parent = (i - 1) / 2;
            if (priority[heap[parent]] >= priority[f]) break;
            heap[i] = heap[parent];
            posInHeap[heap[i]] = i;
            i = parent;
        }
        heap[i] = f;
        posInHeap[f] = i;
    }

    private void siftDown(int i) {
        int f = heap[i];
        while (true) {
            int child = 2 * i + 1;
            if (child >= heapSize) break;
            if (child + 1 < heapSize && priority[heap[child + 1]] > priority[heap[child]]) child++;
            if (priority[heap[child]] <= priority[f]) break;
            heap[i] = heap[child];
            posInHeap[heap[i]] = i;
            i = child;
        }
        heap[i] = f;
        posInHeap[f] = i;
    }
}
