package ru.hollowhorizon.hollowengineloom.idea.inspection;

import java.util.Set;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.tree.IElementType;

import org.jetbrains.kotlin.lexer.KtTokens;
import org.jetbrains.kotlin.psi.KtBinaryExpression;
import org.jetbrains.kotlin.psi.KtConstantExpression;
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression;
import org.jetbrains.kotlin.psi.KtExpression;
import org.jetbrains.kotlin.psi.KtNameReferenceExpression;
import org.jetbrains.kotlin.psi.KtParenthesizedExpression;
import org.jetbrains.kotlin.psi.KtPrefixExpression;
import org.jetbrains.kotlin.psi.KtUnaryExpression;

final class KotlinVersionConditionEvaluator {
	private KotlinVersionConditionEvaluator() {
	}

	static VersionConditionSupport.Evaluation evaluate(KtExpression expression, Set<Integer> universe) {
		if (expression == null || universe.isEmpty()) {
			return null;
		}

		return VersionConditionSupport.evaluation(universe, packedVersion -> evaluateBoolean(expression, packedVersion));
	}

	private static Boolean evaluateBoolean(KtExpression expression, int packedVersion) {
		final KtExpression unwrapped = unwrap(expression);

		if (unwrapped == null) {
			return null;
		}

		if (unwrapped instanceof KtConstantExpression constantExpression) {
			if ("true".equals(constantExpression.getText())) {
				return true;
			}

			if ("false".equals(constantExpression.getText())) {
				return false;
			}
		}

		if (unwrapped instanceof KtUnaryExpression unaryExpression && unaryExpression.getOperationToken() == KtTokens.EXCL) {
			final Boolean value = evaluateBoolean(unaryExpression.getBaseExpression(), packedVersion);
			return value == null ? null : !value;
		}

		if (unwrapped instanceof KtBinaryExpression binaryExpression) {
			return evaluateBooleanBinary(binaryExpression, packedVersion);
		}

		final Integer intValue = evaluateInt(unwrapped, packedVersion);
		return intValue == null ? null : intValue != 0;
	}

	private static Boolean evaluateBooleanBinary(KtBinaryExpression expression, int packedVersion) {
		final IElementType token = expression.getOperationToken();

		if (token == KtTokens.ANDAND || token == KtTokens.OROR) {
			final Boolean left = evaluateBoolean(expression.getLeft(), packedVersion);
			final Boolean right = evaluateBoolean(expression.getRight(), packedVersion);

			if (left == null || right == null) {
				return null;
			}

			return token == KtTokens.ANDAND ? left && right : left || right;
		}

		if (token == KtTokens.EQEQ || token == KtTokens.EXCLEQ) {
			final Integer leftInt = evaluateInt(expression.getLeft(), packedVersion);
			final Integer rightInt = evaluateInt(expression.getRight(), packedVersion);

			if (leftInt != null && rightInt != null) {
				return token == KtTokens.EQEQ ? leftInt.intValue() == rightInt.intValue() : leftInt.intValue() != rightInt.intValue();
			}

			final Boolean leftBool = evaluateBoolean(expression.getLeft(), packedVersion);
			final Boolean rightBool = evaluateBoolean(expression.getRight(), packedVersion);

			if (leftBool == null || rightBool == null) {
				return null;
			}

			return token == KtTokens.EQEQ ? leftBool.equals(rightBool) : !leftBool.equals(rightBool);
		}

		if (token == KtTokens.LT || token == KtTokens.LTEQ || token == KtTokens.GT || token == KtTokens.GTEQ) {
			final Integer left = evaluateInt(expression.getLeft(), packedVersion);
			final Integer right = evaluateInt(expression.getRight(), packedVersion);

			if (left == null || right == null) {
				return null;
			}

			return switch (token.toString()) {
			case "<" -> left < right;
			case "<=" -> left <= right;
			case ">" -> left > right;
			case ">=" -> left >= right;
			default -> null;
			};
		}

		return null;
	}

	private static Integer evaluateInt(KtExpression expression, int packedVersion) {
		final KtExpression unwrapped = unwrap(expression);

		if (unwrapped == null) {
			return null;
		}

		if (unwrapped instanceof KtConstantExpression constantExpression) {
			try {
				return Integer.valueOf(constantExpression.getText());
			} catch (NumberFormatException ignored) {
				return null;
			}
		}

		if (unwrapped instanceof KtNameReferenceExpression nameReferenceExpression) {
			return resolveConstantsField(nameReferenceExpression, packedVersion);
		}

		if (unwrapped instanceof KtDotQualifiedExpression qualifiedExpression) {
			return resolveConstantsField(qualifiedExpression, packedVersion);
		}

		if (unwrapped instanceof KtPrefixExpression prefixExpression) {
			final Integer operand = evaluateInt(prefixExpression.getBaseExpression(), packedVersion);

			if (operand == null) {
				return null;
			}

			return switch (prefixExpression.getOperationToken().toString()) {
			case "+" -> operand;
			case "-" -> -operand;
			default -> null;
			};
		}

		if (unwrapped instanceof KtBinaryExpression binaryExpression) {
			return evaluateIntBinary(binaryExpression, packedVersion);
		}

		return null;
	}

	private static Integer evaluateIntBinary(KtBinaryExpression expression, int packedVersion) {
		final Integer left = evaluateInt(expression.getLeft(), packedVersion);
		final Integer right = evaluateInt(expression.getRight(), packedVersion);

		if (left == null || right == null) {
			return null;
		}

		return switch (expression.getOperationToken().toString()) {
		case "+" -> left + right;
		case "-" -> left - right;
		case "*" -> left * right;
		case "/" -> right == 0 ? null : left / right;
		case "%" -> right == 0 ? null : left % right;
		case "and" -> left & right;
		case "or" -> left | right;
		case "xor" -> left ^ right;
		case "shl" -> left << right;
		case "shr" -> left >> right;
		case "ushr" -> left >>> right;
		default -> null;
		};
	}

	private static Integer resolveConstantsField(KtDotQualifiedExpression expression, int packedVersion) {
		if (!(expression.getSelectorExpression() instanceof KtNameReferenceExpression selector)) {
			return null;
		}

		if (!(expression.getReceiverExpression() instanceof KtNameReferenceExpression qualifier) || !"Constants".equals(qualifier.getReferencedName())) {
			return resolveConstantsField(selector, packedVersion);
		}

		return VersionConditionSupport.resolveConstantsFieldValue(selector.getReferencedName(), packedVersion);
	}

	private static Integer resolveConstantsField(KtNameReferenceExpression expression, int packedVersion) {
		if (!isConstantsField(expression)) {
			return null;
		}

		return VersionConditionSupport.resolveConstantsFieldValue(expression.getReferencedName(), packedVersion);
	}

	private static boolean isConstantsField(KtNameReferenceExpression expression) {
		for (var reference : expression.getReferences()) {
			if (reference.resolve() instanceof PsiField field && isConstantsClass(field.getContainingClass())) {
				return true;
			}
		}

		return false;
	}

	private static boolean isConstantsClass(PsiClass psiClass) {
		if (psiClass == null) {
			return false;
		}

		if ("Constants".equals(psiClass.getName())) {
			return true;
		}

		final String qualifiedName = psiClass.getQualifiedName();
		return qualifiedName != null && qualifiedName.endsWith(".Constants");
	}

	private static KtExpression unwrap(KtExpression expression) {
		KtExpression current = expression;

		while (current instanceof KtParenthesizedExpression parenthesizedExpression) {
			current = parenthesizedExpression.getExpression();
		}

		return current;
	}
}
