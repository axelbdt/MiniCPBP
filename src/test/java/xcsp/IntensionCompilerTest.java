/*
 * mini-cpbp, replacing classic propagation by belief propagation
 *
 * IntensionCompiler: structural compilation of XCSP intension trees.
 * Tests cover (1) semantics against brute-force enumeration, (2) model
 * structure (no unnecessary auxiliary variables / reified Booleans),
 * (3) equivalence with the previous reified decomposition
 * (ExprDecomposer) on expressions the old path supported, and (4) the
 * generic fallback on expressions no specialized pattern recognizes.
 */

package xcsp;

import minicpbp.cp.Factory;
import minicpbp.engine.core.BoolVar;
import minicpbp.engine.core.IntVar;
import minicpbp.engine.core.Solver;
import minicpbp.search.DFSearch;
import minicpbp.search.SearchStatistics;
import minicpbp.util.exception.InconsistencyException;
import org.junit.Test;
import org.xcsp.common.Types.TypeExpr;
import org.xcsp.common.Types.TypeVar;
import org.xcsp.common.predicates.XNode;
import org.xcsp.common.predicates.XNodeLeaf;
import org.xcsp.parser.entries.XVariables.XVar;
import org.xcsp.parser.entries.XVariables.XVarInteger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static minicpbp.cp.BranchingScheme.firstFail;
import static minicpbp.cp.Factory.*;
import static org.junit.Assert.*;
import static org.xcsp.common.Types.TypeExpr.*;

public class IntensionCompilerTest {

    // ------------------------------------------------------------------
    // fixture
    // ------------------------------------------------------------------

    private interface Pred {
        boolean holds(int[] a);
    }

    private static final class Model {
        final Solver cp = makeSolver();
        final XVarInteger[] xv;
        final IntVar[] x;
        final int[][] doms;
        final IntensionCompiler<XVarInteger> compiler;
        final int baseVars;

        Model(int[][] doms) {
            this.doms = doms;
            xv = new XVarInteger[doms.length];
            x = new IntVar[doms.length];
            Map<XVarInteger, IntVar> map = new HashMap<>();
            for (int i = 0; i < doms.length; i++) {
                xv[i] = (XVarInteger) XVar.build("x" + i, TypeVar.integer, null);
                x[i] = makeIntVar(cp, doms[i][0], doms[i][1]);
                x[i].setName("x" + i);
                map.put(xv[i], x[i]);
            }
            compiler = new IntensionCompiler<>(cp, map::get);
            baseVars = cp.getVariables().size();
        }

        int newVars() {
            return cp.getVariables().size() - baseVars;
        }

        int newBoolVars() {
            int n = 0;
            for (int i = baseVars; i < cp.getVariables().size(); i++)
                if (cp.getVariables().get(i) instanceof BoolVar)
                    n++;
            return n;
        }

        /**
         * number of posted constraints, excluding the ConstraintClosure
         * listeners some propagators (e.g. Equal) register internally
         */
        int constraints() {
            return realConstraints(cp);
        }

        XNode<XVarInteger> v(int i) {
            return new XNodeLeaf<>(VAR, xv[i]);
        }
    }

    private static int realConstraints(Solver cp) {
        int n = 0;
        for (int i = 0; i < cp.getConstraints().size(); i++)
            if (!(cp.getConstraints().get(i) instanceof minicpbp.engine.core.ConstraintClosure))
                n++;
        return n;
    }

    private static XNode<XVarInteger> l(long v) {
        return XNode.longLeaf(v);
    }

    @SafeVarargs
    private static XNode<XVarInteger> n(TypeExpr type, XNode<XVarInteger>... sons) {
        return XNode.node(type, sons);
    }

    /** number of assignments of the full original domains satisfying pred */
    private static long bruteForce(int[][] doms, Pred pred) {
        int[] a = new int[doms.length];
        return bf(doms, pred, a, 0);
    }

    private static long bf(int[][] doms, Pred pred, int[] a, int i) {
        if (i == doms.length)
            return pred.holds(a) ? 1 : 0;
        long t = 0;
        for (int v = doms[i][0]; v <= doms[i][1]; v++) {
            a[i] = v;
            t += bf(doms, pred, a, i + 1);
        }
        return t;
    }

