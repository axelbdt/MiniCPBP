/*
 * mini-cpbp, replacing classic propagation by belief propagation
 * Copyright (c)  2019. by Gilles Pesant
 *
 * Structural compilation of XCSP intension expression trees, replacing the
 * blind bottom-up reification of ExprDecomposer on the flat path.
 *
 * Pipeline (inspired by Choco's discrete expression compilation and ACE's
 * primitive recognition):
 *
 *   XNode
 *     -> constant folding (safe, local: all-constant subtrees)
 *     -> linear extraction (add/sub/neg/mul-by-constant over variables and
 *        materialized non-linear atoms)
 *     -> direct posting on an existing MiniCPBP constraint or a plain
 *        domain operation
 *     -> generic recursive reification ONLY when a parent genuinely needs
 *        the truth value or the integer value of a subexpression
 *
 * Principles:
 *  - a Boolean auxiliary variable is created only when a parent expression
 *    consumes the truth value of a subexpression (compileBoolean); the root
 *    of a constraint is never reified;
 *  - arithmetic subexpressions are represented as linear forms
 *    (sum of coeff*var + constant) for as long as possible; views
 *    (mul/offset/opposite) are preferred to auxiliary variables, auxiliary
 *    variables to reified Booleans;
 *  - anything not recognized falls back to the same recursive decomposition
 *    ExprDecomposer performed, so every expression that compiled before
 *    still compiles, with identical semantics.
 *
 * The class is generic in the leaf variable type V (the XCSP parser uses
 * XVarInteger) so that the compiler can be unit-tested with stub leaves.
 */

package xcsp;

import minicpbp.engine.core.BoolVar;
import minicpbp.engine.core.IntVar;
import minicpbp.engine.core.Solver;
import minicpbp.util.exception.InconsistencyException;
import org.xcsp.common.IVar;
import org.xcsp.common.Types.TypeConditionOperatorRel;
import org.xcsp.common.Types.TypeExpr;
import org.xcsp.common.predicates.XNode;
import org.xcsp.common.predicates.XNodeLeaf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static minicpbp.cp.Factory.*;

public class IntensionCompiler<V extends IVar> {

	private final Solver cp;
	private final Function<V, IntVar> varMap;

	public IntensionCompiler(Solver cp, Function<V, IntVar> varMap) {
		this.cp = cp;
		this.varMap = varMap;
	}

	// ================================================================
	// linear form: sum of coeff*var + constant
	// ================================================================

	private static final class Linear {
		final LinkedHashMap<IntVar, Long> coeffs = new LinkedHashMap<>();
		long constant;

		void add(IntVar x, long c) {
			if (c == 0)
				return;
			Long cur = coeffs.get(x);
			long nc = (cur == null ? 0L : cur) + c;
			if (nc == 0)
				coeffs.remove(x);
			else
				coeffs.put(x, nc);
		}

		int size() {
			return coeffs.size();
		}
	}

	// ================================================================
	// public entry points
	// ================================================================

	/**
	 * Posts the expression tree as a hard constraint. Throws
	 * {@link InconsistencyException} when the constraint is detected
	 * infeasible at posting time.
	 */
	public void compileConstraint(XNode<V> t) {
		compile(t);
		// one fixpoint per top-level constraint, exactly like the previous
		// flat path (solver.post(c) intentionally defers propagation during
		// model loading)
		cp.fixPoint();
	}

	private void compile(XNode<V> t) {
		Long c = constValue(t);
		if (c != null) {
			if (c == 0)
				throw InconsistencyException.INCONSISTENCY;
			return; // trivially true
		}
		switch (t.type) {
			case VAR: { // Boolean variable used as a constraint
				IntVar x = var(t);
				x.assign(1);
				return;
			}
			case NOT:
				compileNegated(t.sons[0]);
				return;
			case EQ:
				if (t.arity() >= 2) {
					// n-ary eq: chain of binary equalities
					for (int i = 0; i + 1 < t.arity(); i++)
						postRelation(diff(t.sons[i], t.sons[i + 1]), TypeConditionOperatorRel.EQ);
					return;
				}
				break;
			case NE:
				if (t.arity() == 2) {
					postRelation(diff(t.sons[0], t.sons[1]), TypeConditionOperatorRel.NE);
					return;
				}
				if (t.arity() > 2) {
					// n-ary ne is pairwise distinctness (XCSP3); one
					// allDifferent factor, not the pairwise decomposition,
					// so BP sees the constraint whole
					cp.post(allDifferent(compileAll(t.sons)));
					return;
				}
				break;
			case LT:
			case LE:
			case GE:
			case GT:
				if (t.arity() == 2) {
					postRelation(diff(t.sons[0], t.sons[1]), t.type.toRelop());
					return;
				}
				break;
			case AND:
				for (XNode<V> son : t.sons)
					compile(son);
				return;
			case OR:
				compileOr(t.sons);
				return;
			case IMP: { // a -> b  ==  or(not a, b)
				postClause(new XNode[]{t.sons[0], t.sons[1]}, new boolean[]{true, false});
				return;
			}
			case IFF: { // pairwise equality of truth values
				BoolVar b0 = compileBoolean(t.sons[0]);
				for (int i = 1; i < t.arity(); i++)
					cp.post(equal(b0, compileBoolean(t.sons[i])));
				return;
			}
			case XOR: {
				if (t.arity() == 2) {
					cp.post(notEqual(compileBoolean(t.sons[0]), compileBoolean(t.sons[1])));
				} else { // odd parity
					IntVar[] bs = new IntVar[t.arity()];
					for (int i = 0; i < bs.length; i++)
						bs[i] = compileBoolean(t.sons[i]);
					cp.post(sumModP(bs, 1, 2));
				}
				return;
			}
			case IN: {
				long[] set = literalSet(t.sons[1]);
				if (set != null) {
					restrictToSet(t.sons[0], set, true);
					return;
				}
				break; // set with variables: generic fallback below
			}
			case NOTIN: {
				long[] set = literalSet(t.sons[1]);
				if (set != null) {
					restrictToSet(t.sons[0], set, false);
					return;
				}
				break;
			}
			default:
				break;
		}
		// generic fallback: reify and fix to true
		compileBoolean(t).assign(1);
	}

