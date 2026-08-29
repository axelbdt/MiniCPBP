/*
 * mini-cpbp, replacing classic propagation by belief propagation
 * Copyright (c)  2019. by Gilles Pesant
 *
 * Encapsulated Intension vs the flat reified decomposition it replaces:
 * filtering must be propagation-equivalent (same propagators reach the same
 * fixpoint through the boundary channeling), the outer graph must contain one
 * Intension factor and only the boundary variables, backtracking must restore
 * both arms identically, and the nested-BP boundary messages must match exact
 * enumeration on tree-structured expressions.
 */

package minicpbp.engine.constraints;

import minicpbp.engine.SolverTest;
import minicpbp.engine.core.IntVar;
import minicpbp.engine.core.Solver;
import minicpbp.util.exception.InconsistencyException;
import org.junit.Test;

import java.util.Random;
import java.util.function.Function;

import static minicpbp.cp.Factory.*;
import static org.junit.Assert.*;

public class IntensionTest extends SolverTest {

    // the truth tables of the flat decomposition (ExprDecomposer)
    private static final int[][] AND = {{0, 0, 0}, {0, 1, 0}, {1, 0, 0}, {1, 1, 1}};
    private static final int[][] OR = {{0, 0, 0}, {0, 1, 1}, {1, 0, 1}, {1, 1, 1}};
    private static final int[][] IMP = {{0, 0, 1}, {0, 1, 1}, {1, 0, 0}, {1, 1, 1}};

    private interface Pred {
        boolean holds(int[] a);
    }

    private static final class Expr {
        final String name;
        final int[][] doms; // {min,max} per boundary variable
        final Function<IntVar[], IntVar> builder;
        final Pred pred;
        final boolean tree; // acyclic internal factor graph (BP exact)

        Expr(String name, int[][] doms, Function<IntVar[], IntVar> builder, Pred pred, boolean tree) {
            this.name = name;
            this.doms = doms;
            this.builder = builder;
            this.pred = pred;
            this.tree = tree;
        }
    }

    private static IntVar logicalTable(IntVar b1, IntVar b2, int[][] tab) {
        Solver cp = b1.getSolver();
        IntVar z = makeIntVar(cp, 0, 1);
        cp.post(table(new IntVar[]{b1, b2, z}, tab));
        return z;
    }

    private static Expr[] satExprs() {
        return new Expr[]{
                new Expr("le(add(x,y),z)",
                        new int[][]{{0, 3}, {0, 3}, {0, 3}},
                        xs -> isLessOrEqual(sum(xs[0], xs[1]), xs[2]),
                        a -> a[0] + a[1] <= a[2], true),
                new Expr("eq(add(x,y),z)",
                        new int[][]{{0, 3}, {0, 3}, {0, 3}},
                        xs -> isEqual(sum(xs[0], xs[1]), xs[2]),
                        a -> a[0] + a[1] == a[2], true),
                new Expr("and(lt(x,y),lt(y,z))",
                        new int[][]{{0, 3}, {0, 3}, {0, 3}},
                        xs -> logicalTable(isLess(xs[0], xs[1]), isLess(xs[1], xs[2]), AND),
                        a -> a[0] < a[1] && a[1] < a[2], true),
                new Expr("or(eq(x,0),eq(y,2))",
                        new int[][]{{0, 3}, {0, 3}},
                        xs -> logicalTable(isEqual(xs[0], 0), isEqual(xs[1], 2), OR),
                        a -> a[0] == 0 || a[1] == 2, true),
                new Expr("not(eq(x,y))",
                        new int[][]{{0, 2}, {0, 2}},
                        xs -> plus(minus(isEqual(xs[0], xs[1])), 1),
                        a -> a[0] != a[1], true),
                new Expr("imp(gt(x,1),lt(y,2))",
                        new int[][]{{0, 3}, {0, 3}},
                        xs -> logicalTable(isLarger(xs[0], 1), isLess(xs[1], 2), IMP),
                        a -> !(a[0] > 1) || a[1] < 2, true),
                new Expr("or(lt(x,y),lt(y,x))", // x,y repeated across subexpressions -> loopy
                        new int[][]{{0, 2}, {0, 2}},
                        xs -> logicalTable(isLess(xs[0], xs[1]), isLess(xs[1], xs[0]), OR),
                        a -> a[0] != a[1], false),
                new Expr("eq(dist(x,y),2)",
                        new int[][]{{0, 4}, {0, 4}},
                        xs -> isEqual(abs(sum(xs[0], minus(xs[1]))), 2),
                        a -> Math.abs(a[0] - a[1]) == 2, true),
        };
    }