    /** solves by DFS over the original variables, checking pred per solution */
    private static long solveAndCheck(Model m, Pred pred) {
        List<int[]> sols = new ArrayList<>();
        DFSearch dfs = makeDfs(m.cp, firstFail(m.x));
        dfs.onSolution(() -> {
            int[] a = new int[m.x.length];
            for (int i = 0; i < m.x.length; i++) {
                assertTrue("original variable bound in solution", m.x[i].isBound());
                a[i] = m.x[i].min();
            }
            assertTrue("solution violates the predicate", pred.holds(a));
            sols.add(a);
        });
        SearchStatistics stats = dfs.solve();
        assertEquals(sols.size(), stats.numberOfSolutions());
        return stats.numberOfSolutions();
    }

    /** compile + full semantic check against brute force */
    private static void checkSemantics(Model m, XNode<XVarInteger> tree, Pred pred) {
        long expected = bruteForce(m.doms, pred);
        boolean failed = false;
        try {
            m.compiler.compileConstraint(tree);
        } catch (InconsistencyException e) {
            failed = true;
        }
        long got = failed ? 0 : solveAndCheck(m, pred);
        assertEquals("solution count for " + tree, expected, got);
    }

    private static int[][] doms(int n, int lo, int hi) {
        int[][] d = new int[n][];
        for (int i = 0; i < n; i++)
            d[i] = new int[]{lo, hi};
        return d;
    }

    // ------------------------------------------------------------------
    // direct primitive compilation
    // ------------------------------------------------------------------

    @Test
    public void eqVarVarPostsOneConstraintNoAux() {
        Model m = new Model(doms(2, 0, 3));
        XNode<XVarInteger> t = n(EQ, m.v(0), m.v(1));
        checkSemantics(m, t, a -> a[0] == a[1]);
        assertEquals(1, m.constraints());
        assertEquals(0, m.newVars());
    }

    @Test
    public void neVarVarPostsOneConstraintNoAux() {
        Model m = new Model(doms(2, 0, 3));
        checkSemantics(m, n(NE, m.v(0), m.v(1)), a -> a[0] != a[1]);
        assertEquals(1, m.constraints());
        assertEquals(0, m.newVars());
    }

    @Test
    public void leVarVarPostsOneConstraintNoAux() {
        Model m = new Model(doms(2, 0, 3));
        checkSemantics(m, n(LE, m.v(0), m.v(1)), a -> a[0] <= a[1]);
        assertEquals(1, m.constraints());
        assertEquals(0, m.newVars());
    }

    @Test
    public void unaryRelIsPureDomainOperation() {
        Model m = new Model(doms(1, 0, 9));
        checkSemantics(m, n(LT, m.v(0), l(5)), a -> a[0] < 5);
        assertEquals(0, m.constraints());
        assertEquals(0, m.newVars());
    }

    // ------------------------------------------------------------------
    // linear recognition
    // ------------------------------------------------------------------

    @Test
    public void eqAddIsSingleSumNoBooleans() {
        Model m = new Model(doms(3, 0, 3));
        checkSemantics(m, n(EQ, n(ADD, m.v(0), m.v(1)), m.v(2)),
                a -> a[0] + a[1] == a[2]);
        assertEquals(1, m.constraints()); // one SumDC
        assertEquals(0, m.newBoolVars());
        assertTrue("at most the bound rhs variable", m.newVars() <= 1);
    }

    @Test
    public void leAddAddCompilesToOneSum() {
        // x+y <= z+w  ==  x+y-z-w <= 0 : one sum, one slack, no Booleans
        Model m = new Model(doms(4, 0, 3));
        checkSemantics(m, n(LE, n(ADD, m.v(0), m.v(1)), n(ADD, m.v(2), m.v(3))),
                a -> a[0] + a[1] <= a[2] + a[3]);
        assertEquals(1, m.constraints());
        assertEquals(0, m.newBoolVars());
        assertTrue(m.newVars() <= 1);
    }

    @Test
    public void weightedLinearEquation() {
        // 3x - 2y = 7
        Model m = new Model(doms(2, 0, 10));
        checkSemantics(m,
                n(EQ, n(ADD, n(MUL, l(3), m.v(0)), n(MUL, l(-2), m.v(1))), l(7)),
                a -> 3 * a[0] - 2 * a[1] == 7);
        assertEquals(1, m.constraints());
        assertEquals(0, m.newBoolVars());
    }