	/**
	 * Posts {@code not(t)} as a hard constraint.
	 */
	private void compileNegated(XNode<V> t) {
		Long c = constValue(t);
		if (c != null) {
			if (c != 0)
				throw InconsistencyException.INCONSISTENCY;
			return;
		}
		switch (t.type) {
			case VAR: {
				IntVar x = var(t);
				x.assign(0);
				return;
			}
			case NOT:
				compile(t.sons[0]);
				return;
			case EQ:
				if (t.arity() == 2) {
					postRelation(diff(t.sons[0], t.sons[1]), TypeConditionOperatorRel.NE);
					return;
				}
				break;
			case NE:
			case LT:
			case LE:
			case GE:
			case GT:
				if (t.arity() == 2) {
					postRelation(diff(t.sons[0], t.sons[1]), negate(t.type.toRelop()));
					return;
				}
				break;
			case OR: // De Morgan: not(or) = and(not)
				for (XNode<V> son : t.sons)
					compileNegated(son);
				return;
			case AND: { // not(and) = or(not)
				boolean[] neg = new boolean[t.arity()];
				java.util.Arrays.fill(neg, true);
				postClause(t.sons, neg);
				return;
			}
			case IMP: // not(a -> b) = a and not b
				compile(t.sons[0]);
				compileNegated(t.sons[1]);
				return;
			case IFF:
				if (t.arity() == 2) {
					cp.post(notEqual(compileBoolean(t.sons[0]), compileBoolean(t.sons[1])));
					return;
				}
				break;
			case XOR:
				if (t.arity() == 2) {
					cp.post(equal(compileBoolean(t.sons[0]), compileBoolean(t.sons[1])));
					return;
				}
				break;
			case IN: {
				long[] set = literalSet(t.sons[1]);
				if (set != null) {
					restrictToSet(t.sons[0], set, false);
					return;
				}
				break;
			}
			case NOTIN: {
				long[] set = literalSet(t.sons[1]);
				if (set != null) {
					restrictToSet(t.sons[0], set, true);
					return;
				}
				break;
			}
			default:
				break;
		}
		compileBoolean(t).assign(0);
	}

