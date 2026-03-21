package ru.hollowhorizon.hollowengineloom.idea.inspection;

import java.util.Set;

import com.intellij.psi.JavaTokenType;
import com.intellij.psi.PsiBinaryExpression;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiParenthesizedExpression;
import com.intellij.psi.PsiPolyadicExpression;
import com.intellij.psi.PsiPrefixExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiClass;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiUtil;

final class JavaVersionConditionEvaluator {
	private JavaVersionConditionEvaluator() {
	}

	static VersionConditionSupport.Evaluation evaluate(PsiExpression expression, Set<Integer> universe) {
		if (expression == null || universe.isEmpty()) {
			return null;
		}

		return VersionConditionSupport.evaluation(universe, packedVersion -> evaluateBoolean(expression, packedVersion));
	}

	private static Boolean evaluateBoolean(PsiExpression expression, int packedVersion) {
		final PsiExpression unwrapped = PsiUtil.skipParenthesizedExprDown(expression);

		if (unwrapped == null) {
			return null;
		}

		if (unwrapped instanceof PsiLiteralExpression literalExpression && literalExpression.getValue() instanceof Boolean bool) {
			return bool;
		}

		if (unwrapped instanceof PsiPrefixExpression prefixExpression && prefixExpression.getOperationTokenType() == JavaTokenType.EXCL) {
			final Boolean value = evaluateBoolean(prefixExpression.getOperand(), packedVersion);
			return value == null ? null : !value;
		}

		if (unwrapped instanceof PsiBinaryExpression binaryExpression) {
			return evaluateBooleanBinary(binaryExpression, packedVersion);
		}

		if (unwrapped instanceof PsiPolyadicExpression polyadicExpression) {
			return evaluateBooleanPolyadic(polyadicExpression, packedVersion);
		}

		final Integer intValue = evaluateInt(unwrapped, packedVersion);
		return intValue == null ? null : intValue != 0;
	}

	private static Boolean evaluateBooleanPolyadic(PsiPolyadicExpression expression, int packedVersion) {
		final IElementType tokenType = expression.getOperationTokenType();
		final PsiExpression[] operands = expression.getOperands();

		if (operands.length == 0) {
			return null;
		}

		if (tokenType == JavaTokenType.ANDAND || tokenType == JavaTokenType.OROR) {
			Boolean result = evaluateBoolean(operands[0], packedVersion);

			if (result == null) {
				return null;
			}

			for (int index = 1; index < operands.length; index++) {
				final Boolean operand = evaluateBoolean(operands[index], packedVersion);

				if (operand == null) {
					return null;
				}

				result = tokenType == JavaTokenType.ANDAND ? result && operand : result || operand;
			}

			return result;
		}

		if (tokenType == JavaTokenType.EQEQ || tokenType == JavaTokenType.NE) {
			if (operands.length != 2) {
				return null;
			}

			final Integer leftInt = evaluateInt(operands[0], packedVersion);
			final Integer rightInt = evaluateInt(operands[1], packedVersion);

			if (leftInt != null && rightInt != null) {
				return tokenType == JavaTokenType.EQEQ ? leftInt.intValue() == rightInt.intValue() : leftInt.intValue() != rightInt.intValue();
			}

			final Boolean leftBool = evaluateBoolean(operands[0], packedVersion);
			final Boolean rightBool = evaluateBoolean(operands[1], packedVersion);

			if (leftBool == null || rightBool == null) {
				return null;
			}

			return tokenType == JavaTokenType.EQEQ ? leftBool.equals(rightBool) : !leftBool.equals(rightBool);
		}

		if (tokenType == JavaTokenType.LT || tokenType == JavaTokenType.LE || tokenType == JavaTokenType.GT || tokenType == JavaTokenType.GE) {
			if (operands.length != 2) {
				return null;
			}

			final Integer left = evaluateInt(operands[0], packedVersion);
			final Integer right = evaluateInt(operands[1], packedVersion);

			if (left == null || right == null) {
				return null;
			}

			return switch (tokenType.toString()) {
			case "<" -> left < right;
			case "<=" -> left <= right;
			case ">" -> left > right;
			case ">=" -> left >= right;
			default -> null;
			};
		}

		return null;
	}