    @Test
    public void subEqConstantIsBinaryDiff() {
        // x - y = 3 : one binary Equal on views, no sum
        Model m = new Model(doms(2, 0, 5));
        checkSemantics(m, n(EQ, n(SUB, m.v(0), m.v(1)), l(3)),
                a -> a[0] - a[1] == 3);
        assertEquals(1, m.constraints());
        assertEquals(0, m.newVars());
    }

    @Test
    public void commonTermsCancel() {
        // x+y = x+z  ->  y = z : binary equality, no sum constraint
        Model m = new Model(doms(3, 0, 4));
        checkSemantics(m, n(EQ, n(ADD, m.v(0), m.v(1)), n(ADD, m.v(0), m.v(2))),
                a -> a[1] == a[2]);
        assertEquals(1, m.constraints());
        assertEquals(0, m.newVars());
    }

    @Test
    public void gcdDetectsInfeasibleEquation() {
        // 2x + 2y = 7 : infeasible by gcd, no constraint even posted
        Model m = new Model(doms(2, 0, 10));
        checkSemantics(m,
                n(EQ, n(ADD, n(MUL, l(2), m.v(0)), n(MUL, l(2), m.v(1))), l(7)),
                a -> false);
    }

    @Test
    public void repeatedVariableCollapsesToView() {
        // x + x = 6  ->  2x = 6 : pure domain operation on a view
        Model m = new Model(doms(1, 0, 5));
        checkSemantics(m, n(EQ, n(ADD, m.v(0), m.v(0)), l(6)), a -> 2 * a[0] == 6);
        assertEquals(0, m.constraints());
        assertEquals(0, m.newVars());
    }

    // ------------------------------------------------------------------
    // Boolean expressions
    // ------------------------------------------------------------------

    @Test
    public void andOfUnaryRelationsIsPureDomainOperation() {
        Model m = new Model(doms(2, 0, 5));
        checkSemantics(m, n(AND, n(EQ, m.v(0), l(1)), n(LT, m.v(1), l(3))),
                a -> a[0] == 1 && a[1] < 3);
        assertEquals(0, m.constraints());
        assertEquals(0, m.newVars());
    }

    @Test
    public void orOfEqualitiesOnSameVarIsDomainRestriction() {
        Model m = new Model(doms(1, 0, 5));
        checkSemantics(m, n(OR, n(EQ, m.v(0), l(1)), n(EQ, m.v(0), l(3))),
                a -> a[0] == 1 || a[0] == 3);
        assertEquals(0, m.constraints());
        assertEquals(0, m.newVars());
        assertEquals(2, m.x[0].size());
    }

    @Test
    public void orOfThreeEqualitiesOnSameVar() {
        Model m = new Model(doms(1, 0, 9));
        checkSemantics(m,
                n(OR, n(EQ, m.v(0), l(1)), n(EQ, m.v(0), l(2)), n(EQ, m.v(0), l(3))),
                a -> a[0] >= 1 && a[0] <= 3);
        assertEquals(0, m.constraints());
    }

    @Test
    public void orAcrossDifferentVarsIsOneClause() {
        Model m = new Model(doms(2, 0, 3));
        checkSemantics(m, n(OR, n(EQ, m.v(0), l(1)), n(EQ, m.v(1), l(2))),
                a -> a[0] == 1 || a[1] == 2);
        // two reified equalities + one clause; no logic table, no root Boolean
        assertEquals(3, m.constraints());
        assertEquals(2, m.newBoolVars());
    }

    @Test
    public void notEqIsDirectNotEqual() {
        Model m = new Model(doms(2, 0, 3));
        checkSemantics(m, n(NOT, n(EQ, m.v(0), m.v(1))), a -> a[0] != a[1]);
        assertEquals(1, m.constraints());
        assertEquals(0, m.newVars());
    }

    @Test
    public void impCompilesToClause() {
        Model m = new Model(doms(2, 0, 9));
        checkSemantics(m, n(IMP, n(EQ, m.v(0), l(1)), n(LT, m.v(1), l(5))),
                a -> a[0] != 1 || a[1] < 5);
        assertTrue("reified halves + clause", m.constraints() <= 3);
        assertEquals(2, m.newBoolVars());
    }