	/**
	 * Returns a 0/1 variable equal to the truth value of the expression.
	 * Used only when a parent expression consumes that truth value.
	 */
	public BoolVar compileBoolean(XNode<V> t) {
		Long c = constValue(t);
		if (c != null)
			return boolConst(c != 0);
		switch (t.type) {
			case VAR: {
				IntVar x = var(t);
				if (x instanceof BoolVar)
					return (BoolVar) x;
				x.removeBelow(0);
				x.removeAbove(1);
				return isEqual(x, 1);
			}
			case NOT: {
				XNode<V> son = t.sons[0];
				if (son.type == TypeExpr.NOT) // not(not(A)) -> A
					return compileBoolean(son.sons[0]);
				return not(compileBoolean(son));
			}
			case EQ:
				if (t.arity() == 2)
					return reifyRelation(diff(t.sons[0], t.sons[1]), TypeConditionOperatorRel.EQ);
				if (t.arity() > 2) {
					// n-ary eq (all sons equal): the conjunction of the same
					// chain of binary equalities compile() posts hard
					List<BoolVar> chain = new ArrayList<>();
					for (int i = 0; i + 1 < t.arity(); i++)
						chain.add(reifyRelation(diff(t.sons[i], t.sons[i + 1]),
								TypeConditionOperatorRel.EQ));
					return allOf(chain);
				}
				break;
			case NE:
				if (t.arity() == 2)
					return reifyRelation(diff(t.sons[0], t.sons[1]), TypeConditionOperatorRel.NE);
				if (t.arity() > 2) {
					// n-ary ne (pairwise distinct): reified pair by pair, since
					// allDifferent has no reified form here
					List<BoolVar> pairs = new ArrayList<>();
					for (int i = 0; i < t.arity(); i++)
						for (int j = i + 1; j < t.arity(); j++)
							pairs.add(reifyRelation(diff(t.sons[i], t.sons[j]),
									TypeConditionOperatorRel.NE));
					return allOf(pairs);
				}
				break;
			case LT:
			case LE:
			case GE:
			case GT:
				if (t.arity() == 2)
					return reifyRelation(diff(t.sons[0], t.sons[1]), t.type.toRelop());
				break;
			case AND: {
				List<BoolVar> bs = new ArrayList<>();
				for (XNode<V> son : t.sons) {
					Long sc = constValue(son);
					if (sc != null) {
						if (sc == 0)
							return boolConst(false); // and(...,false) -> false
						continue; // and(A,true) -> A
					}
					bs.add(compileBoolean(son));
				}
				return allOf(bs);
			}
			case OR: {
				List<BoolVar> bs = new ArrayList<>();
				for (XNode<V> son : t.sons) {
					Long sc = constValue(son);
					if (sc != null) {
						if (sc != 0)
							return boolConst(true); // or(...,true) -> true
						continue; // or(A,false) -> A
					}
					bs.add(compileBoolean(son));
				}
				if (bs.isEmpty())
					return boolConst(false);
				if (bs.size() == 1)
					return bs.get(0);
				return isOr(bs.toArray(new BoolVar[0]));
			}
			case IMP: // a -> b == or(not a, b)
				return isOr(new BoolVar[]{not(compileBoolean(t.sons[0])), compileBoolean(t.sons[1])});
			case IFF: {
				if (t.arity() == 2)
					return isEqual((IntVar) compileBoolean(t.sons[0]), (IntVar) compileBoolean(t.sons[1]));
				// n-ary iff (all truth values equal): the conjunction of the
				// same chain against son 0 that compile() posts hard
				BoolVar b0 = compileBoolean(t.sons[0]);
				List<BoolVar> chain = new ArrayList<>();
				for (int i = 1; i < t.arity(); i++)
					chain.add(isEqual((IntVar) b0, (IntVar) compileBoolean(t.sons[i])));
				return allOf(chain);
			}
			case XOR: {
				if (t.arity() == 2)
					return isNotEqual((IntVar) compileBoolean(t.sons[0]), (IntVar) compileBoolean(t.sons[1]));
				IntVar[] bs = new IntVar[t.arity()];
				for (int i = 0; i < bs.length; i++)
					bs[i] = compileBoolean(t.sons[i]);
				return isEqual(sumModP(bs, 2), 1);
			}
			case IN: {
				long[] set = literalSet(t.sons[1]);
				if (set != null)
					return reifyMembership(t.sons[0], set);
				// set with variable members: or of equalities
				List<BoolVar> bs = new ArrayList<>();
				for (XNode<V> m : t.sons[1].sons)
					bs.add(reifyRelation(diff(t.sons[0], m), TypeConditionOperatorRel.EQ));
				return bs.size() == 1 ? bs.get(0) : isOr(bs.toArray(new BoolVar[0]));
			}
			case NOTIN: {
				long[] set = literalSet(t.sons[1]);
				if (set != null)
					return not(reifyMembership(t.sons[0], set));
				List<BoolVar> bs = new ArrayList<>();
				for (XNode<V> m : t.sons[1].sons)
					bs.add(reifyRelation(diff(t.sons[0], m), TypeConditionOperatorRel.EQ));
				return not(bs.size() == 1 ? bs.get(0) : isOr(bs.toArray(new BoolVar[0])));
			}
			default:
				break;
		}
		// A predicate node must never reach the arithmetic fallback:
		// materializeNonLinear() sends every predicate type back here, so a
		// fall-through with one recurs compileBoolean -> compileArithmetic ->
		// materializeNonLinear -> compileBoolean until the stack overflows.
		// n-ary eq did exactly that (RotatingRostering-008-2-3, and
		// imp(ne(x,y),eq(x,y,x)) as a two-variable reproduction); it is handled
		// above now, and anything still unsupported must say so.
		if (isPredicate(t.type))
			throw new IllegalArgumentException("unsupported predicate node in Boolean position: "
					+ t.type + " of arity " + t.arity());
		// arithmetic expression used in a Boolean position: constrain to 0/1
		IntVar s = compileArithmetic(t);
		if (s instanceof BoolVar)
			return (BoolVar) s;
		s.removeBelow(0);
		s.removeAbove(1);
		return isEqual(s, 1);
	}

	/** the sons as integer variables, in order */
	private IntVar[] compileAll(XNode<V>[] sons) {
		IntVar[] xs = new IntVar[sons.length];
		for (int i = 0; i < sons.length; i++)
			xs[i] = compileArithmetic(sons[i]);
		return xs;
	}

	/** conjunction of reified Booleans; negations are views, so one isOr */
	private BoolVar allOf(List<BoolVar> bs) {
		if (bs.isEmpty())
			return boolConst(true);
		if (bs.size() == 1)
			return bs.get(0);
		// and(b1..bn) = not(or(not b1.. not bn))
		BoolVar[] negs = new BoolVar[bs.size()];
		for (int i = 0; i < negs.length; i++)
			negs[i] = not(bs.get(i));
		return not(isOr(negs));
	}

	/**
	 * The node types materializeNonLinear() routes back to compileBoolean().
	 * Keep the two lists in step: a type that is predicate here and absent
	 * there (or the reverse) reopens the recursion the guard above closes.
	 */
	private static boolean isPredicate(TypeExpr type) {
		switch (type) {
			case EQ:
			case NE:
			case LT:
			case LE:
			case GE:
			case GT:
			case NOT:
			case AND:
			case OR:
			case XOR:
			case IFF:
			case IMP:
			case IN:
			case NOTIN:
				return true;
			default:
				return false;
		}
	}

