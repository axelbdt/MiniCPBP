/*
 * mini-cpbp, replacing classic propagation by belief propagation
 * Copyright (c)  2019. by Gilles Pesant
 *
 * Hidden subgraph captured for an encapsulating constraint (e.g.
 * {@link minicpbp.engine.constraints.Intension}): variables and constraints
 * created while the solver is in hidden-capture mode. They live on the same
 * StateManager as the rest of the model (reversible state is shared), but are
 * NOT registered in the solver's variable/constraint lists, so they do not
 * appear in the outer factor graph, the outer BP sweeps, problem entropy,
 * constraint weighing, or the outer propagation queue.
 */

package minicpbp.engine.core;

import java.util.ArrayList;
import java.util.List;

public final class HiddenGraph {

    private final List<IntVar> variables = new ArrayList<>();
    private final List<Constraint> constraints = new ArrayList<>();
    // constraints scheduled during capture (typically by posting/fixing the
    // internal root); the owner transfers them into its local queue
    private final List<Constraint> pending = new ArrayList<>();

    void addVariable(IntVar x) {
        variables.add(x);
    }

    void addConstraint(Constraint c) {
        constraints.add(c);
    }

    void addPending(Constraint c) {
        pending.add(c);
    }

    public List<IntVar> variables() {
        return variables;
    }

    public List<Constraint> constraints() {
        return constraints;
    }

    public List<Constraint> pending() {
        return pending;
    }
}