    @Test
    public void iffOfRelations() {
        Model m = new Model(doms(2, 0, 3));
        checkSemantics(m, n(IFF, n(LE, m.v(0), l(1)), n(LE, m.v(1), l(1))),
                a -> (a[0] <= 1) == (a[1] <= 1));
    }

    @Test
    public void xorOfRelations() {
        Model m = new Model(doms(2, 0, 3));
        checkSemantics(m, n(XOR, n(LE, m.v(0), l(1)), n(LE, m.v(1), l(1))),
                a -> (a[0] <= 1) != (a[1] <= 1));
    }

    @Test
    public void deMorganOnNegatedAnd() {
        Model m = new Model(doms(2, 0, 3));
        checkSemantics(m, n(NOT, n(AND, n(EQ, m.v(0), l(1)), n(EQ, m.v(1), l(2)))),
                a -> !(a[0] == 1 && a[1] == 2));
    }

    @Test
    public void negatedOrBecomesDomainOperations() {
        Model m = new Model(doms(2, 0, 3));
        checkSemantics(m, n(NOT, n(OR, n(EQ, m.v(0), l(1)), n(LT, m.v(1), l(2)))),
                a -> a[0] != 1 && a[1] >= 2);
        assertEquals(0, m.constraints());
    }

    @Test
    public void doubleNegationVanishes() {
        Model m = new Model(doms(2, 0, 3));
        checkSemantics(m, n(NOT, n(NOT, n(EQ, m.v(0), m.v(1)))), a -> a[0] == a[1]);
        assertEquals(1, m.constraints());
        assertEquals(0, m.newVars());
    }

    @Test
    public void membershipIsDomainRestriction() {
        Model m = new Model(doms(1, 0, 9));
        checkSemantics(m, n(IN, m.v(0), n(SET, l(2), l(4), l(6))),
                a -> a[0] == 2 || a[0] == 4 || a[0] == 6);
        assertEquals(0, m.constraints());
    }

    @Test
    public void notMembershipIsDomainRestriction() {
        Model m = new Model(doms(1, 0, 9));
        checkSemantics(m, n(NOTIN, m.v(0), n(SET, l(2), l(4), l(6))),
                a -> a[0] != 2 && a[0] != 4 && a[0] != 6);
        assertEquals(0, m.constraints());
    }

    // ------------------------------------------------------------------
    // nested arithmetic: recognized outer structure + materialized atoms
    // ------------------------------------------------------------------

    @Test
    public void absInsideLinearSum() {
        // |x| + y = z with x in [-3,3]: Absolute + one SumDC, no Booleans
        Model m = new Model(new int[][]{{-3, 3}, {0, 3}, {0, 6}});
        checkSemantics(m, n(EQ, n(ADD, n(ABS, m.v(0)), m.v(1)), m.v(2)),
                a -> Math.abs(a[0]) + a[1] == a[2]);
        assertEquals(2, m.constraints());
        assertEquals(0, m.newBoolVars());
    }

    @Test
    public void distEqualsConstant() {
        Model m = new Model(doms(2, 0, 4));
        checkSemantics(m, n(EQ, n(DIST, m.v(0), m.v(1)), l(2)),
                a -> Math.abs(a[0] - a[1]) == 2);
        assertEquals(0, m.newBoolVars());
    }

    @Test
    public void productAtomInsideRelation() {
        // x*y <= z+2 : Product atom + binary LessOrEqual on a view
        Model m = new Model(doms(3, 0, 3));
        checkSemantics(m, n(LE, n(MUL, m.v(0), m.v(1)), n(ADD, m.v(2), l(2))),
                a -> a[0] * a[1] <= a[2] + 2);
        assertEquals(0, m.newBoolVars());
    }

    // ------------------------------------------------------------------
    // constants and simplification
    // ------------------------------------------------------------------

    @Test
    public void constantFoldingBindsVariable() {
        // x + 2*3 = 8  ->  x = 2
        Model m = new Model(doms(1, 0, 9));
        checkSemantics(m, n(EQ, n(ADD, m.v(0), n(MUL, l(2), l(3))), l(8)),
                a -> a[0] == 2);
        assertEquals(0, m.constraints());
        assertTrue(m.x[0].isBound());
    }