	/**
	 * Returns a variable equal to the value of the expression. Views and
	 * linear recognition are preferred to auxiliary variables.
	 */
	public IntVar compileArithmetic(XNode<V> t) {
		Long c = constValue(t);
		if (c != null)
			return makeIntVar(cp, toInt(c), toInt(c));
		switch (t.type) {
			case VAR:
				return var(t);
			case ADD:
			case SUB:
			case NEG: {
				Linear f = new Linear();
				linear(t, 1, f);
				return materializeLinear(f);
			}
			case MUL: { // may be linear (single non-constant factor)
				Linear f = new Linear();
				linear(t, 1, f);
				return materializeLinear(f);
			}
			default:
				return materializeNonLinear(t);
		}
	}

	/**
	 * Posts {@code sum_i cs[i]*xs[i] op rhs} directly, without auxiliary
	 * reification (public so that the XCSP primitive callbacks can reuse the
	 * linear machinery).
	 */
	public void postLinearRel(int[] cs, IntVar[] xs, TypeConditionOperatorRel op, long rhs) {
		Linear f = new Linear();
		for (int i = 0; i < xs.length; i++)
			f.add(xs[i], cs[i]);
		f.constant = -rhs;
		postRelation(f, op);
		cp.fixPoint();
	}

	// ================================================================
	// linear extraction
	// ================================================================

	/** accumulates mult * t into acc; non-linear subterms become atoms */
	private void linear(XNode<V> t, long mult, Linear acc) {
		if (mult == 0)
			return;
		Long c = constValue(t);
		if (c != null) {
			acc.constant += mult * c;
			return;
		}
		switch (t.type) {
			case VAR:
				acc.add(var(t), mult);
				return;
			case ADD:
				for (XNode<V> son : t.sons)
					linear(son, mult, acc);
				return;
			case SUB:
				linear(t.sons[0], mult, acc);
				linear(t.sons[1], -mult, acc);
				return;
			case NEG:
				linear(t.sons[0], -mult, acc);
				return;
			case MUL: {
				long cst = 1;
				List<XNode<V>> vars = new ArrayList<>();
				for (XNode<V> son : t.sons) {
					Long sc = constValue(son);
					if (sc != null)
						cst *= sc;
					else
						vars.add(son);
				}
				if (cst == 0)
					return; // mul(A,0) -> 0
				if (vars.isEmpty()) {
					acc.constant += mult * cst;
					return;
				}
				if (vars.size() == 1) { // mul by constant stays linear
					linear(vars.get(0), mult * cst, acc);
					return;
				}
				acc.add(materializeProduct(vars), mult * cst);
				return;
			}
			default:
				// non-linear atom: materialize once, keep its coefficient
				acc.add(compileArithmetic(t), mult);
		}
	}

	/** linear form of sons[0] - sons[1] */
	private Linear diff(XNode<V> a, XNode<V> b) {
		Linear f = new Linear();
		linear(a, 1, f);
		linear(b, -1, f);
		return f;
	}

	// ================================================================
	// posting a linear relation:  sum_i c_i x_i + k  op  0
	// ================================================================

	private void postRelation(Linear f, TypeConditionOperatorRel op) {
		int n = f.size();
		long target = -f.constant; // sum c_i x_i op target
		if (n == 0) {
			if (!holds(0, op, target))
				throw InconsistencyException.INCONSISTENCY;
			return;
		}
		if (n == 1) {
			Map.Entry<IntVar, Long> e = f.coeffs.entrySet().iterator().next();
			domainRestrict(mul(e.getKey(), toInt(e.getValue())), op, target);
			return;
		}
		// normalize LT/GT to LE/GE, then divide by the gcd of the coefficients
		if (op == TypeConditionOperatorRel.LT) {
			op = TypeConditionOperatorRel.LE;
			target--;
		} else if (op == TypeConditionOperatorRel.GT) {
			op = TypeConditionOperatorRel.GE;
			target++;
		}
		long g = 0;
		for (long c : f.coeffs.values())
			g = gcd(g, Math.abs(c));
		if (g > 1) {
			if (target % g != 0) {
				if (op == TypeConditionOperatorRel.EQ)
					throw InconsistencyException.INCONSISTENCY; // gcd does not divide target
				if (op == TypeConditionOperatorRel.NE)
					return; // trivially true
			}
			long t2 = op == TypeConditionOperatorRel.LE ? Math.floorDiv(target, g)
					: op == TypeConditionOperatorRel.GE ? -Math.floorDiv(-target, g)
					: target / g;
			Linear f2 = new Linear();
			for (Map.Entry<IntVar, Long> e : f.coeffs.entrySet())
				f2.add(e.getKey(), e.getValue() / g);
			f = f2;
			target = t2;
		}
		IntVar[] xs = new IntVar[f.size()];
		int[] cs = new int[f.size()];
		unpack(f, xs, cs);
		// binary difference: use the dedicated binary constraints
		if (n == 2 && ((cs[0] == 1 && cs[1] == -1) || (cs[0] == -1 && cs[1] == 1))) {
			IntVar x = cs[0] == 1 ? xs[0] : xs[1];
			IntVar y = cs[0] == 1 ? xs[1] : xs[0];
			// x - y op target  <=>  x op y + target
			IntVar yt = target == 0 ? y : plus(y, toInt(target));
			switch (op) {
				case EQ:
					cp.post(equal(x, yt));
					return;
				case NE:
					cp.post(notEqual(x, y, toInt(target)));
					return;
				case LE:
					cp.post(lessOrEqual(x, yt));
					return;
				case GE:
					cp.post(lessOrEqual(yt, x));
					return;
				default:
					throw new IllegalStateException();
			}
		}
		long lo = 0, hi = 0;
		for (int i = 0; i < xs.length; i++) {
			lo += (long) cs[i] * (cs[i] >= 0 ? xs[i].min() : xs[i].max());
			hi += (long) cs[i] * (cs[i] >= 0 ? xs[i].max() : xs[i].min());
		}
		switch (op) {
			case EQ:
				if (target < lo || target > hi)
					throw InconsistencyException.INCONSISTENCY;
				cp.post(sum(cs, xs, toInt(target))); // one SumDC, no auxiliary
				return;
			case NE:
				if (target < lo || target > hi)
					return; // trivially true
				domainRestrict(sumVar(cs, xs), TypeConditionOperatorRel.NE, target);
				return;
			case LE:
				if (target >= hi)
					return; // trivially true
				if (target < lo)
					throw InconsistencyException.INCONSISTENCY;
				domainRestrict(sumVar(cs, xs), TypeConditionOperatorRel.LE, target);
				return;
			case GE:
				if (target <= lo)
					return;
				if (target > hi)
					throw InconsistencyException.INCONSISTENCY;
				domainRestrict(sumVar(cs, xs), TypeConditionOperatorRel.GE, target);
				return;
			default:
				throw new IllegalStateException();
		}
	}

