package ru.hollowhorizon.hollowengineloom.idea.inspection;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.psi.JavaElementVisitor;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiIfStatement;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.PsiNewExpression;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.util.PsiTreeUtil;

public final class RequiresApiGuardInspection extends AbstractBaseJavaLocalInspectionTool {
	@Override
	public PsiElementVisitor buildVisitor(ProblemsHolder holder, boolean isOnTheFly) {
		return new JavaElementVisitor() {
			@Override
			public void visitMethodCallExpression(PsiMethodCallExpression expression) {
				checkUsage(expression, expression.resolveMethod(), holder);
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

			@Override
			public void visitIfStatement(PsiIfStatement statement) {
				super.visitIfStatement(statement);
				checkCondition(statement.getCondition(), holder);
			}
		};
	}

	private static void checkCondition(PsiElement condition, ProblemsHolder holder) {
		if (!(condition instanceof com.intellij.psi.PsiExpression psiExpression)) {
			return;
		}

		final Module module = ModuleUtilCore.findModuleForPsiElement(psiExpression);
		final MultiversionModuleClassifier.ModuleMode moduleMode = MultiversionModuleClassifier.classify(module);

		if (moduleMode.kind() == MultiversionModuleClassifier.Kind.NONE) {
			return;
		}

		final Set<Integer> universe = collectContextVersions(psiExpression, resolveUniverseVersions(psiExpression, moduleMode));
		final VersionConditionSupport.Evaluation evaluation = JavaVersionConditionEvaluator.evaluate(psiExpression, universe);

		if (evaluation == null) {
			return;
		}

		if (evaluation.trueVersions().isEmpty()) {
			holder.registerProblem(psiExpression, RequiresApiInspectionSupport.buildConstantConditionMessage(false));
		} else if (evaluation.falseVersions().isEmpty()) {
			holder.registerProblem(psiExpression, RequiresApiInspectionSupport.buildConstantConditionMessage(true));
		}
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

		final Set<Integer> universe = resolveUniverseVersions(usage, moduleMode);

		if (universe.isEmpty()) {
			return;
		}

		if (moduleMode.kind() == MultiversionModuleClassifier.Kind.COMMON
				&& RequiresApiInspectionSupport.isAvailableEverywhere(requiredVersions, universe)) {
			return;
		}

		final Set<Integer> contextVersions = collectContextVersions(usage, universe);

		if (contextVersions.isEmpty()) {
			return;
		}

		if (requiredVersions.containsAll(contextVersions)) {
			return;
		}

		if (moduleMode.kind() == MultiversionModuleClassifier.Kind.TARGET) {
			holder.registerProblem(usage, RequiresApiInspectionSupport.buildTargetMismatchMessage(requiredVersions, moduleMode.targetVersion()));
			return;
		}

		holder.registerProblem(usage, RequiresApiInspectionSupport.buildMessage(requiredVersions), createQuickFixes(usage, requiredVersions));
	}

	private static LocalQuickFix[] createQuickFixes(PsiElement usage, Set<Integer> requiredVersions) {
		final List<LocalQuickFix> fixes = new java.util.ArrayList<>();

		if (RequiresApiQuickFixSupport.findJavaStatement(usage) != null) {
			fixes.add(new JavaWrapWithRequiresApiGuardQuickFix(requiredVersions));
		}

		fixes.add(new JavaAnnotateWithRequiresApiQuickFix(requiredVersions));
		return fixes.toArray(LocalQuickFix[]::new);
	}

	private static Set<Integer> collectRequiredVersions(PsiModifierListOwner target) {
		Set<Integer> versions = intersect(null, RequiresApiInspectionSupport.resolveSymbolVersions(target));

		if (target instanceof PsiMember member) {
			versions = intersect(versions, RequiresApiInspectionSupport.resolveSymbolVersions(member.getContainingClass()));
		}

		return versions == null ? Set.of() : versions;
	}

	private static Set<Integer> collectContextVersions(PsiElement usage, Set<Integer> universe) {
		Set<Integer> versions = VersionConditionSupport.copy(universe);
		final PsiMethod method = PsiTreeUtil.getParentOfType(usage, PsiMethod.class);
		final PsiClass psiClass = PsiTreeUtil.getParentOfType(usage, PsiClass.class);
		versions = intersectWithAnnotation(versions, method);
		versions = intersectWithAnnotation(versions, psiClass);

		final List<PsiIfStatement> ifStatements = collectEnclosingIfStatements(usage).stream()
				.sorted(Comparator.comparingInt(statement -> depthFrom(statement, usage)))
				.toList();

		for (PsiIfStatement ifStatement : ifStatements) {
			final Set<Integer> branchVersions = resolveBranchVersions(ifStatement, usage, versions);
			versions = branchVersions.isEmpty() ? Set.of() : branchVersions;
		}

		return versions;
	}

	private static int depthFrom(PsiElement ancestor, PsiElement descendant) {
		int depth = 0;
		PsiElement current = descendant;

		while (current != null && current != ancestor) {
			depth++;
			current = current.getParent();
		}

		return depth;
	}

	private static List<PsiIfStatement> collectEnclosingIfStatements(PsiElement usage) {
		final java.util.ArrayList<PsiIfStatement> ifStatements = new java.util.ArrayList<>();
		PsiElement current = usage.getParent();

		while (current != null) {
			if (current instanceof PsiIfStatement ifStatement) {
				ifStatements.add(ifStatement);
			}

			current = current.getParent();
		}

		return ifStatements;
	}

	private static Set<Integer> resolveBranchVersions(PsiIfStatement ifStatement, PsiElement elementInBranch, Set<Integer> universe) {
		final VersionConditionSupport.Evaluation evaluation = JavaVersionConditionEvaluator.evaluate(ifStatement.getCondition(), universe);

		if (evaluation == null) {
			return universe;
		}

		final PsiStatement thenBranch = ifStatement.getThenBranch();

		if (thenBranch != null && PsiTreeUtil.isAncestor(thenBranch, elementInBranch, false)) {
			return evaluation.trueVersions();
		}

		final PsiStatement elseBranch = ifStatement.getElseBranch();

		if (elseBranch != null && PsiTreeUtil.isAncestor(elseBranch, elementInBranch, false)) {
			return evaluation.falseVersions();
		}

		return universe;
	}

	private static Set<Integer> intersectWithAnnotation(Set<Integer> current, PsiModifierListOwner owner) {
		if (owner == null) {
			return current;
		}

		final PsiAnnotation annotation = owner.getAnnotation(HollowEngineConstants.REQUIRES_API_FQN);

		if (annotation == null) {
			return current;
		}

		return VersionConditionSupport.intersect(current, RequiresApiAnnotationReader.readJavaAnnotationVersions(annotation));
	}

	private static Set<Integer> intersect(Set<Integer> left, Collection<Integer> right) {
		return VersionConditionSupport.intersect(left, right);
	}

	private static Set<Integer> resolveUniverseVersions(PsiElement usage, MultiversionModuleClassifier.ModuleMode moduleMode) {
		if (moduleMode.kind() == MultiversionModuleClassifier.Kind.TARGET) {
			return Set.of(RequiresApiInspectionSupport.packVersion(moduleMode.targetVersion()));
		}

		return RequiresApiInspectionSupport.resolveProjectVersions(usage);
	}
}