    @Test
    public void andWithTrueIsIdentity() {
        Model m = new Model(doms(2, 0, 3));
        checkSemantics(m, n(AND, n(EQ, m.v(0), m.v(1)), n(EQ, l(1), l(1))),
                a -> a[0] == a[1]);
        assertEquals(1, m.constraints());
    }

    @Test
    public void andWithFalseIsInconsistent() {
        Model m = new Model(doms(2, 0, 3));
        checkSemantics(m, n(AND, n(EQ, m.v(0), m.v(1)), n(GT, l(2), l(3))),
                a -> false);
    }

    @Test
    public void orWithTrueIsTriviallySatisfied() {
        Model m = new Model(doms(2, 0, 3));
        checkSemantics(m, n(OR, n(EQ, m.v(0), m.v(1)), n(LT, l(2), l(3))),
                a -> true);
        assertEquals(0, m.constraints());
    }

    @Test
    public void orWithFalseIsIdentity() {
        Model m = new Model(doms(2, 0, 3));
        checkSemantics(m, n(OR, n(EQ, m.v(0), m.v(1)), n(GT, l(2), l(3))),
                a -> a[0] == a[1]);
        assertEquals(1, m.constraints());
    }

    @Test
    public void mulByZeroFolds() {
        Model m = new Model(doms(1, 0, 5));
        checkSemantics(m, n(EQ, n(MUL, m.v(0), l(0)), l(0)), a -> true);
        assertEquals(0, m.constraints());
        Model m2 = new Model(doms(1, 0, 5));
        checkSemantics(m2, n(EQ, n(MUL, m2.v(0), l(0)), l(1)), a -> false);
    }

    @Test
    public void addZeroAndMulOneAreIdentity() {
        Model m = new Model(doms(2, 0, 3));
        checkSemantics(m,
                n(EQ, n(ADD, m.v(0), l(0)), n(MUL, m.v(1), l(1))),
                a -> a[0] == a[1]);
        assertEquals(1, m.constraints());
        assertEquals(0, m.newVars());
    }

    // ------------------------------------------------------------------
    // generic fallback
    // ------------------------------------------------------------------

    @Test
    public void fallbackOnComplexExpression() {
        // xor of three subexpressions, one of them nested: no dedicated
        // pattern, must still compile correctly through reification
        Model m = new Model(doms(3, 0, 2));
        checkSemantics(m,
                n(XOR, n(EQ, m.v(0), l(1)),
                        n(AND, n(LT, m.v(1), l(2)), n(EQ, m.v(2), l(0))),
                        n(LE, m.v(2), m.v(0))),
                a -> ((a[0] == 1 ? 1 : 0)
                        ^ ((a[1] < 2 && a[2] == 0) ? 1 : 0)
                        ^ (a[2] <= a[0] ? 1 : 0)) == 1);
    }

    @Test
    public void fallbackMembershipWithVariableSet() {
        Model m = new Model(doms(3, 0, 3));
        checkSemantics(m, n(IN, m.v(0), n(SET, m.v(1), m.v(2))),
                a -> a[0] == a[1] || a[0] == a[2]);
    }

    @Test
    public void fallbackModulo() {
        Model m = new Model(doms(2, 0, 7));
        checkSemantics(m, n(EQ, n(MOD, m.v(0), l(3)), m.v(1)),
                a -> a[0] % 3 == a[1]);
    }

    @Test
    public void fallbackMinMax() {
        Model m = new Model(doms(3, 0, 3));
        checkSemantics(m, n(EQ, n(MIN, m.v(0), m.v(1)), n(MAX, m.v(2), l(1))),
                a -> Math.min(a[0], a[1]) == Math.max(a[2], 1));
    }

    @Test
    public void unsatDetectedAtPost() {
        Model m = new Model(doms(2, 0, 3));
        try {
            m.compiler.compileConstraint(
                    n(AND, n(LT, m.v(0), m.v(1)), n(LT, m.v(1), m.v(0))));
            fail("expected inconsistency at post");
        } catch (InconsistencyException e) {
            // expected
        }
    }

    // ------------------------------------------------------------------
    // equivalence with the previous reified decomposition
    // ------------------------------------------------------------------

    private static final class Pair {
        final String name;
        final int[][] doms;
        final Pred pred;
        final java.util.function.Function<Model, XNode<XVarInteger>> tree;