	/** applies {@code w op target} as a domain operation, then propagates */
	private void domainRestrict(IntVar w, TypeConditionOperatorRel op, long target) {
		switch (op) {
			case EQ:
				if (target < w.min() || target > w.max())
					throw InconsistencyException.INCONSISTENCY;
				w.assign(toInt(target));
				break;
			case NE:
				if (target >= w.min() && target <= w.max())
					w.remove(toInt(target));
				break;
			case LE:
				if (target < w.min())
					throw InconsistencyException.INCONSISTENCY;
				if (target < w.max())
					w.removeAbove(toInt(target));
				break;
			case LT:
				domainRestrict(w, TypeConditionOperatorRel.LE, target - 1);
				return;
			case GE:
				if (target > w.max())
					throw InconsistencyException.INCONSISTENCY;
				if (target > w.min())
					w.removeBelow(toInt(target));
				break;
			case GT:
				domainRestrict(w, TypeConditionOperatorRel.GE, target + 1);
				return;
			default:
				throw new IllegalStateException();
		}
	}

	// ================================================================
	// reifying a linear relation:  b <-> (sum_i c_i x_i + k  op  0)
	// ================================================================

	private BoolVar reifyRelation(Linear f, TypeConditionOperatorRel op) {
		int n = f.size();
		long target = -f.constant;
		if (n == 0)
			return boolConst(holds(0, op, target));
		IntVar w;
		if (n == 1) {
			Map.Entry<IntVar, Long> e = f.coeffs.entrySet().iterator().next();
			w = mul(e.getKey(), toInt(e.getValue()));
		} else {
			IntVar[] xs = new IntVar[n];
			int[] cs = new int[n];
			unpack(f, xs, cs);
			// binary difference against a variable: reified var-var primitives
			if (n == 2 && ((cs[0] == 1 && cs[1] == -1) || (cs[0] == -1 && cs[1] == 1))) {
				IntVar x = cs[0] == 1 ? xs[0] : xs[1];
				IntVar y = cs[0] == 1 ? xs[1] : xs[0];
				IntVar yt = target == 0 ? y : plus(y, toInt(target));
				switch (op) {
					case EQ:
						return isEqual(x, yt);
					case NE:
						return isNotEqual(x, yt);
					case LE:
						return isLessOrEqual(x, yt);
					case LT:
						return isLess(x, yt);
					case GE:
						return isLargerOrEqual(x, yt);
					case GT:
						return isLarger(x, yt);
					default:
						throw new IllegalStateException();
				}
			}
			w = sumVar(cs, xs);
		}
		// w op target, constant right-hand side
		if (target < w.min() || target > w.max()) {
			switch (op) { // degenerate: constant truth value
				case EQ:
					return boolConst(false);
				case NE:
					return boolConst(true);
				case LE:
				case LT:
					return boolConst(target > w.max());
				case GE:
				case GT:
					return boolConst(target < w.min());
				default:
					throw new IllegalStateException();
			}
		}
		int k = toInt(target);
		switch (op) {
			case EQ:
				return isEqual(w, k);
			case NE:
				return isNotEqual(w, k);
			case LE:
				return isLessOrEqual(w, k);
			case LT:
				return isLess(w, k);
			case GE:
				return isLargerOrEqual(w, k);
			case GT:
				return isLarger(w, k);
			default:
				throw new IllegalStateException();
		}
	}

	// ================================================================
	// or / clauses / membership
	// ================================================================

	/**
	 * Posts a top-level disjunction. Recognizes the domain-restriction
	 * pattern first: every disjunct constrains the same single variable
	 * (or(eq(x,1),eq(x,2),...) and friends), in which case the whole
	 * disjunction is one domain operation and zero constraints.
	 */
	private void compileOr(XNode<V>[] sons) {
		List<XNode<V>> active = new ArrayList<>();
		for (XNode<V> son : sons) {
			Long c = constValue(son);
			if (c != null) {
				if (c != 0)
					return; // or(...,true): trivially satisfied
				continue; // or(A,false) -> A
			}
			active.add(son);
		}
		if (active.isEmpty())
			throw InconsistencyException.INCONSISTENCY;
		if (active.size() == 1) {
			compile(active.get(0));
			return;
		}
		if (tryDomainRestrictionOr(active))
			return;
		BoolVar[] lits = new BoolVar[active.size()];
		for (int i = 0; i < lits.length; i++) {
			XNode<V> son = active.get(i);
			lits[i] = son.type == TypeExpr.NOT ? not(compileBoolean(son.sons[0])) : compileBoolean(son);
		}
		cp.post(or(lits));
	}