	private static Boolean evaluateBooleanBinary(PsiBinaryExpression expression, int packedVersion) {
		final IElementType tokenType = expression.getOperationTokenType();
		final PsiExpression leftOperand = expression.getLOperand();
		final PsiExpression rightOperand = expression.getROperand();

		if (rightOperand == null) {
			return null;
		}

		if (tokenType == JavaTokenType.ANDAND || tokenType == JavaTokenType.OROR) {
			final Boolean left = evaluateBoolean(leftOperand, packedVersion);
			final Boolean right = evaluateBoolean(rightOperand, packedVersion);

			if (left == null || right == null) {
				return null;
			}

			return tokenType == JavaTokenType.ANDAND ? left && right : left || right;
		}

		if (tokenType == JavaTokenType.EQEQ || tokenType == JavaTokenType.NE) {
			final Integer leftInt = evaluateInt(leftOperand, packedVersion);
			final Integer rightInt = evaluateInt(rightOperand, packedVersion);

			if (leftInt != null && rightInt != null) {
				return tokenType == JavaTokenType.EQEQ ? leftInt.intValue() == rightInt.intValue() : leftInt.intValue() != rightInt.intValue();
			}

			final Boolean leftBool = evaluateBoolean(leftOperand, packedVersion);
			final Boolean rightBool = evaluateBoolean(rightOperand, packedVersion);

			if (leftBool == null || rightBool == null) {
				return null;
			}

			return tokenType == JavaTokenType.EQEQ ? leftBool.equals(rightBool) : !leftBool.equals(rightBool);
		}

		if (tokenType == JavaTokenType.LT || tokenType == JavaTokenType.LE || tokenType == JavaTokenType.GT || tokenType == JavaTokenType.GE) {
			final Integer left = evaluateInt(leftOperand, packedVersion);
			final Integer right = evaluateInt(rightOperand, packedVersion);

			if (left == null || right == null) {
				return null;
			}

			return switch (tokenType.toString()) {
			case "<" -> left < right;
			case "<=" -> left <= right;
			case ">" -> left > right;
			case ">=" -> left >= right;
			default -> null;
			};
		}

		return null;
	}

	private static Integer evaluateInt(PsiExpression expression, int packedVersion) {
		final PsiExpression unwrapped = PsiUtil.skipParenthesizedExprDown(expression);

		if (unwrapped == null) {
			return null;
		}

		if (unwrapped instanceof PsiLiteralExpression literalExpression && literalExpression.getValue() instanceof Integer integer) {
			return integer;
		}

		if (unwrapped instanceof PsiParenthesizedExpression parenthesizedExpression) {
			return evaluateInt(parenthesizedExpression.getExpression(), packedVersion);
		}

		if (unwrapped instanceof PsiReferenceExpression referenceExpression) {
			return resolveConstantsField(referenceExpression, packedVersion);
		}

		if (unwrapped instanceof PsiPrefixExpression prefixExpression) {
			final Integer operand = evaluateInt(prefixExpression.getOperand(), packedVersion);

			if (operand == null) {
				return null;
			}

			return switch (prefixExpression.getOperationTokenType().toString()) {
			case "+" -> operand;
			case "-" -> -operand;
			case "~" -> ~operand;
			default -> null;
			};
		}

		if (unwrapped instanceof PsiPolyadicExpression polyadicExpression) {
			return evaluateIntPolyadic(polyadicExpression, packedVersion);
		}

		return null;
	}

	private static Integer evaluateIntPolyadic(PsiPolyadicExpression expression, int packedVersion) {
		final PsiExpression[] operands = expression.getOperands();

		if (operands.length == 0) {
			return null;
		}

		Integer result = evaluateInt(operands[0], packedVersion);

		if (result == null) {
			return null;
		}

		final String token = expression.getOperationTokenType().toString();

		for (int index = 1; index < operands.length; index++) {
			final Integer operand = evaluateInt(operands[index], packedVersion);

			if (operand == null) {
				return null;
			}

			result = switch (token) {
			case "+" -> result + operand;
			case "-" -> result - operand;
			case "*" -> result * operand;
			case "/" -> operand == 0 ? null : result / operand;
			case "%" -> operand == 0 ? null : result % operand;
			case "&" -> result & operand;
			case "|" -> result | operand;
			case "^" -> result ^ operand;
			case "<<" -> result << operand;
			case ">>" -> result >> operand;
			case ">>>" -> result >>> operand;
			default -> null;
			};

			if (result == null) {
				return null;
			}
		}

		return result;
	}

	private static Integer resolveConstantsField(PsiReferenceExpression expression, int packedVersion) {
		if (!isConstantsFieldReference(expression)) {
			return null;
		}

		return VersionConditionSupport.resolveConstantsFieldValue(expression.getReferenceName(), packedVersion);
	}

	private static boolean isConstantsFieldReference(PsiReferenceExpression expression) {
		if (expression.resolve() instanceof PsiField field && isConstantsClass(field.getContainingClass())) {
			return true;
		}

		if (expression.getQualifierExpression() instanceof PsiReferenceExpression qualifier) {
			return "Constants".equals(qualifier.getReferenceName());
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
}
