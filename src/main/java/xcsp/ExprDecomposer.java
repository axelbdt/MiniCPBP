/*
 * mini-cpbp, replacing classic propagation by belief propagation
 * Copyright (c)  2019. by Gilles Pesant
 *
 * Reified decomposition of XCSP intension expression trees, extracted
 * verbatim from XCSP.parseExpr and its operator helpers so that the same
 * machinery serves both:
 *  - the flat path (tree-argument callbacks and, under
 *    -Dminicpbp.intension.encapsulated=false, buildCtrIntension), which posts
 *    auxiliary variables and primitive constraints into the outer solver;
 *  - the encapsulated path (minicpbp.engine.constraints.Intension), which
 *    runs the same code under hidden capture so the decomposition becomes the
 *    constraint's internal factor graph.
 * The variable mapping is a parameter: XVarInteger leaves resolve through it
 * (outer variables on the flat path, internal boundary copies inside
 * Intension).
 */

package xcsp;

import minicpbp.engine.core.IntVar;
import minicpbp.engine.core.Solver;
import org.xcsp.common.Types;
import org.xcsp.common.predicates.XNode;
import org.xcsp.parser.entries.XVariables.XVarInteger;

import java.security.InvalidParameterException;
import java.util.function.Function;

import static minicpbp.cp.Factory.*;

public class ExprDecomposer {

	private final Solver minicp;
	private final Function<XVarInteger, IntVar> varMap;

	public ExprDecomposer(Solver minicp, Function<XVarInteger, IntVar> varMap) {
		this.minicp = minicp;
		this.varMap = varMap;
	}

	IntVar unaryArithmeticOperatorConstraint(IntVar x, Types.TypeUnaryArithmeticOperator aop) {
		switch (aop) {
			case NEG:
				return minus(x);
			case ABS:
				return abs(x);
			case SQR:
				return product(x,x);
			case NOT: //treat X as a Boolean variable
				x.removeBelow(0);
				x.removeAbove(1);
				return plus(minus(x),1); // not(x) = 1-x
			default:
			// Not needed
			throw new IllegalArgumentException("not implemented");
		}
	}

	IntVar arithmeticOperatorConstraintVal(IntVar x, Types.TypeArithmeticOperator aop, int p) {
		switch (aop) {
		case ADD:
			return plus(x, p);
		case DIST:
			return abs(minus(x, p));
		case SUB:
			return minus(x, p);
		case MUL:
			return mul(x, p);
		case DIV:
			// integer division truncated toward zero, not the inverse of
			// multiplication: 7/2 = 3 although no y satisfies 2y = 7, so
			// "equal(x, mul(y, p))" made every non-exact division fail.
			// Factory.quotient states the relation by its own tuples.
			return quotient(x, makeIntVar(minicp, p, p));
		case MOD:
			// the remainder takes the sign of the dividend and the modulus may
			// be negative (XCSP3/Java): a 0..p-1 remainder built by sumModP is
			// the floored one, wrong for a negative x and undefined for p <= 0.
			// Factory.modulo states the relation by its own tuples.
			return modulo(x, makeIntVar(minicp, p, p));
		case POW:
			return pow(x,makeIntVar(minicp,p,p));
		default:
			throw new IllegalArgumentException("Unknown TypeArithmeticOperator");
		}
	}

	IntVar arithmeticOperatorConstraintVar(IntVar x, Types.TypeArithmeticOperator aop, IntVar y) {
		switch (aop) {
		case ADD:
			return sum(x, y);
		case DIST:
			return abs(sum(x, minus(y)));
		case SUB:
			return sum(x, minus(y));
		case MUL:
			return product(x, y);
		case DIV:
			return quotient(x, y);
		case MOD:
			return modulo(x, y);
		case POW:
			return pow(x, y);
		default:
			throw new IllegalArgumentException("Unknown TypeArithmeticOperator");
		}
	}

	IntVar reifiedRelOperatorConstraintVar(IntVar x, Types.TypeConditionOperatorRel operator, IntVar y) {
		switch (operator) {
			case EQ:
				return isEqual(x, y);
			case GE:
				return isLargerOrEqual(x, y);
			case GT:
				return isLarger(x, y);
			case LE:
				return isLessOrEqual(x, y);
			case LT:
				return isLess(x, y);
			case NE:
				return isNotEqual(x, y);
			default:
				throw new InvalidParameterException("unknown condition");
		}
	}

	IntVar reifiedRelOperatorConstraintVal(IntVar x, Types.TypeConditionOperatorRel operator, int y) {
		switch (operator) {
			case EQ:
				return isEqual(x, y);
			case GE:
				return isLargerOrEqual(x, y);
			case GT:
				return isLarger(x, y);
			case LE:
				return isLessOrEqual(x, y);
			case LT:
				return isLess(x, y);
			case NE:
				return isNotEqual(x, y);
			default:
				throw new InvalidParameterException("unknown condition");
		}
	}