	/** posts or over the given sons, son i negated when neg[i] */
	private void postClause(XNode<V>[] sons, boolean[] neg) {
		List<BoolVar> lits = new ArrayList<>();
		for (int i = 0; i < sons.length; i++) {
			Long c = constValue(sons[i]);
			if (c != null) {
				boolean val = (c != 0) ^ neg[i];
				if (val)
					return; // clause trivially satisfied
				continue;
			}
			BoolVar b = compileBoolean(sons[i]);
			lits.add(neg[i] ? not(b) : b);
		}
		if (lits.isEmpty())
			throw InconsistencyException.INCONSISTENCY;
		if (lits.size() == 1) {
			lits.get(0).assign(1);
			return;
		}
		cp.post(or(lits.toArray(new BoolVar[0])));
	}

	/**
	 * or(eq(x,a1), eq(x,a2), ..., in(x,{...})) over one common variable:
	 * remove every other value of x. Returns false when the pattern does
	 * not apply.
	 */
	private boolean tryDomainRestrictionOr(List<XNode<V>> sons) {
		// per son: the common variable's coefficient c and the target values
		// T such that the son holds iff c*v is one of T (constant part folded)
		IntVar common = null;
		long[] coefs = new long[sons.size()];
		long[][] targets = new long[sons.size()][];
		for (int i = 0; i < sons.size(); i++) {
			XNode<V> son = sons.get(i);
			Linear f = new Linear();
			long[] set;
			if (son.type == TypeExpr.EQ && son.arity() == 2) {
				// purely structural probe: no materialization side effects
				if (!linearNoAtoms(son.sons[0], 1, f) || !linearNoAtoms(son.sons[1], -1, f))
					return false;
				set = new long[]{0};
			} else if (son.type == TypeExpr.IN) {
				set = literalSet(son.sons[1]);
				if (set == null || !linearNoAtoms(son.sons[0], 1, f))
					return false;
			} else {
				return false;
			}
			if (f.size() != 1)
				return false;
			Map.Entry<IntVar, Long> e = f.coeffs.entrySet().iterator().next();
			if (common == null)
				common = e.getKey();
			else if (common != e.getKey())
				return false;
			coefs[i] = e.getValue();
			targets[i] = new long[set.length];
			for (int j = 0; j < set.length; j++)
				targets[i][j] = set[j] - f.constant; // c*v + k in S  <=>  c*v in S-k
		}
		// union of the values of the common variable supported by some son
		int[] dom = new int[common.size()];
		int s = common.fillArray(dom);
		boolean changed = false;
		for (int j = 0; j < s; j++) {
			long v = dom[j];
			boolean supported = false;
			outer:
			for (int i = 0; i < sons.size(); i++)
				for (long m : targets[i])
					if (coefs[i] * v == m) {
						supported = true;
						break outer;
					}
			if (!supported) {
				common.remove(dom[j]);
				changed = true;
			}
		}
		return true;
	}

	/**
	 * Linear extraction that fails (returns false) instead of materializing
	 * non-linear atoms; used by pattern recognizers that must not post
	 * anything when the pattern does not apply.
	 */
	private boolean linearNoAtoms(XNode<V> t, long mult, Linear acc) {
		Long c = constValue(t);
		if (c != null) {
			acc.constant += mult * c;
			return true;
		}
		switch (t.type) {
			case VAR:
				acc.add(var(t), mult);
				return true;
			case ADD:
				for (XNode<V> son : t.sons)
					if (!linearNoAtoms(son, mult, acc))
						return false;
				return true;
			case SUB:
				return linearNoAtoms(t.sons[0], mult, acc) && linearNoAtoms(t.sons[1], -mult, acc);
			case NEG:
				return linearNoAtoms(t.sons[0], -mult, acc);
			case MUL: {
				long cst = 1;
				XNode<V> varSon = null;
				for (XNode<V> son : t.sons) {
					Long sc = constValue(son);
					if (sc != null)
						cst *= sc;
					else if (varSon == null)
						varSon = son;
					else
						return false;
				}
				if (cst == 0)
					return true; // contributes 0
				if (varSon == null) {
					acc.constant += mult * cst;
					return true;
				}
				return linearNoAtoms(varSon, mult * cst, acc);
			}
			default:
				return false;
		}
	}

	/** restricts expr to (or away from) a literal set: pure domain operation */
	private void restrictToSet(XNode<V> lhs, long[] set, boolean keep) {
		Linear f = new Linear();
		linear(lhs, 1, f);
		IntVar w = materializeLinear(f);
		int[] buf = domainBuf(w);
		int s = w.fillArray(buf);
		boolean changed = false;
		for (int j = 0; j < s; j++) {
			boolean in = false;
			for (long m : set)
				if (buf[j] == m) {
					in = true;
					break;
				}
			if (in != keep) {
				w.remove(buf[j]);
				changed = true;
			}
		}
	}

