/*
 * mini-cp is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License  v3
 * as published by the Free Software Foundation.
 *
 * mini-cp is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY.
 * See the GNU Lesser General Public License  for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with mini-cp. If not, see http://www.gnu.org/licenses/lgpl-3.0.en.html
 *
 * Copyright (c)  2018. by Laurent Michel, Pierre Schaus, Pascal Van Hentenryck
 */

package xcsp;

import minicpbp.cp.Factory;
import minicpbp.engine.constraints.Intension;
import minicpbp.engine.core.BoolVar;
import minicpbp.engine.core.Constraint;
import minicpbp.engine.core.IntVar;
import minicpbp.engine.core.IntVarViewOffset;
import minicpbp.engine.core.Solver;
import minicpbp.engine.core.Solver.PropaMode;
import minicpbp.search.DovetailSearch;
import minicpbp.search.LDSearch;
import minicpbp.search.Search;
import minicpbp.search.SearchStatistics;
import minicpbp.util.Procedure;
import minicpbp.util.exception.InconsistencyException;
import minicpbp.util.exception.NotImplementedException;

import launch.SolveXCSPFZN.BranchingHeuristic;
import launch.SolveXCSPFZN.TreeSearchType;

import org.xcsp.common.structures.Transition;
import org.xcsp.parser.callbacks.SolutionChecker;
import org.xcsp.common.Condition;
import org.xcsp.common.Constants;
import org.xcsp.common.Types;
import org.xcsp.common.predicates.XNode;
import org.xcsp.common.predicates.XNodeParent;
import org.xcsp.parser.callbacks.XCallbacks2;
import org.xcsp.parser.entries.XVariables.XVarInteger;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.security.InvalidParameterException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static minicpbp.cp.BranchingScheme.*;
import static minicpbp.cp.Factory.*;
import static java.lang.reflect.Array.newInstance;
import minicpbp.util.Log;

public class XCSP implements XCallbacks2 {

	private Implem implem = new Implem(this);

	private String fileName;
	private final Map<XVarInteger, IntVar> mapVar = new HashMap<>();
	private final List<XVarInteger> xVars = new LinkedList<>();
	private final List<IntVar> minicpVars = new LinkedList<>();

	private final Set<IntVar> decisionVars = new LinkedHashSet<>();

	private final Solver minicp = makeSolver();

	// reified decomposition of intension expression trees (kept for the
	// encapsulated Intension constraint, whose internal factor graph is by
	// design the reified decomposition with a conditioned root Boolean)
	private final ExprDecomposer exprDecomposer = new ExprDecomposer(minicp, x -> mapVar.get(x));

	// structural compilation of intension expression trees (flat path and
	// tree arguments of global constraints): recognition before
	// materialization, generic reification only as fallback
	private final IntensionCompiler<XVarInteger> intensionCompiler =
			new IntensionCompiler<>(minicp, x -> mapVar.get(x));

	private Optional<IntVar> objectiveMinimize = Optional.empty();
	private Optional<IntVar> realObjective = Optional.empty();

	private boolean hasFailed;

	@Override
	public Implem implem() {
		return implem;
	}

	/**
	 * The solver holding the model, so that a probe can run propagation or
	 * belief propagation on it directly instead of through a search
	 * (experiment/java/exp/SchedBench.java).
	 */
	public Solver getSolver() {
		return minicp;
	}

	public XCSP(String fileName) throws Exception {
		this.fileName = fileName;
		hasFailed = false;

		implem.currParameters.clear();

		implem.currParameters.put(XCallbacksParameters.RECOGNIZE_UNARY_PRIMITIVES, new Object());
		implem.currParameters.put(XCallbacksParameters.RECOGNIZE_BINARY_PRIMITIVES, new Object());
		implem.currParameters.put(XCallbacksParameters.RECOGNIZE_TERNARY_PRIMITIVES, new Object());
		implem.currParameters.put(XCallbacksParameters.RECOGNIZE_NVALUES_CASES, new Object());
		implem.currParameters.put(XCallbacksParameters.RECOGNIZE_COUNT_CASES, Boolean.TRUE);
		implem.currParameters.put(XCallbacksParameters.RECOGNIZING_BEFORE_CONVERTING, Boolean.TRUE);
		// defaults unchanged (convert whenever the parser can enumerate the
		// tuples); overridable so that the intension compiler can be
		// exercised/benchmarked on real instances, e.g. spaceLimit=0 keeps
		// every intension symbolic
		implem.currParameters.put(XCallbacksParameters.CONVERT_INTENSION_TO_EXTENSION_ARITY_LIMIT,
				Integer.getInteger("minicpbp.intension.toExtension.arityLimit", Integer.MAX_VALUE)); // included
		implem.currParameters.put(XCallbacksParameters.CONVERT_INTENSION_TO_EXTENSION_SPACE_LIMIT,
				Long.getLong("minicpbp.intension.toExtension.spaceLimit", Long.MAX_VALUE)); // included

		loadInstance(fileName);
	}

	public boolean isCOP() {
		return objectiveMinimize.isPresent();
	}

	public List<String> getViolatedCtrs(String solution) throws Exception {
		return new SolutionChecker(false, fileName, new ByteArrayInputStream(solution.getBytes())).violatedCtrs;
	}

	@Override
	public void buildVarInteger(XVarInteger x, int minValue, int maxValue) {
		IntVar minicpVar = makeIntVar(minicp, minValue, maxValue);
		mapVar.put(x, minicpVar);
		minicpVars.add(minicpVar);
		xVars.add(x);
		minicpVar.setName(x.id());
	}

	@Override
	public void buildVarInteger(XVarInteger x, int[] values) {
		Set<Integer> vals = new LinkedHashSet<>();
		for (int v : values)
			vals.add(v);
		IntVar minicpVar = makeIntVar(minicp, vals);
		mapVar.put(x, minicpVar);
		minicpVars.add(minicpVar);
		xVars.add(x);
		minicpVar.setName(x.id());
	}

	private IntVar[] mapVarArray(Object vars) {
		return Arrays.stream((XVarInteger[]) vars).map(mapVar::get).toArray(IntVar[]::new);
	}
	/* eventually useful?
	private IntVar[][] mapVar2dArray(Object vars) {
		return Arrays.stream((XVarInteger[][]) vars).map(this::mapVarArray).toArray(IntVar[][]::new);
	}
	*/

	@Override
	public void buildCtrExtension(String id, XVarInteger x, int[] values, boolean positive, Set<Types.TypeFlag> flags) {
		if (hasFailed)
			return;
		int[][] table = new int[values.length][1];
		for (int i = 0; i < values.length; i++)
			table[i][0] = values[i];
		buildCtrExtension(id, new XVarInteger[] { x }, table, positive, flags);
	}