    private static IntVar[] makeVars(Solver cp, int[][] doms) {
        IntVar[] xs = new IntVar[doms.length];
        for (int i = 0; i < doms.length; i++) {
            xs[i] = makeIntVar(cp, doms[i][0], doms[i][1]);
            xs[i].setName("x" + i);
        }
        return xs;
    }

    /** flat arm: reified decomposition posted into the outer solver, root := true */
    private static IntVar[] flatArm(Solver cp, Expr e) {
        IntVar[] xs = makeVars(cp, e.doms);
        IntVar root = e.builder.apply(xs);
        root.assign(1);
        cp.fixPoint();
        return xs;
    }

    /** encapsulated arm: one Intension factor, decomposition hidden inside */
    private static IntVar[] encArm(Solver cp, Expr e, Intension[] out) {
        IntVar[] xs = makeVars(cp, e.doms);
        Intension c = new Intension(cp, xs, e.builder);
        cp.post(c, true);
        if (out != null)
            out[0] = c;
        return xs;
    }

    private static void assertSameDomains(String ctx, Expr e, IntVar[] flat, IntVar[] enc) {
        for (int i = 0; i < e.doms.length; i++)
            for (int v = e.doms[i][0]; v <= e.doms[i][1]; v++)
                assertEquals(ctx + " [" + e.name + "] x" + i + "=" + v,
                        flat[i].contains(v), enc[i].contains(v));
    }

    @Test
    public void initialFilteringMatchesFlatAndOuterGraphIsOneFactor() {
        for (Expr e : satExprs()) {
            Solver cpF = solverFactory.get();
            Solver cpE = solverFactory.get();
            IntVar[] flat = flatArm(cpF, e);
            IntVar[] enc = encArm(cpE, e, null);
            assertSameDomains("initial fixpoint", e, flat, enc);
            // encapsulation invariant: the outer network holds ONE constraint
            // (the Intension factor) and only the boundary variables
            assertEquals("[" + e.name + "] outer constraints", 1, cpE.getConstraints().size());
            assertEquals("[" + e.name + "] outer variables", e.doms.length, cpE.getVariables().size());
        }
    }

    @Test
    public void outerReductionPropagatesThroughIntension() {
        // outer constraints -> Intension -> outer variables:
        // |x - y| = 2 with x := 0 forces y = 2
        Expr e = satExprs()[7];
        Solver cpF = solverFactory.get();
        Solver cpE = solverFactory.get();
        IntVar[] flat = flatArm(cpF, e);
        IntVar[] enc = encArm(cpE, e, null);
        flat[0].assign(0);
        cpF.fixPoint();
        enc[0].assign(0);
        cpE.fixPoint();
        assertTrue(enc[1].isBound());
        assertEquals(2, enc[1].min());
        assertSameDomains("after x:=0", e, flat, enc);
    }

    @Test
    public void randomizedReductionEquivalence() {
        for (Expr e : satExprs()) {
            for (long seed = 0; seed < 5; seed++) {
                Random rnd = new Random(97 * seed + e.name.hashCode());
                Solver cpF = solverFactory.get();
                Solver cpE = solverFactory.get();
                IntVar[] flat = flatArm(cpF, e);
                IntVar[] enc = encArm(cpE, e, null);
                int[] buf = new int[64];
                for (int step = 0; step < 30; step++) {
                    int i = rnd.nextInt(e.doms.length);
                    if (flat[i].isBound())
                        continue;
                    int s = flat[i].fillArray(buf);
                    int v = buf[rnd.nextInt(s)];
                    boolean doAssign = rnd.nextBoolean();
                    boolean failF = false, failE = false;
                    try {
                        if (doAssign) flat[i].assign(v); else flat[i].remove(v);
                        cpF.fixPoint();
                    } catch (InconsistencyException ex) {
                        failF = true;
                    }
                    try {
                        if (doAssign) enc[i].assign(v); else enc[i].remove(v);
                        cpE.fixPoint();
                    } catch (InconsistencyException ex) {
                        failE = true;
                    }
                    assertEquals("[" + e.name + "] seed " + seed + " step " + step
                            + " inconsistency", failF, failE);
                    if (failF)
                        break;
                    assertSameDomains("seed " + seed + " step " + step, e, flat, enc);
                }
            }
        }
    }