	/** reified membership of an expression in a literal set */
	private BoolVar reifyMembership(XNode<V> lhs, long[] set) {
		Linear f = new Linear();
		linear(lhs, 1, f);
		IntVar w = materializeLinear(f);
		List<BoolVar> bs = new ArrayList<>();
		for (long m : set)
			if (m >= w.min() && m <= w.max())
				bs.add(isEqual(w, toInt(m)));
		if (bs.isEmpty())
			return boolConst(false);
		if (bs.size() == 1)
			return bs.get(0);
		return isOr(bs.toArray(new BoolVar[0]));
	}

	// ================================================================
	// materialization (the fallback, never the first operation)
	// ================================================================

	private IntVar materializeLinear(Linear f) {
		int n = f.size();
		if (n == 0)
			return makeIntVar(cp, toInt(f.constant), toInt(f.constant));
		if (n == 1) { // views only, no constraint
			Map.Entry<IntVar, Long> e = f.coeffs.entrySet().iterator().next();
			IntVar w = mul(e.getKey(), toInt(e.getValue()));
			return f.constant == 0 ? w : plus(w, toInt(f.constant));
		}
		IntVar[] xs = new IntVar[n];
		int[] cs = new int[n];
		unpack(f, xs, cs);
		IntVar s = sumVar(cs, xs);
		return f.constant == 0 ? s : plus(s, toInt(f.constant));
	}

	private IntVar materializeNonLinear(XNode<V> t) {
		switch (t.type) {
			case ABS: {
				IntVar a = compileArithmetic(t.sons[0]);
				return absOf(a);
			}
			case DIST: { // |a - b|
				IntVar a = materializeLinear(diff(t.sons[0], t.sons[1]));
				return absOf(a);
			}
			case SQR: {
				IntVar a = compileArithmetic(t.sons[0]);
				return product(a, a);
			}
			case MUL: {
				List<XNode<V>> vars = new ArrayList<>();
				long cst = 1;
				for (XNode<V> son : t.sons) {
					Long sc = constValue(son);
					if (sc != null)
						cst *= sc;
					else
						vars.add(son);
				}
				if (cst == 0)
					return makeIntVar(cp, 0, 0);
				IntVar p = materializeProduct(vars);
				return cst == 1 ? p : mul(p, toInt(cst));
			}
			case DIV: {
				IntVar a = compileArithmetic(t.sons[0]);
				IntVar b = compileArithmetic(t.sons[1]);
				return quotient(a, b);
			}
			case MOD: {
				IntVar a = compileArithmetic(t.sons[0]);
				IntVar b = compileArithmetic(t.sons[1]);
				return modulo(a, b);
			}
			case POW: {
				IntVar a = compileArithmetic(t.sons[0]);
				IntVar b = compileArithmetic(t.sons[1]);
				return pow(a, b);
			}
			case MIN: {
				IntVar[] xs = new IntVar[t.arity()];
				for (int i = 0; i < xs.length; i++)
					xs[i] = compileArithmetic(t.sons[i]);
				return minimum(xs);
			}
			case MAX: {
				IntVar[] xs = new IntVar[t.arity()];
				for (int i = 0; i < xs.length; i++)
					xs[i] = compileArithmetic(t.sons[i]);
				return maximum(xs);
			}
			case IF: { // if(c,a,b)
				BoolVar b = compileBoolean(t.sons[0]);
				IntVar va = compileArithmetic(t.sons[1]);
				IntVar vb = compileArithmetic(t.sons[2]);
				IntVar r = makeIntVar(cp, Math.min(va.min(), vb.min()), Math.max(va.max(), vb.max()));
				cp.post(or(new BoolVar[]{not(b), isEqual(r, va)}));
				cp.post(or(new BoolVar[]{b, isEqual(r, vb)}));
				return r;
			}
			case EQ:
			case NE:
			case LT:
			case LE:
			case GE:
			case GT:
			case NOT:
			case AND:
			case OR:
			case XOR:
			case IFF:
			case IMP:
			case IN:
			case NOTIN:
				// predicate used as a 0/1 integer
				return compileBoolean(t);
			default:
				throw new IllegalArgumentException("unsupported expression node " + t.type);
		}
	}

	/** product of two or more non-constant factors */
	private IntVar materializeProduct(List<XNode<V>> factors) {
		IntVar cur = compileArithmetic(factors.get(0));
		for (int i = 1; i < factors.size(); i++)
			cur = product(cur, compileArithmetic(factors.get(i)));
		return cur;
	}

	private IntVar absOf(IntVar a) {
		if (a.min() >= 0)
			return a;
		if (a.max() <= 0)
			return minus(a);
		return abs(a);
	}

	// ================================================================
	// helpers
	// ================================================================

	@SuppressWarnings("unchecked")
	private IntVar var(XNode<V> leaf) {
		IntVar x = varMap.apply(leaf.var(0));
		if (x == null)
			throw new IllegalArgumentException("unmapped variable " + leaf.var(0));
		return x;
	}

	private IntVar sumVar(int[] cs, IntVar[] xs) {
		boolean allOnes = true;
		for (int c : cs)
			if (c != 1) {
				allOnes = false;
				break;
			}
		return allOnes ? sum(xs) : sum(cs, xs);
	}

	private static void unpack(Linear f, IntVar[] xs, int[] cs) {
		int i = 0;
		for (Map.Entry<IntVar, Long> e : f.coeffs.entrySet()) {
			xs[i] = e.getKey();
			cs[i] = toInt(e.getValue());
			i++;
		}
	}