	@Override
	public void buildCtrExtension(String id, XVarInteger[] list, int[][] tuples, boolean positive,
			Set<Types.TypeFlag> flags) {
		if (hasFailed)
			return;

		/*
		 * if (flags.contains(Types.TypeFlag.UNCLEAN_TUPLES)) { // You have possibly to
		 * clean tuples here, in order to remove invalid tuples. // A tuple is invalid
		 * if it contains a setValue $a$ for a variable $x$, not present in $dom(x)$ //
		 * Note that most of the time, tuples are already cleaned by the parser }
		 */

		try {

			if (!positive) {
				minicp.post(negTable(mapVarArray(list), tuples));
			} else {
				if (flags.contains(Types.TypeFlag.STARRED_TUPLES)) {
					minicp.post(shortTable(mapVarArray(list), tuples, Constants.STAR_INT));
				} else {
					minicp.post(table(mapVarArray(list), tuples));
				}
			}

		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	private void relConstraintVal(IntVar x, Types.TypeConditionOperatorRel operator, int k) {
		if (hasFailed)
			return;
		try {
			switch (operator) {
			case EQ:
				x.assign(k);
				break;
			case GE:
				x.removeBelow(k);
				break;
			case GT:
				x.removeBelow(k + 1);
				break;
			case LE:
				x.removeAbove(k);
				break;
			case LT:
				x.removeAbove(k - 1);
				break;
			case NE:
				x.remove(k);
				break;
			default:
				throw new InvalidParameterException("unknown condition");
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	private void relConstraintVar(IntVar x, Types.TypeConditionOperatorRel operator, IntVar y) {
		if (hasFailed)
			return;
		try {
			switch (operator) {
			case EQ:
				minicp.post(equal(x, y));
				break;
			case GE:
				minicp.post(largerOrEqual(x, y));
				break;
			case GT:
				minicp.post(larger(x, y));
				break;
			case LE:
				minicp.post(lessOrEqual(x, y));
				break;
			case LT:
				minicp.post(less(x, y));
				break;
			case NE:
				minicp.post(notEqual(x, y));
				break;
			default:
				throw new InvalidParameterException("unknown condition");
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	private void buildCrtWithCondition(String id, IntVar expr, Condition operator) {
		if (hasFailed)
			return;

		if (operator instanceof Condition.ConditionVal) {
			Condition.ConditionVal op = (Condition.ConditionVal) operator;
			relConstraintVal(expr, op.operator, (int) op.k);
		} else if (operator instanceof Condition.ConditionVar) {
			Condition.ConditionVar op = (Condition.ConditionVar) operator;
			relConstraintVar(expr, op.operator, mapVar.get(op.x));
		} else if (operator instanceof Condition.ConditionIntvl) {
			Condition.ConditionIntvl op = (Condition.ConditionIntvl) operator;
			try {
				switch (op.operator) {
				case IN:
					expr.removeAbove((int) op.max);
					expr.removeBelow((int) op.min);
					break;
				case NOTIN:
					BoolVar[] ltOrGt = new BoolVar[2];
					ltOrGt[0] = isLess(expr, (int) op.min);
					ltOrGt[1] = isLarger(expr, (int) op.max);
					minicp.post(or(ltOrGt));
					break;
				default:
					throw new InvalidParameterException("unknown condition");
				}
			} catch (InconsistencyException e) {
				hasFailed = true;
			}
		}
	}

	// UNARY PRIMITIVE CONSTRAINTS

	@Override
	public void buildCtrPrimitive(String id, XVarInteger x, Types.TypeConditionOperatorRel op, int k) {
		if (hasFailed)
			return;
		relConstraintVal(mapVar.get(x), op, k);
	}

	/*TODO
	@Override
	public void buildCtrPrimitive(String id, XVarInteger x, Types.TypeConditionOperatorRel op, int[] t) {
		if (hasFailed)
			return;

	}
	 */

	/*TODO
	@Override
	public void buildCtrPrimitive(String id, XVarInteger x, TypeConditionOperatorSet op, int min, int max) {
		if (hasFailed)
			return;

	}
	 */

	@Override
	public void buildCtrPrimitive(String id, XVarInteger x, Types.TypeArithmeticOperator aop, int p,
								  Types.TypeConditionOperatorRel op, int k) {
		if (hasFailed)
			return;

		try {
			switch (aop) {
			case ADD: // x + p op k
				intensionCompiler.postLinearRel(new int[]{1}, new IntVar[]{mapVar.get(x)}, op, (long) k - p);
				return;
			case SUB: // x - p op k
				intensionCompiler.postLinearRel(new int[]{1}, new IntVar[]{mapVar.get(x)}, op, (long) k + p);
				return;
			case MUL: // p*x op k
				if (p != 0) {
					intensionCompiler.postLinearRel(new int[]{p}, new IntVar[]{mapVar.get(x)}, op, k);
					return;
				}
				break;
			default:
				break;
			}
			IntVar r = arithmeticOperatorConstraintVal(mapVar.get(x), aop, p);
			relConstraintVal(r, op, k);
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	// BINARY PRIMITIVE CONSTRAINTS

	@Override
	public void buildCtrPrimitive(String id, XVarInteger x, Types.TypeUnaryArithmeticOperator aop, XVarInteger y) {
		if (hasFailed)
			return;
		try {
			IntVar r = unaryArithmeticOperatorConstraint(mapVar.get(y), aop);
			relConstraintVar(mapVar.get(x), Types.TypeConditionOperatorRel.EQ, r);
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrPrimitive(String id, XVarInteger x, Types.TypeArithmeticOperator aop, int p,
			Types.TypeConditionOperatorRel op, XVarInteger y) {
		if (hasFailed)
			return;
		try {
			switch (aop) {
			case ADD: // x + p op y  <=>  x - y op -p
				intensionCompiler.postLinearRel(new int[]{1, -1},
						new IntVar[]{mapVar.get(x), mapVar.get(y)}, op, -(long) p);
				return;
			case SUB: // x - p op y  <=>  x - y op p
				intensionCompiler.postLinearRel(new int[]{1, -1},
						new IntVar[]{mapVar.get(x), mapVar.get(y)}, op, p);
				return;
			case MUL: // p*x op y
				if (p != 0) {
					intensionCompiler.postLinearRel(new int[]{p, -1},
							new IntVar[]{mapVar.get(x), mapVar.get(y)}, op, 0);
					return;
				}
				break;
			default:
				break;
			}
			IntVar r = arithmeticOperatorConstraintVal(mapVar.get(x), aop, p);
			relConstraintVar(r, op, mapVar.get(y));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrPrimitive(String id, XVarInteger x, Types.TypeArithmeticOperator aop, XVarInteger y,
			Types.TypeConditionOperatorRel op, int k) {
		if (hasFailed)
			return;

		IntVar minicpX = mapVar.get(x);
		IntVar minicpY = mapVar.get(y);

		try {
			switch (aop) {
			case ADD: // x + y op k
				intensionCompiler.postLinearRel(new int[]{1, 1}, new IntVar[]{minicpX, minicpY}, op, k);
				return;
			case SUB: // x - y op k
				intensionCompiler.postLinearRel(new int[]{1, -1}, new IntVar[]{minicpX, minicpY}, op, k);
				return;
			default:
				break;
			}
			IntVar r = arithmeticOperatorConstraintVar(minicpX, aop, minicpY);
			relConstraintVal(r, op, k);
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	// TERNARY PRIMITIVE CONSTRAINTS

	@Override
	public void buildCtrPrimitive(String id, XVarInteger x, Types.TypeArithmeticOperator aop, XVarInteger y,
			Types.TypeConditionOperatorRel op, XVarInteger z) {
		if (hasFailed)
			return;

		try {
			switch (aop) {
			case ADD: // x + y op z
				intensionCompiler.postLinearRel(new int[]{1, 1, -1},
						new IntVar[]{mapVar.get(x), mapVar.get(y), mapVar.get(z)}, op, 0);
				return;
			case SUB: // x - y op z
				intensionCompiler.postLinearRel(new int[]{1, -1, -1},
						new IntVar[]{mapVar.get(x), mapVar.get(y), mapVar.get(z)}, op, 0);
				return;
			default:
				break;
			}
			IntVar r = arithmeticOperatorConstraintVar(mapVar.get(x), aop, mapVar.get(y));
			relConstraintVar(r, op, mapVar.get(z));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}
	public void buildCtrLogic(String id, XVarInteger x, XVarInteger y, Types.TypeConditionOperatorRel op, int k) {
		if (hasFailed)
			return;
		try {
			minicp.post(equal(mapVar.get(x),reifiedRelOperatorConstraintVal(mapVar.get(y),op,k)));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	public void buildCtrLogic(String id, XVarInteger x, XVarInteger y, Types.TypeConditionOperatorRel op, XVarInteger z) {
		if (hasFailed)
			return;
		try {
			minicp.post(equal(mapVar.get(x),reifiedRelOperatorConstraintVar(mapVar.get(y),op,mapVar.get(z))));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	/* TODO
	public void buildCtrLogic(String id, TypeLogicalOperator lop, XVarInteger[] vars)
	public void buildCtrLogic(String id, XVarInteger x, TypeEqNeOperator op, TypeLogicalOperator lop, XVarInteger[] vars)
	 */

	// The reified decomposition of intension expression trees lives in
	// ExprDecomposer (shared with the encapsulated Intension constraint);
	// these delegates keep the helper signatures used by the primitive and
	// logic callbacks.

	private IntVar unaryArithmeticOperatorConstraint(IntVar x, Types.TypeUnaryArithmeticOperator aop) {
		return exprDecomposer.unaryArithmeticOperatorConstraint(x, aop);
	}

	private IntVar arithmeticOperatorConstraintVal(IntVar x, Types.TypeArithmeticOperator aop, int p) {
		return exprDecomposer.arithmeticOperatorConstraintVal(x, aop, p);
	}

	private IntVar arithmeticOperatorConstraintVar(IntVar x, Types.TypeArithmeticOperator aop, IntVar y) {
		return exprDecomposer.arithmeticOperatorConstraintVar(x, aop, y);
	}

	private IntVar reifiedRelOperatorConstraintVar(IntVar x, Types.TypeConditionOperatorRel operator, IntVar y) {
		return exprDecomposer.reifiedRelOperatorConstraintVar(x, operator, y);
	}

	private IntVar reifiedRelOperatorConstraintVal(IntVar x, Types.TypeConditionOperatorRel operator, int y) {
		return exprDecomposer.reifiedRelOperatorConstraintVal(x, operator, y);
	}

	private IntVar parseExpr(XNode<XVarInteger> tree) {
		return intensionCompiler.compileArithmetic(tree);
	}


	@Override
	public void buildCtrSum(String id, XVarInteger[] list, Condition condition) {
		if (hasFailed)
			return;
		try {
			if ((condition instanceof Condition.ConditionVar) &&
					(((Condition.ConditionVar) condition).operator == Types.TypeConditionOperatorRel.EQ)) {
				// special case of equality with a variable: avoid creating an intermediate variable
				minicp.post(sum(mapVarArray(list),mapVar.get(((Condition.ConditionVar) condition).x)));
			} else {
				IntVar s = sum(mapVarArray(list));
				buildCrtWithCondition(id, s, condition);
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrSum(String id, XNode<XVarInteger>[] trees, Condition condition) {
		if (hasFailed)
			return;

		try {
			IntVar[] wx = new IntVar[trees.length];
			for (int i = 0; i < trees.length; i++) {
				wx[i] = parseExpr(trees[i]);
			}
			if ((condition instanceof Condition.ConditionVar) &&
					(((Condition.ConditionVar) condition).operator == Types.TypeConditionOperatorRel.EQ)) {
				// special case of equality with a variable: avoid creating an intermediate variable
				minicp.post(sum(wx,mapVar.get(((Condition.ConditionVar) condition).x)));
			} else {
				IntVar s = sum(wx);
				buildCrtWithCondition(id, s, condition);
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrSum(String id, XVarInteger[] list, int[] coeffs, Condition condition) {
		if (hasFailed)
			return;
		try {
			IntVar[] wx = new IntVar[list.length];
			for (int i = 0; i < list.length; i++) {
				wx[i] = mul(mapVar.get(list[i]), coeffs[i]);
			}
			if ((condition instanceof Condition.ConditionVar) &&
					(((Condition.ConditionVar) condition).operator == Types.TypeConditionOperatorRel.EQ)) {
				// special case of equality with a variable: avoid creating an intermediate variable
				minicp.post(sum(wx,mapVar.get(((Condition.ConditionVar) condition).x)));
			} else {
				IntVar s = sum(wx);
				buildCrtWithCondition(id, s, condition);
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrSum(String id, XNode<XVarInteger>[] trees, int[] coeffs, Condition condition) {
		if (hasFailed)
			return;
		try {
			IntVar[] wx = new IntVar[trees.length];
			for (int i = 0; i < trees.length; i++) {
				wx[i] = mul(parseExpr(trees[i]), coeffs[i]);
			}
			if ((condition instanceof Condition.ConditionVar) &&
					(((Condition.ConditionVar) condition).operator == Types.TypeConditionOperatorRel.EQ)) {
				// special case of equality with a variable: avoid creating an intermediate variable
				minicp.post(sum(wx,mapVar.get(((Condition.ConditionVar) condition).x)));
			} else {
				IntVar s = sum(wx);
				buildCrtWithCondition(id, s, condition);
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrSum(String id, XVarInteger[] list, XVarInteger[] coeffs, Condition condition) {
		if (hasFailed)
			return;
		try {
			IntVar[] wx = new IntVar[list.length];
			for (int i = 0; i < list.length; i++) {
				wx[i] = product(mapVar.get(list[i]), mapVar.get(coeffs[i]));
			}
			if ((condition instanceof Condition.ConditionVar) &&
					(((Condition.ConditionVar) condition).operator == Types.TypeConditionOperatorRel.EQ)) {
				// special case of equality with a variable: avoid creating an intermediate variable
				minicp.post(sum(wx,mapVar.get(((Condition.ConditionVar) condition).x)));
			} else {
				IntVar s = sum(wx);
				buildCrtWithCondition(id, s, condition);
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrSum(String id, XNode<XVarInteger>[] trees, XVarInteger[] coeffs, Condition condition) {
		if (hasFailed)
			return;
		try {
			IntVar[] wx = new IntVar[trees.length];
			for (int i = 0; i < trees.length; i++) {
				wx[i] = product(parseExpr(trees[i]), mapVar.get(coeffs[i]));
			}
			if ((condition instanceof Condition.ConditionVar) &&
					(((Condition.ConditionVar) condition).operator == Types.TypeConditionOperatorRel.EQ)) {
				// special case of equality with a variable: avoid creating an intermediate variable
				minicp.post(sum(wx,mapVar.get(((Condition.ConditionVar) condition).x)));
			} else {
				IntVar s = sum(wx);
				buildCrtWithCondition(id, s, condition);
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrAllDifferent(String id, XVarInteger[] list) {
		if (hasFailed)
			return;
		// Constraints
		try {
			IntVar[] xs = mapVarArray(list);
			minicp.post(allDifferent(xs));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrAllDifferent(String id, XNode<XVarInteger>[] trees) {
		if (hasFailed)
			return;
		try {
			IntVar[] xs = new IntVar[trees.length];
			for (int i=0; i<trees.length; i++) {
				xs[i] = parseExpr(trees[i]);
			}
			minicp.post(allDifferent(xs));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	// TODO: buildCtrAllDifferentExcept

	@Override
	public void buildCtrAllDifferentMatrix(String id, XVarInteger[][] matrix) {
		if (hasFailed)
			return;
		// Constraints
		try {
			for (XVarInteger[] list : matrix) {
				IntVar[] xs = mapVarArray(list);
				minicp.post(allDifferent(xs));
			}
			XVarInteger[][] tmatrix = transpose(matrix);
			for (XVarInteger[] list : tmatrix) {
				IntVar[] xs = mapVarArray(list);
				minicp.post(allDifferent(xs));
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrAllEqual(String id, XVarInteger[] list) {
		if (hasFailed)
			return;
		try {
			IntVar[] xs = mapVarArray(list);
			for (int i = 1; i < list.length; i++) {
				minicp.post(equal(xs[0],xs[i]));
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrAllEqual(String id, XNode<XVarInteger>[] trees) {
		if (hasFailed)
			return;
		try {
			IntVar[] xs = Arrays.stream(trees).map(this::parseExpr).toArray(IntVar[]::new);
			for (int i = 1; i < trees.length; i++) {
				minicp.post(equal(xs[0],xs[i]));
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	/** true iff no two entries are the same factor-graph node (see NotAllEqual) */
	private static boolean distinctBaseVars(IntVar[] xs) {
		for (int i = 0; i < xs.length; i++)
			for (int j = i + 1; j < xs.length; j++)
				if (xs[i].getBaseVar() == xs[j].getBaseVar())
					return false;
		return true;
	}

	@Override
	public void buildCtrNotAllEqual(String id, XVarInteger[] list) {
		// 2026-08-18: implemented for RamseyPartition (gcc corpus widening).
		// not-all-equal(x_0..x_{n-1})  <=>  exists i > 0 with x_i != x_0.
		if (hasFailed)
			return;
		try {
			IntVar[] xs = mapVarArray(list);
			// 2026-08-25 (GCC_EXPERIMENT.md §14): -Dminicpbp.nae.post=global posts
			// the relation as ONE factor instead of the reified decomposition
			// below, which routes it through n-1 booleans and hides its joint
			// structure from belief propagation. Default stays 'decomp' so that
			// every campaign predating the flag reproduces unchanged.
			if ("global".equals(System.getProperty("minicpbp.nae.post", "decomp")) && distinctBaseVars(xs)) {
				minicp.post(notAllEqual(xs));
				return;
			}
			BoolVar[] diff = new BoolVar[xs.length - 1];
			for (int i = 1; i < xs.length; i++)
				diff[i - 1] = isNotEqual(xs[i], xs[0]);
			minicp.post(or(diff));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrOrdered(String id, XVarInteger[] list, Types.TypeOperatorRel operator) {
		if (hasFailed)
			return;
		try {
			IntVar[] xs = mapVarArray(list);
			switch (operator) {
				case GE:
					for (int i = 0; i < xs.length-1; i++)
						minicp.post(lessOrEqual(xs[i+1], xs[i]));
					break;
				case GT:
					for (int i = 0; i < xs.length-1; i++)
						minicp.post(less(xs[i+1], xs[i]));
					break;
				case LE:
					for (int i = 0; i < xs.length-1; i++)
						minicp.post(lessOrEqual(xs[i], xs[i+1]));
					break;
				case LT:
					for (int i = 0; i < xs.length-1; i++)
						minicp.post(less(xs[i], xs[i+1]));
					break;
				default:
					throw new InvalidParameterException("inappropriate condition");
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrOrdered(String id, XVarInteger[] list, int[] lengths, Types.TypeOperatorRel operator) {
		if (hasFailed)
			return;
		try {
			IntVar[] xs = mapVarArray(list);
			switch (operator) {
				case GE:
					for (int i = 0; i < xs.length-1; i++)
						minicp.post(lessOrEqual(xs[i+1], plus(xs[i],lengths[i])));
					break;
				case GT:
					for (int i = 0; i < xs.length-1; i++)
						minicp.post(less(xs[i+1], plus(xs[i],lengths[i])));
					break;
				case LE:
					for (int i = 0; i < xs.length-1; i++)
						minicp.post(lessOrEqual(plus(xs[i],lengths[i]), xs[i+1]));
					break;
				case LT:
					for (int i = 0; i < xs.length-1; i++)
						minicp.post(less(plus(xs[i],lengths[i]), xs[i+1]));
					break;
				default:
					throw new InvalidParameterException("inappropriate condition");
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	// TODO: public void buildCtrOrdered(String id, XVarInteger[] list, XVarInteger[] lengths, TypeOperatorRel operator)

	@Override
	public void buildCtrLex(String id, XVarInteger[][] lists, Types.TypeOperatorRel operator) {
		if (hasFailed)
			return;
		try {
			IntVar[][] xs = new IntVar[lists.length][];
			int i = 0;
			for (XVarInteger[] list : lists) {
				xs[i++] = mapVarArray(list);
			}
			switch (operator) {
				case GE:
					for (i = 0; i < lists.length-1; i++)
						minicp.post(lexLessOrEqual(xs[i+1], xs[i]));
					break;
				case GT:
					for (i = 0; i < lists.length-1; i++)
						minicp.post(lexLess(xs[i+1], xs[i]));
					break;
				case LE:
					for (i = 0; i < lists.length-1; i++)
						minicp.post(lexLessOrEqual(xs[i], xs[i+1]));
					break;
				case LT:
					for (i = 0; i < lists.length-1; i++)
						minicp.post(lexLess(xs[i], xs[i+1]));
					break;
				default:
					throw new InvalidParameterException("inappropriate condition");
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrLexMatrix(String id, XVarInteger[][] matrix, Types.TypeOperatorRel operator) {
		if (hasFailed)
			return;
		try {
			IntVar[][] rows = new IntVar[matrix.length][];
			int i = 0;
			for (XVarInteger[] list : matrix) {
				rows[i++] = mapVarArray(list);
			}
			XVarInteger[][] tmatrix = transpose(matrix);
			IntVar[][] cols = new IntVar[tmatrix.length][];
			i = 0;
			for (XVarInteger[] list : tmatrix) {
				cols[i++] = mapVarArray(list);
			}
			switch (operator) {
				case GE:
					for (i = 0; i < rows.length-1; i++)
						minicp.post(lexLessOrEqual(rows[i+1], rows[i]));
					for (i = 0; i < cols.length-1; i++)
						minicp.post(lexLessOrEqual(cols[i+1], cols[i]));
					break;
				case GT:
					for (i = 0; i < rows.length-1; i++)
						minicp.post(lexLess(rows[i+1], rows[i]));
					for (i = 0; i < cols.length-1; i++)
						minicp.post(lexLess(cols[i+1], cols[i]));
					break;
				case LE:
					for (i = 0; i < rows.length-1; i++)
						minicp.post(lexLessOrEqual(rows[i], rows[i+1]));
					for (i = 0; i < cols.length-1; i++)
						minicp.post(lexLessOrEqual(cols[i], cols[i+1]));
					break;
				case LT:
					for (i = 0; i < rows.length-1; i++)
						minicp.post(lexLess(rows[i], rows[i+1]));
					for (i = 0; i < cols.length-1; i++)
						minicp.post(lexLess(cols[i], cols[i+1]));
					break;
				default:
					throw new InvalidParameterException("inappropriate condition");
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrPrecedence(String id, XVarInteger[] list) {
		if (hasFailed)
			return;
		IntVar[] xs = mapVarArray(list);
		SortedSet<Integer> values = new TreeSet<Integer>();
		for (int i = 0; i < xs.length; i++)
			for (int j = xs[i].min(); j <= xs[i].max(); j++)
				if (xs[i].contains(j)) {
					values.add(j);
				}
		buildCtrPrecedence(id,list,values.stream().mapToInt(i->i).toArray(),false);
	}

	@Override
	public void buildCtrPrecedence(String id, XVarInteger[] list, int[] values, boolean covered) {
		if (hasFailed)
			return;
		try {
			IntVar[] xs = mapVarArray(list);
			int minVal = xs[0].min();
			int maxVal = xs[0].max();
			for (IntVar x : xs) {
				if (x.min() < minVal)
					minVal = x.min();
				if (x.max() > maxVal)
					maxVal = x.max();
			}
			int[][] A = new int[values.length+1][maxVal-minVal+1];
			// TODO: potentially wasteful in terms of space; define A over union of domains only
			for (int i = 0; i <= values.length; i++) {
				for (int j = 0; j <= maxVal-minVal; j++) {
					A[i][j] = i; // loop by default
				}
				if (i < values.length) A[i][values[i]] = i+1; // first occurrence of values[i]
				for (int j = i+1; j < values.length; j++) {
					A[i][values[j]] = -1; // cannot be used yet
				}
			}
			if (covered) {
				List<Integer> f = new ArrayList<Integer>();
				f.add(values.length);
				minicp.post(regular(xs,A,f));
			}else {
				minicp.post(regular(xs,A));
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	// the code of transpose method is from choco solver:
	// https://tinyurl.com/tlgz7mt
	/**
	 * Transposes a matrix M[n][m] in a matrix M<sup>T</sup>[m][n] such that
	 * M<sup>T</sup>[i][j] = M[j][i]
	 * 
	 * @param matrix matrix to transpose
	 * @param        <T> the class of the objects in the input matrix
	 * @return a matrix
	 */
	@SuppressWarnings("unchecked")
	public static <T> T[][] transpose(T[][] matrix) {
		T[][] ret = (T[][]) newInstance(matrix.getClass().getComponentType(), matrix[0].length);
		for (int i = 0; i < ret.length; i++) {
			ret[i] = (T[]) newInstance(matrix[0].getClass().getComponentType(), matrix.length);
		}

		for (int i = 0; i < matrix.length; i++)
			for (int j = 0; j < matrix[i].length; j++)
				ret[j][i] = matrix[i][j];

		return ret;

	}

	@Override
	public void buildCtrElement(String id, XVarInteger[] list, Condition cond) {
		if(hasFailed)
			return;
		try {
			IntVar[] xs = mapVarArray(list);
			if (cond instanceof Condition.ConditionVal) {
				int val = (int) ((Condition.ConditionVal) cond).k;
				minicp.post(atleast(xs, val, 1));
			} else if (cond instanceof Condition.ConditionVar) {
				IntVar z = mapVar.get(((Condition.ConditionVar) cond).x);
				IntVar y = makeIntVar(minicp, 0, xs.length-1);
				minicp.post(element(xs, y, z));
			}
			else {
				Log.info("c Element constraint with an unsupported condition");
				System.exit(1);
			}
		} catch(InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrElement(String id, XVarInteger[] list, int startIndex, XVarInteger index, Types.TypeRank rank, Condition cond) {
		if (hasFailed)
			return;
		try {
			IntVar[] xs = mapVarArray(list);
			IntVar y = minus(mapVar.get(index),startIndex);
			switch(rank) {
				case ANY:
					if (cond instanceof Condition.ConditionVal) {
						int val = (int) ((Condition.ConditionVal) cond).k;
						Types.TypeConditionOperatorRel op = ((Condition.ConditionVal) cond).operator;
						relConstraintVal(element(xs, y), op, val);
					} else if (cond instanceof Condition.ConditionVar) {
						IntVar z = mapVar.get(((Condition.ConditionVar) cond).x);
						Types.TypeConditionOperatorRel op = ((Condition.ConditionVar) cond).operator;
						relConstraintVar(element(xs, y), op, z);
					}
					else {
						Log.info("c Element constraint with an unsupported condition");
						System.exit(1);
					}
					break;
				case FIRST:
					Log.info("c Ranking type for Element Constraint is not supported yet");
					System.exit(1);
					break;
				case LAST:
					Log.info("c Ranking type for Element Constraint is not supported yet");
					System.exit(1);
					break;
			}
		} catch(InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrElement(String id, int[] list, int startIndex, XVarInteger index, Types.TypeRank rank, Condition cond) {
		if (hasFailed)
			return;
		try {
			IntVar y = minus(mapVar.get(index),startIndex);
			switch(rank) {
				case ANY:
					if (cond instanceof Condition.ConditionVal) {
						int val = (int) ((Condition.ConditionVal) cond).k;
						Types.TypeConditionOperatorRel op = ((Condition.ConditionVal) cond).operator;
						relConstraintVal(element(list, y), op, val);
					} else if (cond instanceof Condition.ConditionVar) {
						IntVar z = mapVar.get(((Condition.ConditionVar) cond).x);
						Types.TypeConditionOperatorRel op = ((Condition.ConditionVar) cond).operator;
						relConstraintVar(element(list, y), op, z);
					}
					else {
						Log.info("c Element constraint with an unsupported condition");
						System.exit(1);
					}
					break;
				case FIRST:
					Log.info("c Ranking type for Element Constraint is not supported yet");
					System.exit(1);
					break;
				case LAST:
					Log.info("c Ranking type for Element Constraint is not supported yet");
					System.exit(1);
					break;
			}
		} catch(InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrElement(String id, int[][] matrix, int startRowIndex, XVarInteger rowIndex, int startColIndex, XVarInteger colIndex,
								 Condition cond) {
		if (hasFailed)
			return;
		try {
			IntVar x = minus(mapVar.get(rowIndex),startRowIndex);
			IntVar y = minus(mapVar.get(colIndex),startColIndex);
			if (cond instanceof Condition.ConditionVal) {
				int val = (int) ((Condition.ConditionVal) cond).k;
				Types.TypeConditionOperatorRel op = ((Condition.ConditionVal) cond).operator;
				relConstraintVal(element(matrix, x, y), op, val);
			} else if (cond instanceof Condition.ConditionVar) {
				IntVar z = mapVar.get(((Condition.ConditionVar) cond).x);
				Types.TypeConditionOperatorRel op = ((Condition.ConditionVar) cond).operator;
				relConstraintVar(element(matrix, x, y), op, z);
			}
			else {
				Log.info("c Element constraint with an unsupported condition");
				System.exit(1);
			}
		} catch(InconsistencyException e) {
			hasFailed = true;
		}
	}

	private void setObj(IntVar obj, boolean minimization) {
		IntVar minobj = minimization ? obj : minus(obj);

		objectiveMinimize = Optional.of(minobj);
		realObjective = Optional.of(obj);
	}

	@Override
	public void buildObjToMinimize(String id, XVarInteger x) {
		if (hasFailed)
			return;

		setObj(mapVar.get(x), true);
	}

	@Override
	public void buildObjToMinimize(String id, Types.TypeObjective type, XVarInteger[] list) {
		if (hasFailed)
			return;
		try {
			if (type == Types.TypeObjective.MAXIMUM) {
				IntVar[] xs = mapVarArray(list);
				setObj(maximum(xs), true);
			} else if (type == Types.TypeObjective.MINIMUM) {
				IntVar[] xs = mapVarArray(list);
				setObj(minimum(xs), true);
			} else if (type == Types.TypeObjective.SUM) {
				IntVar s = sum(mapVarArray(list));
				setObj(s, true);
			} else {
				throw new NotImplementedException();
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildObjToMinimize(String id, Types.TypeObjective type, XVarInteger[] list, int[] coeffs) {
		if (hasFailed)
			return;

		IntVar[] wx = new IntVar[list.length];
		for (int i = 0; i < list.length; i++) {
			wx[i] = mul(mapVar.get(list[i]), coeffs[i]);
		}
		try {
			IntVar s = sum(wx);
			setObj(s, true);
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildObjToMaximize(String id, XVarInteger x) {
		if (hasFailed)
			return;

		setObj(mapVar.get(x), false);
	}

	@Override
	public void buildObjToMaximize(String id, Types.TypeObjective type, XVarInteger[] list) {
		if (hasFailed)
			return;

		try {
			if (type == Types.TypeObjective.MAXIMUM) {
				IntVar[] xs = mapVarArray(list);
				setObj(maximum(xs), false);
			} else if (type == Types.TypeObjective.MINIMUM) {
				IntVar[] xs = mapVarArray(list);
				setObj(minimum(xs), false);
			} else if (type == Types.TypeObjective.SUM) {
				IntVar s = sum(mapVarArray(list));
				setObj(s, false);
			} else {
				throw new NotImplementedException();
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildObjToMaximize(String id, Types.TypeObjective type, XVarInteger[] list, int[] coeffs) {
		if (hasFailed)
			return;

		IntVar[] wx = new IntVar[list.length];
		for (int i = 0; i < list.length; i++) {
			wx[i] = mul(mapVar.get(list[i]), coeffs[i]);
		}
		try {
			IntVar s = sum(wx);
			setObj(s, false);
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrIntension(String id, XVarInteger[] scope, XNodeParent<XVarInteger> tree) {
		if (hasFailed)
			return;
		try {
			if (minicpbp.util.BPConfig.INTENSION_ENCAPSULATED) {
				// one encapsulated Intension factor over the (distinct)
				// boundary variables; the reified decomposition is internal
				LinkedHashSet<XVarInteger> distinct = new LinkedHashSet<>(Arrays.asList(scope));
				XVarInteger[] xScope = distinct.toArray(new XVarInteger[0]);
				IntVar[] boundary = new IntVar[xScope.length];
				for (int i = 0; i < xScope.length; i++)
					boundary[i] = mapVar.get(xScope[i]);
				Constraint c = new Intension(minicp, boundary, copies -> {
					Map<XVarInteger, IntVar> toCopy = new HashMap<>();
					for (int i = 0; i < xScope.length; i++)
						toCopy.put(xScope[i], copies[i]);
					return new ExprDecomposer(minicp, toCopy::get).parseExpr(tree);
				});
				c.setName("intension:" + id);
				minicp.post(c);
			} else {
				// flat path: structural compilation posts direct constraints
				// (or domain operations); reification only as fallback
				intensionCompiler.compileConstraint(tree);
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrMaximum(String id, XVarInteger[] list, Condition condition) {
		if (hasFailed)
			return;
		try {
			IntVar[] xs = mapVarArray(list);
			if ((condition instanceof Condition.ConditionVar) &&
					(((Condition.ConditionVar) condition).operator == Types.TypeConditionOperatorRel.EQ)) {
				// special case of equality with a variable: avoid creating an intermediate variable
				minicp.post(maximum(xs,mapVar.get(((Condition.ConditionVar) condition).x)));
			} else {
				IntVar s = maximum(xs);
				buildCrtWithCondition(id, s, condition);
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrMaximum(String id, XNode<XVarInteger>[] trees, Condition condition) {
		if (hasFailed)
			return;
		try {
			IntVar[] xs = new IntVar[trees.length];
			for (int i=0; i<trees.length; i++) {
				xs[i] = parseExpr(trees[i]);
			}
			if ((condition instanceof Condition.ConditionVar) &&
					(((Condition.ConditionVar) condition).operator == Types.TypeConditionOperatorRel.EQ)) {
				// special case of equality with a variable: avoid creating an intermediate variable
				minicp.post(maximum(xs,mapVar.get(((Condition.ConditionVar) condition).x)));
			} else {
				IntVar s = maximum(xs);
				buildCrtWithCondition(id, s, condition);
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrMinimum(String id, XVarInteger[] list, Condition condition) {
		if (hasFailed)
			return;
		try {
			IntVar[] xs = mapVarArray(list);
			if ((condition instanceof Condition.ConditionVar) &&
					(((Condition.ConditionVar) condition).operator == Types.TypeConditionOperatorRel.EQ)) {
				// special case of equality with a variable: avoid creating an intermediate variable
				minicp.post(minimum(xs,mapVar.get(((Condition.ConditionVar) condition).x)));
			} else {
				IntVar s = Factory.minimum(xs);
				buildCrtWithCondition(id, s, condition);
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrMinimum(String id, XNode<XVarInteger>[] trees, Condition condition) {
		if (hasFailed)
			return;
		try {
			IntVar[] xs = new IntVar[trees.length];
			for (int i=0; i<trees.length; i++) {
				xs[i] = parseExpr(trees[i]);
			}
			if ((condition instanceof Condition.ConditionVar) &&
					(((Condition.ConditionVar) condition).operator == Types.TypeConditionOperatorRel.EQ)) {
				// special case of equality with a variable: avoid creating an intermediate variable
				minicp.post(minimum(xs,mapVar.get(((Condition.ConditionVar) condition).x)));
			} else {
				IntVar s = minimum(xs);
				buildCrtWithCondition(id, s, condition);
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrCount(String id, XVarInteger[] list, int[] values, Condition condition) {
		if (hasFailed)
			return;
		try {
			Types.TypeConditionOperatorRel operator = null;
			if (condition instanceof Condition.ConditionVar) {
				IntVar countVar = mapVar.get(((Condition.ConditionVar) condition).x);
				operator = ((Condition.ConditionVar) condition).operator;
				IntVar[] xs = mapVarArray(list);
				IntVar occ = makeIntVar(minicp,0,xs.length);
				minicp.post(among(xs,values,occ));
				relConstraintVar(occ,operator,countVar);
			} else if (condition instanceof Condition.ConditionVal) {
				int countVal = (int) ((Condition.ConditionVal) condition).k;
				operator = ((Condition.ConditionVal) condition).operator;
				IntVar[] xs = mapVarArray(list);
				switch (operator) {
					case EQ:
						minicp.post(exactly(xs,values,countVal));
						break;
					case GE:
						minicp.post(atleast(xs,values,countVal));
						break;
					case GT:
						minicp.post(atleast(xs,values,countVal+1));
						break;
					case LE:
						minicp.post(atmost(xs,values,countVal));
						break;
					case LT:
						minicp.post(atmost(xs,values,countVal-1));
						break;
					case NE:
						IntVar occ = makeIntVar(minicp,0,xs.length);
						occ.remove(countVal);
						minicp.post(among(xs,values,occ));
						break;
					default:
						throw new InvalidParameterException("unknown condition");
				}
			} else {
				throw new InvalidParameterException("unknown condition");
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrCount(String id, XNode<XVarInteger>[] trees, int[] values, Condition condition) {
		if (hasFailed)
			return;
		try {
			Types.TypeConditionOperatorRel operator = null;
			if (condition instanceof Condition.ConditionVar) {
				IntVar countVar = mapVar.get(((Condition.ConditionVar) condition).x);
				operator = ((Condition.ConditionVar) condition).operator;
				IntVar[] xs = new IntVar[trees.length];
				for (int i = 0; i < trees.length; i++) {
					xs[i] = parseExpr(trees[i]);
				}
				IntVar occ = makeIntVar(minicp,0,xs.length);
				minicp.post(among(xs,values,occ));
				relConstraintVar(occ,operator,countVar);
			} else if (condition instanceof Condition.ConditionVal) {
				int countVal = (int) ((Condition.ConditionVal) condition).k;
				operator = ((Condition.ConditionVal) condition).operator;
				IntVar[] xs = new IntVar[trees.length];
				for (int i = 0; i < trees.length; i++) {
					xs[i] = parseExpr(trees[i]);
				}
				switch (operator) {
					case EQ:
						minicp.post(exactly(xs,values,countVal));
						break;
					case GE:
						minicp.post(atleast(xs,values,countVal));
						break;
					case GT:
						minicp.post(atleast(xs,values,countVal+1));
						break;
					case LE:
						minicp.post(atmost(xs,values,countVal));
						break;
					case LT:
						minicp.post(atmost(xs,values,countVal-1));
						break;
					case NE:
						IntVar occ = makeIntVar(minicp,0,xs.length);
						occ.remove(countVal);
						minicp.post(among(xs,values,occ));
						break;
					default:
						throw new InvalidParameterException("unknown condition");
				}
			} else {
				throw new InvalidParameterException("unknown condition");
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}
/* TODO?
	@Override
	public void buildCtrCount(String id, XVarInteger[] list, XVarInteger[] values, Condition condition) {
	}
*/
	@Override
	public void buildCtrAtLeast(String id, XVarInteger[] list, int value, int k) {
		if (hasFailed)
			return;
		try {
			IntVar[] xs = mapVarArray(list);
			minicp.post(atleast(xs, value, k));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrAtMost(String id, XVarInteger[] list, int value, int k) {
		if (hasFailed)
			return;
		try {
			IntVar[] xs = mapVarArray(list);
			minicp.post(atmost(xs, value, k));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrExactly(String id, XVarInteger[] list, int value, int k) {
		if (hasFailed)
			return;
		try {
			IntVar[] xs = mapVarArray(list);
			minicp.post(exactly(xs, value, k));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrExactly(String id, XVarInteger[] list, int value, XVarInteger k) {
		if (hasFailed)
			return;
		try {
			IntVar[] xs = mapVarArray(list);
			minicp.post(among(xs, value, mapVar.get(k)));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrAmong(String id, XVarInteger[] list, int[] values, int k) {
		if (hasFailed)
			return;
		try {
			IntVar[] xs = mapVarArray(list);
			minicp.post(exactly(xs, values, k));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrAmong(String id, XVarInteger[] list, int[] values, XVarInteger k) {
		if (hasFailed)
			return;
		try {
			IntVar[] xs = mapVarArray(list);
			minicp.post(among(xs, values, mapVar.get(k)));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrCardinality(String id, XVarInteger[] list, boolean closed, int[] values, XVarInteger[] occurs) {
		if (hasFailed)
			return;
		try {
			if (closed)
				throw new NotImplementedException();
			IntVar[] xs = mapVarArray(list);
			IntVar[] os = mapVarArray(occurs);
			minicp.post(cardinality(xs, values, os));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrCardinality(String id, XVarInteger[] list, boolean closed, int[] values, int[] occurs) {
		if (hasFailed)
			return;
		try {
			if (closed)
				throw new NotImplementedException();
			IntVar[] xs = mapVarArray(list);
			minicp.post(cardinality(xs, values, occurs));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrCardinality(String id, XVarInteger[] list, boolean closed, int[] values, int[] occursMin,
			int[] occursMax) {
		if (hasFailed)
			return;
		try {
			if (closed)
				throw new NotImplementedException();
			IntVar[] xs = mapVarArray(list);
			minicp.post(cardinality(xs, values, occursMin, occursMax));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrNValues(String id, XVarInteger[] list, Condition condition) {
		if (hasFailed)
			return;
		try {
			IntVar[] xs = mapVarArray(list);
			if ((condition instanceof Condition.ConditionVar) &&
					(((Condition.ConditionVar) condition).operator == Types.TypeConditionOperatorRel.EQ)) {
				// special case of equality with a variable: avoid creating an intermediate variable
				minicp.post(nValues(xs,mapVar.get(((Condition.ConditionVar) condition).x)));
			} else {
				IntVar nDistinct = makeIntVar(minicp, 0, xs.length);
				minicp.post(nValues(xs, nDistinct));
				buildCrtWithCondition(id, nDistinct, condition);
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	/* TODO?
	@Override
	public void buildCtrNValuesExcept(String id, XVarInteger[] list, int[] except, Condition condition) {
	}
*/

	@Override
	public void buildCtrNValues(String id, XNode<XVarInteger>[] trees, Condition condition) {
		if (hasFailed)
			return;
		try {
			IntVar[] xs = new IntVar[trees.length];
			for (int i=0; i<trees.length; i++) {
				xs[i] = parseExpr(trees[i]);
			}
			if ((condition instanceof Condition.ConditionVar) &&
					(((Condition.ConditionVar) condition).operator == Types.TypeConditionOperatorRel.EQ)) {
				// special case of equality with a variable: avoid creating an intermediate variable
				minicp.post(nValues(xs,mapVar.get(((Condition.ConditionVar) condition).x)));
			} else {
				IntVar nDistinct = makeIntVar(minicp, 0, xs.length);
				minicp.post(nValues(xs, nDistinct));
				buildCrtWithCondition(id, nDistinct, condition);
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrInstantiation(String id, XVarInteger[] list, int[] values) {
		if (hasFailed)
			return;
		try {
			IntVar[] xs = mapVarArray(list);
			assert (xs.length == values.length);
			for (int i = 0; i < xs.length; i++)
				xs[i].assign(values[i]);
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrClause(String id, XVarInteger[] pos, XVarInteger[] neg) {
		if (hasFailed)
			return;
		try {
			IntVar[] xpos = mapVarArray(pos);
			IntVar[] xneg = mapVarArray(neg);
			BoolVar[] literals = new BoolVar[xpos.length + xneg.length];
			for (int i = 0; i < xpos.length; i++) {
				literals[i] = makeBoolVar(minicp);
				minicp.post(equal(literals[i],xpos[i]));
			}
			for (int i = 0; i < xneg.length; i++) {
				literals[xpos.length + i] = makeBoolVar(minicp);
				minicp.post(equal(not(literals[xpos.length + i]),xneg[i]));
			}
			minicp.post(or(literals));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrChannel(String id, XVarInteger[] list, int startIndex) {
		if (hasFailed)
			return;
		try {
			IntVar[] x = mapVarArray(list);
			minicp.post(inverse(x,x,startIndex));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrChannel(String id, XVarInteger[] list1, int startIndex1, XVarInteger[] list2, int startIndex2) {
		if (hasFailed)
			return;
		if (startIndex1 != startIndex2) {
			Log.info("c Channel constraint with two different start indices?!?");
			System.exit(1);
		}
		try {
			IntVar[] x1 = mapVarArray(list1);
			IntVar[] x2 = mapVarArray(list2);
			minicp.post(inverse(x1,x2,startIndex1));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrChannel(String id, XVarInteger[] list, int startIndex, XVarInteger value) {
		if (hasFailed)
			return;
		try {
			IntVar[] x = mapVarArray(list); // assumes this is an array of 0-1 vara
			IntVar v = (startIndex == 0 ? mapVar.get(value) : new IntVarViewOffset(mapVar.get(value), -startIndex));
			minicp.post(element(x,v,1));
			minicp.post(sum(x,1));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrRegular(String id, XVarInteger[] list, Transition[] transitions, String startState,
								String[] finalStates) {
		if (hasFailed)
			return;
		try {
			IntVar[] x = mapVarArray(list);

			int minVal = x[0].min();
			int maxVal = x[0].max();
			for (IntVar y : x) {
				if (y.min() < minVal)
					minVal = y.min();
				if (y.max() > maxVal)
					maxVal = y.max();
			}

			// create var view of x with domain starting at zero (regular constraint currently requires this)
			IntVar[] x_withDomStartingAtZero = new IntVar[x.length];
			for (int i = 0; i < x.length; i++) {
				x_withDomStartingAtZero[i] = minus(x[i], minVal);
			}

			Map<String, Integer> stateMap = new HashMap<String, Integer>();

			ArrayList<int[]> A0 = new ArrayList<int[]>();
			for (Transition tr : transitions) {
				String from = (String) tr.start;
				if (!stateMap.containsKey(from)) {
					stateMap.put(from, stateMap.size());
					// create an empty entry for that new state in the transition table
					int[] no_outgoing_arcs = new int[maxVal-minVal+1];
					for (int i = 0; i < maxVal-minVal+1; i++) {
						no_outgoing_arcs[i] = -1;
					}
					A0.add(no_outgoing_arcs);
				}

				int value = ((Long) tr.value).intValue();

				String to = (String) tr.end;
				if (!stateMap.containsKey(to)) {
					stateMap.put(to, stateMap.size());
					// create an empty entry for that new state in the transition table
					int[] no_outgoing_arcs = new int[maxVal-minVal+1];
					for (int i = 0; i < maxVal-minVal+1; i++) {
						no_outgoing_arcs[i] = -1;
					}
					A0.add(no_outgoing_arcs);
				}

				int fromIndex = stateMap.get(from);
				int toIndex = stateMap.get(to);
				if (value>=minVal && value<=maxVal) // otherwise it will never be used
					A0.get(fromIndex)[value-minVal] = toIndex;
			}

			int[][] A = new int[A0.size()][maxVal-minVal+1];
			for (int i = 0; i < A0.size(); i++)
				A[i] = A0.get(i);

			int s = stateMap.get(startState);
			List<Integer> f = Arrays.stream(finalStates).map(stateMap::get).collect(Collectors.toList());

			minicp.post(regular(x_withDomStartingAtZero, A, s, f));

		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	// TODO: buildCtrStretch(String id, XVarInteger[] list, int[] values, int[] widthsMin, int[] widthsMax);
	// TODO: buildCtrStretch(String id, XVarInteger[] list, int[] values, int[] widthsMin, int[] widthsMax, int[][] patterns);

	// TODO: buildCtrMDD(String id, XVarInteger[] list, Transition[] transitions) http://xcsp.org/format3.pdf

	@Override
	public void buildCtrCircuit(String id, XVarInteger[] list, int startIndex) {
		// TODO this signature (without size parameter) should allow self-loops but it currently won't...
		if (hasFailed)
			return;
		try {
			IntVar[] x = mapVarArray(list);
			minicp.post(circuit(x, startIndex));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrBinPacking(String id, XVarInteger[] list, int[] sizes, Condition condition) {
		if (hasFailed)
			return;
		try {
			IntVar[] b = mapVarArray(list);
			if (!(condition instanceof Condition.ConditionVal)) {
				throw new InvalidParameterException("bin packing constraint with inappropriate condition");
			}
			int	capacity = (int) ((Condition.ConditionVal) condition).k;
			Types.TypeConditionOperatorRel operator = ((Condition.ConditionVal) condition).operator;
			int lastBin = 0;
			for (int i=0; i < b.length; i++) {
				if (b[i].max() > lastBin)
					lastBin = b[i].max();
			}
			IntVar[] l = new IntVar[lastBin+1];
			// restrict the capacity of bins
			switch (operator) {
				case LE:
					for (int j = 0; j <= lastBin; j++) {
						l[j] = makeIntVar(minicp,0, capacity);
					}
					break;
				case LT:
					for (int j = 0; j <= lastBin; j++) {
						l[j] = makeIntVar(minicp,0, capacity-1);
					}
					break;
				case EQ:
					for (int j = 0; j <= lastBin; j++) {
						l[j] = makeIntVar(minicp, capacity, capacity);
					}
					break;
				default:
					throw new InvalidParameterException("bin packing constraint with inappropriate condition");
			}
			minicp.post(binPacking(b, sizes, l));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrBinPacking(String id, XVarInteger[] list, int[] sizes, int[] capacities, boolean loads) {
		// If loads is true then these capacities correspond to the exact load. Difference between <limits> and <loads>
		if (hasFailed)
			return;
		try {
			IntVar[] b = mapVarArray(list);
			IntVar[] l = new IntVar[capacities.length];
			// restrict the capacity of bins
			if (loads) {
				for (int j = 0; j < l.length; j++) {
					l[j] = makeIntVar(minicp, capacities[j], capacities[j]);
				}
			} else {
				for (int j = 0; j < l.length; j++) {
					l[j] = makeIntVar(minicp, 0, capacities[j]);
				}
			}
			minicp.post(binPacking(b, sizes, l));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrBinPacking(String id, XVarInteger[] list, int[] sizes, XVarInteger[] capacities, boolean loads) {
		// If loads is true than these capacities correspond to the exact load. Difference between <limits> and <loads>
		if (hasFailed)
			return;
		try {
			IntVar[] b = mapVarArray(list);
			IntVar[] caps = mapVarArray(capacities);
			// restrict the capacity of bins
			if (loads) {
				minicp.post(binPacking(b, sizes, caps));
			} else {
				IntVar[] l = new IntVar[capacities.length];
				for (int j = 0; j < l.length; j++) {
					l[j] = makeIntVar(minicp, 0, caps[j].max());
					minicp.post(lessOrEqual(l[j], caps[j]));
				}
				minicp.post(binPacking(b, sizes, l));
			}
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrBinPacking(String id, XVarInteger[] list, int[] sizes, Condition[] conditions, int startIndex) {
		if (hasFailed)
			return;
		try {
			IntVar[] b;
			if (startIndex == 0) {
				b = mapVarArray(list);
			} else {
				b = new IntVar[list.length];
				for (int i=0; i<list.length; i++) {
					b[i] = new IntVarViewOffset(mapVar.get(list[i]), -startIndex);
				}
			}
			IntVar[] l = new IntVar[conditions.length];
			for (int j = 0; j < l.length; j++) {
				if (!(conditions[j] instanceof Condition.ConditionVal)) {
					throw new InvalidParameterException("bin packing constraint with inappropriate condition");
				}
				int capacity = (int) ((Condition.ConditionVal) conditions[j]).k;
				Types.TypeConditionOperatorRel operator = ((Condition.ConditionVal) conditions[j]).operator;
				// restrict the capacity of bins
				switch (operator) {
					case LE:
						l[j] = makeIntVar(minicp, 0, capacity);
						break;
					case LT:
						l[j] = makeIntVar(minicp, 0, capacity - 1);
						break;
					case EQ:
						l[j] = makeIntVar(minicp, capacity, capacity);
						break;
					default:
						throw new InvalidParameterException("bin packing constraint with inappropriate condition");
				}
			}
			minicp.post(binPacking(b, sizes, l));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrKnapsack(String id, XVarInteger[] list, int[] weights, Condition wcondition, int[] profits, Condition pcondition) {
		if (hasFailed)
			return;
		try {
			IntVar[] xs = mapVarArray(list);
			int maxWeight=0, minProfit=0;
			Types.TypeConditionOperatorRel woperator=null, poperator=null;
			if (wcondition instanceof Condition.ConditionVal) {
				maxWeight = (int) ((Condition.ConditionVal) wcondition).k;
					woperator = ((Condition.ConditionVal) wcondition).operator;
			} else {
				Log.info("c Knapsack constraint with an unsupported wcondition");
				System.exit(1);
			}
			if ((woperator != Types.TypeConditionOperatorRel.EQ) && (woperator != Types.TypeConditionOperatorRel.LE) && (woperator != Types.TypeConditionOperatorRel.LT)) {
				Log.info("c Knapsack constraint with an unsupported wcondition");
				System.exit(1);
			}
			if (pcondition instanceof Condition.ConditionVal) {
				minProfit = (int) ((Condition.ConditionVal) pcondition).k;
				poperator = ((Condition.ConditionVal) pcondition).operator;
			} else {
				Log.info("c Knapsack constraint with an unsupported pcondition");
				System.exit(1);
			}
			if ((poperator != Types.TypeConditionOperatorRel.EQ) && (poperator != Types.TypeConditionOperatorRel.GE) && (poperator != Types.TypeConditionOperatorRel.GT)) {
				Log.info("c Knapsack constraint with an unsupported pcondition");
				System.exit(1);
			}
			relConstraintVal(sum(weights,xs), woperator, maxWeight);
			relConstraintVal(sum(profits,xs), poperator, minProfit);
			} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrNoOverlap(String id, XVarInteger[] origins, int[] lengths, boolean zeroIgnored) {
		if (hasFailed)
			return;
		if (!zeroIgnored) {
			Log.info("c NoOverlap constraint with unsupported zeroIgnored=false");
			System.exit(1);
		}
		try {
			IntVar[] starts = mapVarArray(origins);
			minicp.post(disjunctive(starts,lengths));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	@Override
	public void buildCtrCumulative(String id, XVarInteger[] origins, int[] lengths, int[] heights, Condition condition) {
		if (hasFailed)
			return;
		try {
			IntVar[] starts = mapVarArray(origins);
			if (!(condition instanceof Condition.ConditionVal) || (((Condition.ConditionVal) condition).operator != Types.TypeConditionOperatorRel.LE)) {
				Log.info("c Cumulative constraint with an unsupported condition");
				System.exit(1);
			}
			minicp.post(cumulative(starts,lengths,heights,(int) ((Condition.ConditionVal) condition).k));
		} catch (InconsistencyException e) {
			hasFailed = true;
		}
	}

	static class EntryComparator implements Comparator<Map.Entry<XVarInteger, IntVar>> {
		@Override
		public int compare(Map.Entry<XVarInteger, IntVar> o1, Map.Entry<XVarInteger, IntVar> o2) {
			return o1.getKey().id.compareTo(o2.getKey().id);
		}
	}

	public String solve(int nSolution, int timeOut) {
		AtomicReference<String> lastSolution = new AtomicReference<>("");
		Long t0 = System.currentTimeMillis();

		solve((solution, value) -> {
			Log.info("solfound " + (value == Integer.MAX_VALUE ? value : "solution"));
			lastSolution.set(solution);
		}, ss -> {
			int nSols = isCOP() ? nSolution : 1;
			return (System.currentTimeMillis() - t0 >= timeOut * 1000 || ss.numberOfSolutions() >= nSols);
		});

		return lastSolution.get();
	}

	public void buildAnnotationDecision(XVarInteger[] list) {
		decisionVars.clear();
		Arrays.stream(list).map(mapVar::get).forEach(decisionVars::add);
	}

	/**
	 * @param onSolution: void onSolution(solution, obj). If not a COP, obj =
	 *        Integer.MAXVALUE
	 * @param shouldStop: boolean shouldStop(stats, isCOP).
	 * @return Stats
	 */
	public SearchStatistics solve(BiConsumer<String, Integer> onSolution,
			Function<SearchStatistics, Boolean> shouldStop) {

		IntVar[] vars = mapVar.entrySet().stream().sorted(new EntryComparator()).map(Map.Entry::getValue)
				.toArray(IntVar[]::new);
		LDSearch search;
		// TODO change firstfail to maxMarginalStrength
		if (decisionVars.isEmpty()) {
			search = makeLds(minicp, firstFail(vars));
		} else {
			search = makeLds(minicp, and(firstFail(decisionVars.toArray(new IntVar[0])), firstFail(vars)));
		}

		if (objectiveMinimize.isPresent()) {
			try {
				minicp.minimize(objectiveMinimize.get());
			} catch (InconsistencyException e) {
				hasFailed = true;
			}
		}

		if (hasFailed) {
			throw InconsistencyException.INCONSISTENCY;
		}

		search.onSolution(() -> {
			StringBuilder sol = new StringBuilder("<instantiation>\n\t<list>\n\t\t");
			for (XVarInteger x : xVars)
				sol.append(x.id()).append(" ");
			sol.append("\n\t</list>\n\t<values>\n\t\t");
			for (IntVar x : minicpVars)
				sol.append(x.min()).append(" ");
			sol.append("\n\t</values>\n</instantiation>");
			onSolution.accept(sol.toString(), realObjective.map(IntVar::min).orElse(Integer.MAX_VALUE));
		});

		return search.solve(shouldStop::apply);
	}

	private String solutionStr = null;
	private boolean extractSolutionStr = false;
	private boolean foundSolution = false;

	private static boolean checkSolution = false;

	public void checkSolution(boolean checkSolution) {
		XCSP.checkSolution = checkSolution;
	}

	private static boolean traceBP = false;

	public void traceBP(boolean traceBP) {
		XCSP.traceBP = traceBP;
		Log.setTraceBP(traceBP);
	}

	private static boolean traceSearch = false;

	public void traceSearch(boolean traceSearch) {
		XCSP.traceSearch = traceSearch;
		Log.setTraceSearch(traceSearch);
	}

	private static boolean traceEntropy = false;

	public void traceEntropy(boolean traceEntropy) {
		XCSP.traceEntropy = traceEntropy;
	}

	private static int maxIter = 5;

	public void maxIter(int maxIter) {
		XCSP.maxIter = maxIter;
	}

	private static boolean damp = false;

	/**
	 * INTENTIONALLY INERT (BP_COST_PROFILE.md 1.4 / Part 4 item 4). Damping is
	 * decided by {@code MiniCP.BPtuneDamping} at the root, and whatever factor
	 * it lands on stays in force for the whole search; this setter was never
	 * forwarded to the solver ({@code minicp.setDamp} call deliberately absent
	 * from {@code solve()}). Kept so existing harness callers compile, and
	 * documented so nobody believes {@code damp(false)} disables damping.
	 * Forwarding it would change every measured arm's behaviour and therefore
	 * requires a registered protocol and new campaigns, not a reconnect.
	 */
	public void damp(boolean damp) {
		XCSP.damp = damp;
	}

	private static double dampingFactor = 0.5;

	/** INTENTIONALLY INERT — see {@link #damp(boolean)}. */
	public void dampingFactor(double dampingFactor) {
		XCSP.dampingFactor = dampingFactor;
	}

	private static boolean restart = false;
	
	public void restart(boolean restart) {
		XCSP.restart = restart;
	}

	private static int nbFailCutof = 100;

	public void nbFailCutof(int nbFailCutof) {
		XCSP.nbFailCutof = nbFailCutof;
	} 

	private static double restartFactor = 1.5;

	public void restartFactor(double restartFactor) {
		XCSP.restartFactor = restartFactor;
	}

	private static double variationThreshold = -Double.MAX_VALUE;

	public void variationThreshold(double variationThreshold) {
		XCSP.variationThreshold = variationThreshold;
	}

	private static TreeSearchType searchType = TreeSearchType.DFS;

	public void searchType(TreeSearchType searchType) {
		XCSP.searchType = searchType;
	}

	private static boolean initImpact = false;

	public void initImpact(boolean initImpact) {
		XCSP.initImpact = initImpact;
	}

	private static boolean dynamicStopBP = false;

	public void dynamicStopBP(boolean dynamicStopBP) {
		XCSP.dynamicStopBP = dynamicStopBP;
	}

	private static boolean traceNbIter = false;

	public void traceNbIter(boolean traceNbIter) {
		XCSP.traceNbIter = traceNbIter;
	}
	
	private static boolean competitionOutput = false;

	public void competitionOutput(boolean competitionOutput) {
		XCSP.competitionOutput = competitionOutput;
	}

	private Search makeSearch(Supplier<Procedure[]> branching) {
		Search search = null;
		switch (searchType) {
		case DFS:
			search = makeDfs(minicp, branching);
			break;
		case LDS:
			search = makeLds(minicp, branching);
			break;
		default:
			Log.info("unknown search type");
			System.exit(1);
		}
		return search;
	}

	public void solve(BranchingHeuristic heuristic, int timeout, String statsFileStr, String solFileStr) {

		// GP: this is the solve we use
		Long t0 = System.currentTimeMillis();

		minicp.setTraceBPFlag(traceBP);
		minicp.setTraceSearchFlag(traceSearch);
//		minicp.setTraceNbIterFlag(traceNbIter);
		minicp.setTraceEntropyFlag(traceEntropy);
		minicp.setMaxIter(maxIter);
//		minicp.setDynamicStopBP(dynamicStopBP);
		// setDamp / setDampingFactor deliberately NOT forwarded: damping is
		// decided by BPtuneDamping at the root. See damp(boolean) above.
//		minicp.setVariationThreshold(variationThreshold);

		if (hasFailed) {
			if (!competitionOutput) {
				Log.info("problem failed before initiating the search");
				throw InconsistencyException.INCONSISTENCY;
			} else {
				Log.info("s UNSATISFIABLE");
				Log.info("c problem failed before initiating the search");
				return;
			}
		}

		/*
		Stream<IntVar> nonDecisionVars = mapVar.entrySet().stream().sorted(new EntryComparator())
				.map(Map.Entry::getValue).filter(v -> !decisionVars.contains(v));
		IntVar[] vars = Stream.concat(decisionVars.stream(),
		 nonDecisionVars).toArray(IntVar[]::new);
		*/

		/* */
		// 2026-08-16: mapVar is a HashMap keyed by XVarInteger, whose hashCode is
		// the identity hash, so the branching order used to change from one JVM to
		// the next and node counts were not reproducible. Sorting by variable id
		// makes a run depend only on the model and the heuristic, which is what a
		// paired comparison of counting routines needs. See IMPLEMENTATION_LOG.md.
		IntVar[] vars = mapVar.entrySet().stream().sorted(new EntryComparator())
				.map(Map.Entry::getValue).toArray(IntVar[]::new);
		/* */

		// 2026-08-18: a constraint-free instance (seen from a pycsp3
		// mis-compilation that emits variables but no constraints) leaves
		// mapVar empty and used to crash the branching heuristics with an
		// ArrayIndexOutOfBoundsException; fail with a diagnosis instead.
		if (vars.length == 0) {
			System.out.println("status: UNSUPPORTED");
			Log.info("c no branching variables (constraint-free or unparsed instance); not searching");
			return;
		}

		/*
		// GP for branching, use all vars registered in solver, not only those appearing in the model
		IntVar[] vars = new IntVar[minicp.getVariables().size()];
		for (int i = 0; i < minicp.getVariables().size(); i++) {
			vars[i] = minicp.getVariables().get(i);
		}
		*/

		Search search = null;
		switch (heuristic) {
		case FFRV:
			minicp.setMode(PropaMode.SP);
			search = makeSearch(firstFailRandomVal(vars));
			break;
		case MXMS:
			search = makeSearch(maxMarginalStrength(vars));
			break;
		case MXM:
			search = makeSearch(maxMarginal(vars));
			break;
		case MNMS:
			search = makeSearch(minMarginalStrength(vars));
			break;
		case MNM:
			search = makeSearch(minMarginal(vars));
			break;
		case MNE:
			search = makeSearch(minEntropy(vars));
			break;
		case MNERTB:
			// probe N (BP_PROBE_PROTOCOL amendments 10/10a): min entropy with a
			// uniform random tie-break among variables whose entropy agrees to
			// 2 decimals. Seed it with -Dminicpbp.seed; absent that property the
			// solver RNG is new Random() and the run is unreproducible.
			// NOTE: minEntropyRandomTieBreak does not call setBranchingOrder, so
			// stopRule=decision would sample the deterministic heuristic's
			// decision. Ship-stop arms only until that is fixed.
			search = makeSearch(minEntropyRandomTieBreak(vars));
			break;
		case IE:
			search = makeSearch(impactEntropy(vars));
			if(XCSP.initImpact)
				search.initializeImpact(vars);
			break;
		case IBS:
			minicp.setMode(PropaMode.SP);
			search = makeSearch(impactBasedSearch(vars));
			search.initializeImpactDomains(vars);
			nbFailCutof = nbFailCutof*vars.length;
			break;
		case MIE:
			search = makeDfs(minicp, minEntropyRegisterImpact(vars),impactEntropy(vars));
			if(XCSP.initImpact)
				search.initializeImpact(vars);
			break;
		case MNEBW:
			search = makeSearch(minEntropyBiasedWheelSelectVal(vars));
			break;
		case WDEG:
			minicp.setMode(PropaMode.SP);
			search = makeSearch(domWdeg(vars));
			nbFailCutof = nbFailCutof*vars.length;
			break;
		case WDEGMXM:
			// probe O (BP_PROBE_PROTOCOL amendment 11): dom/wdeg variable
			// selection with max-marginal value selection. BP mode is left ON
			// deliberately — BP's only job here is the branched variable's
			// marginal (value selection); variable selection needs no BP.
			// No nbFailCutof scaling: LDS/DFS only, no restart path uses it.
			search = makeSearch(domWdegMaxMarginalValue(vars));
			break;
		default:
			Log.info("unknown search strategy");
			System.exit(1);
		}

		if (checkSolution || (solFileStr != ""))
			extractSolutionStr = true;

		search.onSolution(() -> {
			foundSolution = true;
			if (extractSolutionStr) {
				StringBuilder sol = new StringBuilder("<instantiation>\n\t<list>\n\t\t");
				for (XVarInteger x : xVars)
					sol.append(x.id()).append(" ");
				sol.append("\n\t</list>\n\t<values>\n\t\t");
				for (IntVar x : minicpVars) {
					sol.append(x.min()).append(" ");
				}
				sol.append("\n\t</values>\n</instantiation>");
				solutionStr = sol.toString();
			}
			if(competitionOutput) {
				StringBuilder sol = new StringBuilder("v <instantiation>\nv <list> ");
				for (XVarInteger x : xVars)
					sol.append(x.id()).append(" ");
				sol.append("</list>\nv <values> ");
				for (IntVar x : minicpVars) {
					sol.append(x.min()).append(" ");
				}
				sol.append("</values>\nv </instantiation>");
				solutionStr = sol.toString();
			}
			// GP: printing each solution
//			Log.info("SOLN:"+solutionStr);
		});

		SearchStatistics stats;
		if(!restart) {
			stats = search.solve(ss -> {
				return (System.currentTimeMillis() - t0 >= timeout * 1000 || foundSolution);
				// GP; print all solns
//				return (System.currentTimeMillis() - t0 >= timeout * 1000);
			});
		}
		else {
			stats = search.solveRestarts(ss -> {
				return (System.currentTimeMillis() - t0 >= timeout * 1000 || foundSolution);
			}, nbFailCutof, restartFactor);
		}

		if(!competitionOutput) {
			if (foundSolution) {
				if(competitionOutput) {}
				Log.info("solution found");
				if (checkSolution)
					verifySolution();
				printSolution(solFileStr);
			} else
				Log.info("no solution was found");

			Long runtime = System.currentTimeMillis() - t0;
			// 2026-08-19 LDS amendment: report the exact discrepancy of the
			// solution path (-1 for DFS or when no solution was found).
			int solutionDiscrepancy = (search instanceof LDSearch)
					? ((LDSearch) search).solutionDiscrepancy() : -1;
			// 2026-08-19 run-length accounting (TODO.md item 3): naive LDS
			// re-expands the tree prefix each pass, so cumulative nodes are
			// not comparable to a DFS tree size. Per-pass deltas
			// (cap:nodes:failures:solutions) make total-expanded vs
			// final-pass work distinguishable downstream.
			String ldsPasses = (search instanceof LDSearch)
					? ((LDSearch) search).passSummary() : "";
			printStats(stats, statsFileStr, runtime, solutionDiscrepancy, ldsPasses);
		}
		else {
			if(foundSolution) {
				Log.info("s SATISFIABLE");
				Log.info(solutionStr);
				Log.info("c "+stats.numberOfFailures()+" backtracks");
			}
			else if(stats.isCompleted()) {
				Log.info("s UNSATISFIABLE");
				Log.info("c "+stats.numberOfFailures()+" backtracks");
			}
			else {
				Log.info("s UNKNOWN");
			}
		}

	}

	/**
	 * Amendment 10b (probe N phase 2): run an in-process dovetail over LDS
	 * passes on the parsed model, instead of one JVM per pass. The model is
	 * parsed and built once; {@link DovetailSearch} owns the pass schedule, the
	 * per-pass configuration and the completion rule.
	 *
	 * Only the two min-entropy heuristics are accepted: the arms of this
	 * experiment differ in BP configuration and in RNG seed, not in heuristic
	 * family, and the heuristics that also flip the propagation mode (FFRV,
	 * IBS, WDEG) have no meaning inside a BP dovetail.
	 */
	public DovetailSearch.Result solveDovetail(BranchingHeuristic heuristic,
											  java.util.List<DovetailSearch.Arm> arms,
											  int[] ladder, long globalBudgetMs,
											  long calibBudgetMs, int maxPasses,
											  String solFileStr) {
		if (heuristic != BranchingHeuristic.MNE && heuristic != BranchingHeuristic.MNERTB)
			throw new IllegalArgumentException("c solveDovetail supports min-entropy and "
					+ "min-entropy-rtb only, not " + heuristic);
		if (hasFailed) {
			DovetailSearch.Result r = new DovetailSearch.Result();
			r.status = "UNSAT";
			r.decidingLabel = "root";
			return r;
		}
		minicp.setTraceBPFlag(traceBP);
		minicp.setTraceSearchFlag(traceSearch);
		minicp.setTraceEntropyFlag(traceEntropy);

		IntVar[] vars = mapVar.entrySet().stream().sorted(new EntryComparator())
				.map(Map.Entry::getValue).toArray(IntVar[]::new);
		if (vars.length == 0) {
			DovetailSearch.Result r = new DovetailSearch.Result();
			r.status = "UNSUPPORTED";
			return r;
		}
		extractSolutionStr = true;

		// A fresh search AND a fresh branching per pass: minEntropyRandomTieBreak
		// captures the RNG and the branching flags at construction, so a reseed
		// between passes is only visible to a heuristic built after it.
		java.util.function.Supplier<LDSearch> factory = () -> {
			Supplier<Procedure[]> branching = (heuristic == BranchingHeuristic.MNERTB)
					? minEntropyRandomTieBreak(vars) : minEntropy(vars);
			LDSearch s = makeLds(minicp, branching);
			s.onSolution(() -> {
				foundSolution = true;
				StringBuilder sol = new StringBuilder("<instantiation>\n\t<list>\n\t\t");
				for (XVarInteger x : xVars)
					sol.append(x.id()).append(" ");
				sol.append("\n\t</list>\n\t<values>\n\t\t");
				for (IntVar x : minicpVars)
					sol.append(x.min()).append(" ");
				sol.append("\n\t</values>\n</instantiation>");
				solutionStr = sol.toString();
			});
			return s;
		};

		DovetailSearch.Result r = DovetailSearch.run(minicp, factory,
				() -> foundSolution, arms, ladder, globalBudgetMs, calibBudgetMs, maxPasses);
		if (foundSolution && solFileStr != null && !solFileStr.isEmpty())
			printSolution(solFileStr);
		return r;
	}

	private void verifySolution() {
		Log.info("verifying the solution (begin)");
		try {
			SolutionChecker checker = new SolutionChecker(false, fileName,
					new ByteArrayInputStream(solutionStr.getBytes()));
			if (checker.violatedCtrs.size() > 0)
				Log.info("INVALID SOLUTION");
			else
				Log.info("VALID SOLUTION");
		} catch (Exception e) {
			e.printStackTrace();
			Log.info("unable to verify the solution");
		}
		Log.info("verifying the solution (end)");
	}

	private void printSolution(String solFileStr) {
		if (solFileStr != "")
			try {
				PrintWriter out = new PrintWriter(new File(solFileStr));
				out.print(solutionStr);
				out.close();
			} catch (FileNotFoundException e) {
				e.printStackTrace();
				Log.info("unable to create file " + solFileStr);
				System.exit(1);
			}
	}

	private void printStats(SearchStatistics stats, String statsFileStr, Long runtime, int solutionDiscrepancy, String ldsPasses) {
		PrintStream out = null;
		if (statsFileStr == "")
			out = System.out;
		else
			try {
				out = new PrintStream(new File(statsFileStr));
			} catch (FileNotFoundException e) {
				e.printStackTrace();
				Log.info("unable to create file " + statsFileStr);
				System.exit(1);
			}

		String statusStr;
		if (foundSolution)
			statusStr = "SAT";
		else if (stats.isCompleted())
			statusStr = "UNSAT";
		else
			statusStr = "TIMEOUT";

		out.println("status: " + statusStr);
		out.println("failures: " + stats.numberOfFailures());
		out.println("nodes: " + stats.numberOfNodes());
		out.println("discrepancy: " + solutionDiscrepancy);
		if (!ldsPasses.isEmpty())
			out.println("ldsPasses: " + ldsPasses);
		out.println("runtime (ms): " + runtime);

		out.close();

	}

	public static void main(String[] args) {
		try {
			XCSP xcsp = new XCSP(args[0]);
			String solution = xcsp.solve(Integer.MAX_VALUE, 100);
			List<String> violatedCtrs = xcsp.getViolatedCtrs(solution);
			Log.info(violatedCtrs.toString());
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}
