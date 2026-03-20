package ru.hollowhorizon.hollowengineloom.idea.inspection;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.module.Module;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiArrayInitializerMemberValue;
import com.intellij.psi.PsiBinaryExpression;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiIfStatement;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiParameterList;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.openapi.module.ModuleUtilCore;

public final class RequiresApiGuardInspection extends AbstractBaseJavaLocalInspectionTool {
	@Override
	public PsiElementVisitor buildVisitor(ProblemsHolder holder, boolean isOnTheFly) {
		return new JavaElementVisitor() {
			@Override
			public void visitMethodCallExpression(PsiMethodCallExpression expression) {
				final PsiMethod method = expression.resolveMethod();
				checkUsage(expression, method, holder);
			}

			@Override
			public void visitReferenceExpression(PsiReferenceExpression expression) {
				if (expression.getParent() instanceof PsiMethodCallExpression callExpression && callExpression.getMethodExpression() == expression) {
					return;
				}

				if (expression.resolve() instanceof PsiField field) {
					checkUsage(expression, field, holder);
				}
			}

			@Override
			public void visitNewExpression(PsiNewExpression expression) {
				checkUsage(expression, expression.resolveConstructor(), holder);
			}
		};
	}

	private static void checkUsage(PsiElement usage, PsiModifierListOwner target, ProblemsHolder holder) {
		if (target == null || usage == null) {
			return;
		}

		final Module module = ModuleUtilCore.findModuleForPsiElement(usage);
		final MultiversionModuleClassifier.ModuleMode moduleMode = MultiversionModuleClassifier.classify(module);

		if (moduleMode.kind() == MultiversionModuleClassifier.Kind.NONE) {
			return;
		}

		final Set<Integer> requiredVersions = collectRequiredVersions(target);

		if (requiredVersions.isEmpty()) {
			return;
		}

		if (moduleMode.kind() == MultiversionModuleClassifier.Kind.TARGET) {
			final int targetVersion = RequiresApiInspectionSupport.packVersion(moduleMode.targetVersion());

			if (!requiredVersions.contains(targetVersion)) {
				holder.registerProblem(usage, RequiresApiInspectionSupport.buildTargetMismatchMessage(requiredVersions, moduleMode.targetVersion()));
			}

			return;
		}

		final Set<Integer> projectVersions = RequiresApiInspectionSupport.resolveProjectVersions(usage);

		if (RequiresApiInspectionSupport.isAvailableEverywhere(requiredVersions, projectVersions)) {
			return;
		}

		final Set<Integer> contextVersions = collectContextVersions(usage);

		if (!contextVersions.isEmpty() && requiredVersions.containsAll(contextVersions)) {
			return;
		}

		holder.registerProblem(usage, RequiresApiInspectionSupport.buildMessage(requiredVersions));
	}

	private static Set<Integer> collectRequiredVersions(PsiModifierListOwner target) {
		Set<Integer> versions = intersect(null, RequiresApiInspectionSupport.resolveSymbolVersions(target));

		if (target instanceof PsiMember member) {
			versions = intersect(versions, RequiresApiInspectionSupport.resolveSymbolVersions(member.getContainingClass()));
		}

		return versions == null ? Set.of() : versions;
	}

	private static Set<Integer> collectContextVersions(PsiElement usage) {
		Set<Integer> versions = null;

		final PsiMethod method = PsiTreeUtil.getParentOfType(usage, PsiMethod.class);
		final PsiClass psiClass = PsiTreeUtil.getParentOfType(usage, PsiClass.class);
		versions = intersectWithAnnotation(versions, method);
		versions = intersectWithAnnotation(versions, psiClass);

		PsiElement current = usage;

		while (current != null && current != method && current != psiClass) {
			if (current.getParent() instanceof PsiIfStatement ifStatement) {
				final Set<Integer> branchVersions = resolveBranchVersions(ifStatement, current);

				if (!branchVersions.isEmpty()) {
					versions = intersect(versions, branchVersions);
				}
			}

			current = current.getParent();
		}

		return versions == null ? Set.of() : versions;
	}

	private static Set<Integer> resolveBranchVersions(PsiIfStatement ifStatement, PsiElement elementInBranch) {
		final PsiStatement thenBranch = ifStatement.getThenBranch();

		if (thenBranch != null && PsiTreeUtil.isAncestor(thenBranch, elementInBranch, false)) {
			return resolveExactVersions(ifStatement.getCondition());
		}

		return Set.of();
	}