	// logic truth tables
	static final int [][] tableAND = { {0,0,0}, {0,1,0}, {1,0,0}, {1,1,1}};
	static final int [][] tableOR = { {0,0,0}, {0,1,1}, {1,0,1}, {1,1,1}};
	static final int [][] tableXOR = { {0,0,0}, {0,1,1}, {1,0,1}, {1,1,0}};
	static final int [][] tableIFF = { {0,0,1}, {0,1,0}, {1,0,0}, {1,1,1}};
	static final int [][] tableIMP = { {0,0,1}, {0,1,1}, {1,0,0}, {1,1,1}};

	IntVar reifiedLogOperatorConstraint(IntVar x, Types.TypeLogicalOperator operator, IntVar y) {
		IntVar z = makeIntVar(minicp,0,1);
		switch (operator) {
			case AND:
				minicp.post(table(new IntVar[]{x,y,z},tableAND));
				return z;
			case OR:
				minicp.post(table(new IntVar[]{x,y,z},tableOR));
				return z;
			case XOR:
				minicp.post(table(new IntVar[]{x,y,z},tableXOR));
				return z;
			case IFF:
				minicp.post(table(new IntVar[]{x,y,z},tableIFF));
				return z;
			case IMP:
				minicp.post(table(new IntVar[]{x,y,z},tableIMP));
				return z;
			default:
				throw new InvalidParameterException("unknown condition");
		}
	}

	IntVar parseExpr(XNode<XVarInteger> tree) {
//		Log.info(tree.toString() + " op: " + tree.type + " arity: " + tree.arity());
		Types.TypeExpr type = tree.type;
		switch (tree.arity()) {
			case 0:
				if (type == Types.TypeExpr.VAR) {
//					Log.info("in var");
					return varMap.apply(tree.var(0));
				} else if (type == Types.TypeExpr.LONG) {
//					Log.info("in val");
					int val = tree.val(0);
//					Log.info("had to turn val into var in expression tree (shouldn't happen?)");
					return makeIntVar(minicp, val, val);
				} else
					throw new IllegalArgumentException("expression tree leaf that isn't a var nor a value?");
			case 1:
				return unaryArithmeticOperatorConstraint(parseExpr(tree.sons[0]), type.toUnalop());
			case 2:
//				Log.info("in binary op");
				if (type.isRelationalOperator()) {
//					Log.info("in relop");
					if (tree.sons[1].type == Types.TypeExpr.LONG) {
//						Log.info("in rel with val; type is " + type);
						return reifiedRelOperatorConstraintVal(parseExpr(tree.sons[0]), type.toRelop(), tree.sons[1].val(0));
					} else {
						return reifiedRelOperatorConstraintVar(parseExpr(tree.sons[0]), type.toRelop(), parseExpr(tree.sons[1]));
					}
				} else if (type.isArithmeticOperator()) {
					if (tree.sons[1].type == Types.TypeExpr.LONG) {
//						Log.info("in arith with val; type is " + type);
						return arithmeticOperatorConstraintVal(parseExpr(tree.sons[0]), type.toAriop(), tree.sons[1].val(0));
					} else {
						return arithmeticOperatorConstraintVar(parseExpr(tree.sons[0]), type.toAriop(), parseExpr(tree.sons[1]));
					}
				} else if (type.isLogicalOperator()) {
					return reifiedLogOperatorConstraint(parseExpr(tree.sons[0]), type.toLogop(), parseExpr(tree.sons[1]));
				} else if (type == Types.TypeExpr.MAX) {
					return maximum(new IntVar[]{parseExpr(tree.sons[0]),parseExpr(tree.sons[1])});
				}else if (type == Types.TypeExpr.MIN) {
					return minimum(new IntVar[]{parseExpr(tree.sons[0]),parseExpr(tree.sons[1])});
				} else
					throw new IllegalArgumentException("unsupported expression-tree binary node");
			default:
				if (type == Types.TypeExpr.MUL) {
					IntVar cumul = parseExpr(tree.sons[0]);
					for (int i = 1; i < tree.arity(); i++) {
						cumul = product(cumul, parseExpr(tree.sons[i]));
					}
					return cumul;
				}
				if (type == Types.TypeExpr.ADD) {
					IntVar[] children = new IntVar[tree.arity()];
					for (int i = 0; i < tree.arity(); i++) {
						children[i] = parseExpr(tree.sons[i]);
					}
					return sum(children);
				}
				if (type == Types.TypeExpr.MAX) {
					IntVar[] children = new IntVar[tree.arity()];
					for (int i = 0; i < tree.arity(); i++) {
						children[i] = parseExpr(tree.sons[i]);
					}
					return maximum(children);
				}
				if (type == Types.TypeExpr.MIN) {
					IntVar[] children = new IntVar[tree.arity()];
					for (int i = 0; i < tree.arity(); i++) {
						children[i] = parseExpr(tree.sons[i]);
					}
					return minimum(children);
				}
				throw new IllegalArgumentException("not implemented");
		}
	}
}