        Pair(String name, int[][] doms, java.util.function.Function<Model, XNode<XVarInteger>> tree, Pred pred) {
            this.name = name;
            this.doms = doms;
            this.tree = tree;
            this.pred = pred;
        }
    }

    private static Pair[] equivalencePairs() {
        return new Pair[]{
                new Pair("eq(add(x,y),z)", doms(3, 0, 3),
                        m -> n(EQ, n(ADD, m.v(0), m.v(1)), m.v(2)),
                        a -> a[0] + a[1] == a[2]),
                new Pair("le(add(x,y),add(z,w))", doms(4, 0, 3),
                        m -> n(LE, n(ADD, m.v(0), m.v(1)), n(ADD, m.v(2), m.v(3))),
                        a -> a[0] + a[1] <= a[2] + a[3]),
                new Pair("or(eq(x,1),eq(y,2))", doms(2, 0, 3),
                        m -> n(OR, n(EQ, m.v(0), l(1)), n(EQ, m.v(1), l(2))),
                        a -> a[0] == 1 || a[1] == 2),
                new Pair("imp(gt(x,1),lt(y,2))", doms(2, 0, 3),
                        m -> n(IMP, n(GT, m.v(0), l(1)), n(LT, m.v(1), l(2))),
                        a -> !(a[0] > 1) || a[1] < 2),
                new Pair("eq(dist(x,y),2)", doms(2, 0, 4),
                        m -> n(EQ, n(DIST, m.v(0), m.v(1)), l(2)),
                        a -> Math.abs(a[0] - a[1]) == 2),
                new Pair("not(eq(x,y))", doms(2, 0, 2),
                        m -> n(NOT, n(EQ, m.v(0), m.v(1))),
                        a -> a[0] != a[1]),
        };
    }

    /** old arm: ExprDecomposer's reified decomposition, root fixed to true */
    private static IntVar[] oldArm(Pair p, int[] outCounts) {
        Solver cp = makeSolver();
        Map<XVarInteger, IntVar> map = new HashMap<>();
        Model shim = new Model(p.doms); // only to build the tree with its leaves
        IntVar[] x = new IntVar[p.doms.length];
        for (int i = 0; i < x.length; i++) {
            x[i] = makeIntVar(cp, p.doms[i][0], p.doms[i][1]);
            map.put(shim.xv[i], x[i]);
        }
        int base = cp.getVariables().size();
        ExprDecomposer dec = new ExprDecomposer(cp, map::get);
        IntVar root = dec.parseExpr(p.tree.apply(shim));
        root.assign(1);
        cp.fixPoint();
        outCounts[0] = cp.getVariables().size() - base;
        outCounts[1] = realConstraints(cp);
        return x;
    }

    @Test
    public void newCompilationFiltersAtLeastAsMuchAsOld() {
        for (Pair p : equivalencePairs()) {
            int[] oldCounts = new int[2];
            IntVar[] oldX = oldArm(p, oldCounts);
            Model m = new Model(p.doms);
            m.compiler.compileConstraint(p.tree.apply(m));
            for (int i = 0; i < p.doms.length; i++)
                for (int v = p.doms[i][0]; v <= p.doms[i][1]; v++)
                    assertFalse("[" + p.name + "] new keeps value the old path pruned: x"
                                    + i + "=" + v,
                            m.x[i].contains(v) && !oldX[i].contains(v));
            // the new compilation must not be larger than the old one
            assertTrue("[" + p.name + "] aux vars: new " + m.newVars() + " old " + oldCounts[0],
                    m.newVars() <= oldCounts[0]);
            assertTrue("[" + p.name + "] constraints: new " + m.constraints() + " old " + oldCounts[1],
                    m.constraints() <= oldCounts[1]);
        }
    }

    @Test
    public void newCompilationHasSameSolutions() {
        for (Pair p : equivalencePairs()) {
            Model m = new Model(p.doms);
            long expected = bruteForce(p.doms, p.pred);
            boolean failed = false;
            try {
                m.compiler.compileConstraint(p.tree.apply(m));
            } catch (InconsistencyException e) {
                failed = true;
            }
            long got = failed ? 0 : solveAndCheck(m, p.pred);
            assertEquals("[" + p.name + "]", expected, got);
        }
    }
}