	private static Set<Integer> resolveExactVersions(PsiExpression expression) {
		final PsiExpression unwrapped = PsiUtil.skipParenthesizedExprDown(expression);

		if (unwrapped instanceof PsiMethodCallExpression callExpression) {
			return resolveConstantsIsCall(callExpression);
		}

		if (unwrapped instanceof PsiBinaryExpression binaryExpression) {
			return resolveVersionEquality(binaryExpression);
		}

		return Set.of();
	}

	private static Set<Integer> resolveConstantsIsCall(PsiMethodCallExpression callExpression) {
		if (!(callExpression.getMethodExpression().getQualifierExpression() instanceof PsiReferenceExpression qualifier)) {
			return Set.of();
		}

		if (!"Constants".equals(qualifier.getReferenceName())) {
			return Set.of();
		}

		if (!"is".equals(callExpression.getMethodExpression().getReferenceName())) {
			return Set.of();
		}

		final PsiMethod resolvedMethod = callExpression.resolveMethod();

		if (resolvedMethod == null) {
			return Set.of();
		}

		final PsiParameterList parameterList = resolvedMethod.getParameterList();

		if (parameterList.getParametersCount() != 1) {
			return Set.of();
		}

		final PsiExpression[] arguments = callExpression.getArgumentList().getExpressions();

		if (arguments.length != 1) {
			return Set.of();
		}

		final Integer packed = readPackedVersion(arguments[0]);
		return packed == null ? Set.of() : Set.of(packed);
	}

	private static Set<Integer> resolveVersionEquality(PsiBinaryExpression binaryExpression) {
		final IElementType tokenType = binaryExpression.getOperationTokenType();

		if (!JavaTokenSets.EQUALITY_TOKENS.contains(tokenType)) {
			return Set.of();
		}

		final PsiExpression left = PsiUtil.skipParenthesizedExprDown(binaryExpression.getLOperand());
		final PsiExpression right = PsiUtil.skipParenthesizedExprDown(binaryExpression.getROperand());

		if (left == null || right == null) {
			return Set.of();
		}

		if (isMinecraftVersionField(left)) {
			final Integer packed = readPackedVersion(right);
			return packed == null ? Set.of() : Set.of(packed);
		}

		if (isMinecraftVersionField(right)) {
			final Integer packed = readPackedVersion(left);
			return packed == null ? Set.of() : Set.of(packed);
		}

		return Set.of();
	}

	private static boolean isMinecraftVersionField(PsiExpression expression) {
		if (!(expression instanceof PsiReferenceExpression referenceExpression)) {
			return false;
		}

		if (!"MINECRAFT_VERSION".equals(referenceExpression.getReferenceName())) {
			return false;
		}

		if (!(referenceExpression.getQualifierExpression() instanceof PsiReferenceExpression qualifier)) {
			return false;
		}

		return "Constants".equals(qualifier.getReferenceName());
	}

	private static Integer readPackedVersion(PsiExpression expression) {
		final PsiExpression unwrapped = PsiUtil.skipParenthesizedExprDown(expression);

		if (unwrapped instanceof PsiLiteralExpression literalExpression && literalExpression.getValue() instanceof Integer integer) {
			return integer;
		}

		return null;
	}

	private static Set<Integer> intersectWithAnnotation(Set<Integer> current, PsiModifierListOwner owner) {
		if (owner == null) {
			return current;
		}

		final PsiAnnotation annotation = owner.getAnnotation(HollowEngineConstants.REQUIRES_API_FQN);

		if (annotation == null) {
			return current;
		}

		final Set<Integer> annotationVersions = RequiresApiAnnotationReader.readJavaAnnotationVersions(annotation);
		return intersect(current, annotationVersions);
	}

	private static Set<Integer> intersect(Set<Integer> left, Collection<Integer> right) {
		if (right == null || right.isEmpty()) {
			return left == null ? Set.of() : left;
		}

		if (left == null) {
			return new LinkedHashSet<>(right);
		}

		final Set<Integer> intersection = new LinkedHashSet<>(left);
		intersection.retainAll(right);
		return intersection;
	}

	private static final class JavaTokenSets {
		private static final Set<IElementType> EQUALITY_TOKENS = Set.of(
				com.intellij.psi.JavaTokenType.EQEQ
		);

		private JavaTokenSets() {
		}
	}
}