	private BoolVar boolConst(boolean v) {
		BoolVar b = makeBoolVar(cp);
		b.assign(v ? 1 : 0);
		return b;
	}

	private static boolean holds(long lhs, TypeConditionOperatorRel op, long rhs) {
		switch (op) {
			case EQ:
				return lhs == rhs;
			case NE:
				return lhs != rhs;
			case LT:
				return lhs < rhs;
			case LE:
				return lhs <= rhs;
			case GE:
				return lhs >= rhs;
			case GT:
				return lhs > rhs;
			default:
				throw new IllegalStateException();
		}
	}

	private static TypeConditionOperatorRel negate(TypeConditionOperatorRel op) {
		switch (op) {
			case EQ:
				return TypeConditionOperatorRel.NE;
			case NE:
				return TypeConditionOperatorRel.EQ;
			case LT:
				return TypeConditionOperatorRel.GE;
			case LE:
				return TypeConditionOperatorRel.GT;
			case GE:
				return TypeConditionOperatorRel.LT;
			case GT:
				return TypeConditionOperatorRel.LE;
			default:
				throw new IllegalStateException();
		}
	}

	private static long gcd(long a, long b) {
		while (b != 0) {
			long t = a % b;
			a = b;
			b = t;
		}
		return a;
	}

	private static int toInt(long v) {
		if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE)
			throw new IllegalArgumentException("integer overflow in intension expression: " + v);
		return (int) v;
	}

	private int[] buf = new int[0];

	private int[] domainBuf(IntVar x) {
		if (buf.length < x.size())
			buf = new int[Math.max(x.size(), 64)];
		return buf;
	}

	/** values of a literal set node, or null when the set contains variables */
	private long[] literalSet(XNode<V> setNode) {
		if (setNode.type != TypeExpr.SET)
			return null;
		long[] vals = new long[setNode.arity()];
		for (int i = 0; i < vals.length; i++) {
			Long c = constValue(setNode.sons[i]);
			if (c == null)
				return null;
			vals[i] = c;
		}
		return vals;
	}

	// ================================================================
	// constant folding
	// ================================================================

	/**
	 * Value of an all-constant subtree, or null when the subtree contains a
	 * variable or an operator this folder does not evaluate. Folding is
	 * exact: any arithmetic overflow or undefined operation aborts folding.
	 */
	private Long constValue(XNode<V> t) {
		try {
			return constValueExact(t);
		} catch (ArithmeticException e) {
			return null;
		}
	}

	private Long constValueExact(XNode<V> t) {
		if (t.type == TypeExpr.LONG)
			return ((Number) ((XNodeLeaf<V>) t).value).longValue();
		if (t.arity() == 0)
			return null; // VAR, PAR, SYMBOL, ...
		long[] v = new long[t.arity()];
		for (int i = 0; i < v.length; i++) {
			Long c = constValueExact(t.sons[i]);
			if (c == null)
				return null;
			v[i] = c;
		}
		switch (t.type) {
			case NEG:
				return -v[0];
			case ABS:
				return Math.abs(v[0]);
			case SQR:
				return Math.multiplyExact(v[0], v[0]);
			case ADD: {
				long s = 0;
				for (long x : v)
					s = Math.addExact(s, x);
				return s;
			}
			case SUB:
				return Math.subtractExact(v[0], v[1]);
			case MUL: {
				long p = 1;
				for (long x : v)
					p = Math.multiplyExact(p, x);
				return p;
			}
			case DIV:
				return v[0] / v[1]; // ArithmeticException on /0 aborts folding
			case MOD:
				return v[0] % v[1];
			case POW: {
				if (v[1] < 0)
					throw new ArithmeticException("negative exponent");
				long r = 1;
				for (long i = 0; i < v[1]; i++)
					r = Math.multiplyExact(r, v[0]);
				return r;
			}
			case DIST:
				return Math.abs(Math.subtractExact(v[0], v[1]));
			case MIN: {
				long m = v[0];
				for (long x : v)
					m = Math.min(m, x);
				return m;
			}
			case MAX: {
				long m = v[0];
				for (long x : v)
					m = Math.max(m, x);
				return m;
			}
			case LT:
				return v[0] < v[1] ? 1L : 0L;
			case LE:
				return v[0] <= v[1] ? 1L : 0L;
			case GE:
				return v[0] >= v[1] ? 1L : 0L;
			case GT:
				return v[0] > v[1] ? 1L : 0L;
			case EQ: {
				for (int i = 1; i < v.length; i++)
					if (v[i] != v[0])
						return 0L;
				return 1L;
			}
			case NE:
				return v[0] != v[1] ? 1L : 0L;
			case NOT:
				return v[0] == 0 ? 1L : 0L;
			case AND: {
				for (long x : v)
					if (x == 0)
						return 0L;
				return 1L;
			}
			case OR: {
				for (long x : v)
					if (x != 0)
						return 1L;
				return 0L;
			}
			case XOR: {
				long r = 0;
				for (long x : v)
					r ^= (x != 0 ? 1 : 0);
				return r;
			}
			case IFF: {
				boolean b0 = v[0] != 0;
				for (long x : v)
					if ((x != 0) != b0)
						return 0L;
				return 1L;
			}
			case IMP:
				return (v[0] == 0 || v[1] != 0) ? 1L : 0L;
			case IF:
				return v[0] != 0 ? v[1] : v[2];
			default:
				return null; // SET and anything exotic: no folding
		}
	}
}