    @Test
    public void backtrackingRestoresBothArmsIdentically() {
        for (Expr e : satExprs()) {
            Solver cpF = solverFactory.get();
            Solver cpE = solverFactory.get();
            IntVar[] flat = flatArm(cpF, e);
            IntVar[] enc = encArm(cpE, e, null);
            // snapshot of the fixpoint domains
            boolean[][] snap = new boolean[e.doms.length][];
            for (int i = 0; i < e.doms.length; i++) {
                snap[i] = new boolean[e.doms[i][1] - e.doms[i][0] + 1];
                for (int v = e.doms[i][0]; v <= e.doms[i][1]; v++)
                    snap[i][v - e.doms[i][0]] = enc[i].contains(v);
            }
            cpF.getStateManager().saveState();
            cpE.getStateManager().saveState();
            // a consistent reduction: bind x0 to its current minimum
            try {
                flat[0].assign(flat[0].min());
                cpF.fixPoint();
                enc[0].assign(enc[0].min());
                cpE.fixPoint();
                assertSameDomains("under saveState", e, flat, enc);
            } catch (InconsistencyException ex) {
                // acceptable only if both arms agree; equivalence is asserted
                // by randomizedReductionEquivalence, so just fall through
            }
            cpF.getStateManager().restoreState();
            cpE.getStateManager().restoreState();
            assertSameDomains("after restore", e, flat, enc);
            for (int i = 0; i < e.doms.length; i++)
                for (int v = e.doms[i][0]; v <= e.doms[i][1]; v++)
                    assertEquals("[" + e.name + "] restored x" + i + "=" + v,
                            snap[i][v - e.doms[i][0]], enc[i].contains(v));
            // the intension must keep filtering correctly after the restore
            try {
                enc[1].assign(enc[1].max());
                flat[1].assign(flat[1].max());
                cpE.fixPoint();
                cpF.fixPoint();
                assertSameDomains("after restore + new reduction", e, flat, enc);
            } catch (InconsistencyException ex) {
                // ditto
            }
        }
    }

    @Test
    public void unsatDetectedAtPostOnBothArms() {
        Expr unsat = new Expr("and(lt(x,y),lt(y,x))",
                new int[][]{{0, 3}, {0, 3}},
                xs -> logicalTable(isLess(xs[0], xs[1]), isLess(xs[1], xs[0]), AND),
                a -> false, false);
        boolean failF = false, failE = false;
        try {
            flatArm(solverFactory.get(), unsat);
        } catch (InconsistencyException ex) {
            failF = true;
        }
        try {
            encArm(solverFactory.get(), unsat, null);
        } catch (InconsistencyException ex) {
            failE = true;
        }
        assertTrue("flat arm must detect UNSAT at post", failF);
        assertTrue("encapsulated arm must detect UNSAT at post", failE);
    }

    @Test
    public void nestedBPBoundaryMessagesMatchExactEnumeration() {
        for (Expr e : satExprs()) {
            if (!e.tree)
                continue; // BP is exact on trees only
            Solver cp = solverFactory.get();
            Intension[] c = new Intension[1];
            IntVar[] xs = encArm(cp, e, c);
            // domains after the exact fixpoint = the conditioning support
            int n = xs.length;
            int[][] dom = new int[n][];
            int[] buf = new int[64];
            for (int i = 0; i < n; i++) {
                int s = xs[i].fillArray(buf);
                dom[i] = new int[s];
                System.arraycopy(buf, 0, dom[i], 0, s);
                java.util.Arrays.sort(dom[i]);
            }
            cp.setMaxIter(30);
            cp.vanillaBP(3);
            assertTrue("[" + e.name + "] nested BP must have run", c[0].nbBPCalls() > 0);
            // exact posterior by enumeration over the current domains
            double[][] cnt = new double[n][];
            for (int i = 0; i < n; i++)
                cnt[i] = new double[dom[i].length];
            long total = enumerate(e, dom, new int[n], 0, cnt);
            assertTrue("[" + e.name + "] fixpoint domains must contain a solution", total > 0);
            for (int i = 0; i < n; i++)
                for (int j = 0; j < dom[i].length; j++) {
                    double exact = cnt[i][j] / total;
                    double bp = cp.getBeliefRep().rep2std(xs[i].marginal(dom[i][j]));
                    assertEquals("[" + e.name + "] marginal x" + i + "=" + dom[i][j],
                            exact, bp, 0.02);
                }
            // approximate BP must not have touched any domain
            for (int i = 0; i < n; i++)
                assertEquals("[" + e.name + "] BP filtered x" + i, dom[i].length, xs[i].size());
        }
    }

    private static long enumerate(Expr e, int[][] dom, int[] a, int i, double[][] cnt) {
        if (i == dom.length) {
            if (!e.pred.holds(a))
                return 0;
            for (int k = 0; k < dom.length; k++)
                for (int j = 0; j < dom[k].length; j++)
                    if (dom[k][j] == a[k])
                        cnt[k][j]++;
            return 1;
        }
        long t = 0;
        for (int j = 0; j < dom[i].length; j++) {
            a[i] = dom[i][j];
            t += enumerate(e, dom, a, i + 1, cnt);
        }
        return t;
    }
}
