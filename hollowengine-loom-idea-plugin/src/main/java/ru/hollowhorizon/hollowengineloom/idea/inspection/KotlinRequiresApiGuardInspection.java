package ru.hollowhorizon.hollowengineloom.idea.inspection;

import java.util.List;
import java.util.Set;
import java.util.Collections;
import java.util.IdentityHashMap;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiReference;
import com.intellij.psi.util.PsiTreeUtil;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.psi.KtAnnotationEntry;
import org.jetbrains.kotlin.psi.KtCallExpression;
import org.jetbrains.kotlin.psi.KtClassOrObject;
import org.jetbrains.kotlin.psi.KtExpression;
import org.jetbrains.kotlin.psi.KtIfExpression;
import org.jetbrains.kotlin.psi.KtNamedFunction;
import org.jetbrains.kotlin.psi.KtQualifiedExpression;
import org.jetbrains.kotlin.psi.KtReferenceExpression;
import org.jetbrains.kotlin.psi.KtSimpleNameExpression;
import org.jetbrains.kotlin.psi.KtVisitorVoid;

public final class KotlinRequiresApiGuardInspection extends LocalInspectionTool {
	@Override
	public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
		final Set<PsiElement> reportedAnchors = Collections.newSetFromMap(new IdentityHashMap<>());

		return new KtVisitorVoid() {
			@Override
			public void visitCallExpression(@NotNull KtCallExpression expression) {
				super.visitCallExpression(expression);
				checkUsage(expression, resolveTarget(expression.getCalleeExpression()), holder, reportedAnchors);
			}

			@Override
			public void visitReferenceExpression(@NotNull KtReferenceExpression expression) {
				super.visitReferenceExpression(expression);

				if (isPartOfCallCallee(expression)) {
					return;
				}

				checkUsage(expression, resolveTarget(expression), holder, reportedAnchors);
			}

			@Override
			public void visitIfExpression(@NotNull KtIfExpression expression) {
				super.visitIfExpression(expression);
				checkCondition(expression, holder);
			}
		};
	}

	private static void checkCondition(KtIfExpression ifExpression, ProblemsHolder holder) {
		final KtExpression condition = ifExpression.getCondition();

		if (condition == null) {
			return;
		}

		final Module module = ModuleUtilCore.findModuleForPsiElement(condition);
		final MultiversionModuleClassifier.ModuleMode moduleMode = resolveModuleMode(condition, module);

		if (moduleMode.kind() == MultiversionModuleClassifier.Kind.NONE) {
			return;
		}

		final Set<Integer> universe = collectContextVersions(condition, resolveUniverseVersions(condition, moduleMode));
		final VersionConditionSupport.Evaluation evaluation = KotlinVersionConditionEvaluator.evaluate(condition, universe);

		if (evaluation == null) {
			return;
		}

		if (evaluation.trueVersions().isEmpty()) {
			holder.registerProblem(condition, RequiresApiInspectionSupport.buildConstantConditionMessage(false));
		} else if (evaluation.falseVersions().isEmpty()) {
			holder.registerProblem(condition, RequiresApiInspectionSupport.buildConstantConditionMessage(true));
		}
	}

	private static void checkUsage(PsiElement usage, PsiElement target, ProblemsHolder holder, Set<PsiElement> reportedAnchors) {
		if (target == null || usage == null) {
			return;
		}

		if (RequiresApiInspectionSupport.isImportContext(usage) || RequiresApiInspectionSupport.isImportContext(target)) {
			return;
		}

		final PsiElement anchor = usageAnchor(usage);

		if (RequiresApiInspectionSupport.isImportContext(anchor)) {
			return;
		}

		if (!reportedAnchors.add(anchor)) {
			return;
		}

		final Module module = ModuleUtilCore.findModuleForPsiElement(anchor);
		final MultiversionModuleClassifier.ModuleMode moduleMode = resolveModuleMode(anchor, module);

		if (moduleMode.kind() == MultiversionModuleClassifier.Kind.NONE) {
			return;
		}

		final Set<Integer> requiredVersions = collectRequiredVersions(target);

		if (requiredVersions.isEmpty()) {
			return;
		}

		final Set<Integer> universe = resolveUniverseVersions(anchor, moduleMode);

		if (universe.isEmpty()) {
			return;
		}

		if (moduleMode.kind() == MultiversionModuleClassifier.Kind.COMMON
				&& RequiresApiInspectionSupport.isAvailableEverywhere(requiredVersions, universe)) {
			return;
		}

		final Set<Integer> contextVersions = collectContextVersions(anchor, universe);

		if (contextVersions.isEmpty()) {
			return;
		}

		if (requiredVersions.containsAll(contextVersions)) {
			return;
		}

		if (moduleMode.kind() == MultiversionModuleClassifier.Kind.TARGET) {
			holder.registerProblem(anchor, RequiresApiInspectionSupport.buildTargetMismatchMessage(requiredVersions, moduleMode.targetVersion()));
			return;
		}

		holder.registerProblem(anchor, RequiresApiInspectionSupport.buildMessage(requiredVersions), createQuickFixes(anchor, requiredVersions));
	}

	private static PsiElement usageAnchor(PsiElement usage) {
		PsiElement anchor = usage;
		PsiElement current = usage;

		while (current.getParent() instanceof KtQualifiedExpression qualifiedExpression
				&& qualifiedExpression.getSelectorExpression() == current) {
			anchor = qualifiedExpression;
			current = qualifiedExpression;
		}

		if (anchor.getParent() instanceof KtCallExpression callExpression && callExpression.getCalleeExpression() == anchor) {
			return callExpression;
		}

		return anchor;
	}

	private static LocalQuickFix[] createQuickFixes(PsiElement usage, Set<Integer> requiredVersions) {
		final java.util.List<LocalQuickFix> fixes = new java.util.ArrayList<>();

		if (RequiresApiQuickFixSupport.findKotlinStatement(usage) != null) {
			fixes.add(new KotlinWrapWithRequiresApiGuardQuickFix(requiredVersions));
		}

		fixes.add(new KotlinAnnotateWithRequiresApiQuickFix(requiredVersions));
		return fixes.toArray(LocalQuickFix[]::new);
	}

	private static PsiElement resolveTarget(KtExpression expression) {
		if (expression == null) {
			return null;
		}

		final PsiElement direct = resolveFromReferences(expression);

		if (direct != null) {
			return direct;
		}

		for (PsiReference reference : expression.getReferences()) {
			final PsiElement resolved = reference.resolve();

			if (resolved != null) {
				return resolved;
			}
		}

		if (expression instanceof KtCallExpression callExpression && callExpression.getCalleeExpression() != null) {
			final PsiElement calleeDirect = resolveFromReferences(callExpression.getCalleeExpression());

			if (calleeDirect != null) {
				return calleeDirect;
			}

			for (PsiReference reference : callExpression.getCalleeExpression().getReferences()) {
				final PsiElement resolved = reference.resolve();

				if (resolved != null) {
					return resolved;
				}
			}

			if (callExpression.getCalleeExpression() instanceof KtSimpleNameExpression simpleNameExpression) {
				final PsiElement localDeclaration = findLocalKotlinFunction(simpleNameExpression);

				if (localDeclaration != null) {
					return localDeclaration;
				}
			}
		}

		if (expression.getParent() instanceof org.jetbrains.kotlin.psi.KtQualifiedExpression qualifiedExpression) {
			final PsiElement qualifiedDirect = resolveFromReferences(qualifiedExpression);

			if (qualifiedDirect != null) {
				return qualifiedDirect;
			}

			for (PsiReference reference : qualifiedExpression.getReferences()) {
				final PsiElement resolved = reference.resolve();

				if (resolved != null) {
					return resolved;
				}
			}
		}

		return null;
	}

	private static PsiElement resolveFromReferences(KtExpression expression) {
		final PsiReference reference = expression.getReference();

		return reference == null ? null : reference.resolve();
	}

	private static PsiElement findLocalKotlinFunction(KtSimpleNameExpression expression) {
		final String name = expression.getReferencedName();

		if (name == null || expression.getContainingFile() == null) {
			return null;
		}

		for (KtNamedFunction function : PsiTreeUtil.findChildrenOfType(expression.getContainingFile(), KtNamedFunction.class)) {
			if (name.equals(function.getName())) {
				return function;
			}
		}

		return null;
	}

	private static boolean isPartOfCallCallee(KtReferenceExpression expression) {
		if (expression.getParent() instanceof KtCallExpression) {
			return true;
		}

		if (expression.getParent() instanceof KtQualifiedExpression qualifiedExpression
				&& qualifiedExpression.getSelectorExpression() == expression
				&& qualifiedExpression.getParent() instanceof KtCallExpression) {
			return true;
		}

		PsiElement current = expression;

		while (current.getParent() instanceof KtQualifiedExpression qualifiedExpression
				&& qualifiedExpression.getSelectorExpression() == current) {
			current = qualifiedExpression;

			if (current.getParent() instanceof KtCallExpression) {
				return true;
			}
		}

		return false;
	}

	private static Set<Integer> collectRequiredVersions(PsiElement target) {
		Set<Integer> versions = intersect(null, RequiresApiInspectionSupport.resolveSymbolVersions(target));

		if (target instanceof PsiMember member) {
			versions = intersect(versions, RequiresApiInspectionSupport.resolveSymbolVersions(member.getContainingClass()));
		}

		if (target instanceof KtNamedFunction function) {
			versions = intersect(versions, RequiresApiInspectionSupport.resolveSymbolVersions(PsiTreeUtil.getParentOfType(function, KtClassOrObject.class)));
		}

		return versions == null ? Set.of() : versions;
	}

	private static Set<Integer> collectContextVersions(PsiElement usage, Set<Integer> universe) {
		Set<Integer> versions = VersionConditionSupport.copy(universe);
		versions = intersectWithKotlinAnnotations(versions, PsiTreeUtil.getParentOfType(usage, KtNamedFunction.class));
		versions = intersectWithKotlinAnnotations(versions, PsiTreeUtil.getParentOfType(usage, KtClassOrObject.class));

		final List<KtIfExpression> ifExpressions = collectEnclosingIfExpressions(usage).stream()
				.sorted((left, right) -> Integer.compare(depthFrom(left, usage), depthFrom(right, usage)))
				.toList();

		for (KtIfExpression ifExpression : ifExpressions) {
			final Set<Integer> branchVersions = resolveBranchVersions(ifExpression, usage, versions);
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

	private static List<KtIfExpression> collectEnclosingIfExpressions(PsiElement usage) {
		final java.util.ArrayList<KtIfExpression> ifExpressions = new java.util.ArrayList<>();
		PsiElement current = usage.getParent();

		while (current != null) {
			if (current instanceof KtIfExpression ifExpression) {
				ifExpressions.add(ifExpression);
			}

			current = current.getParent();
		}

		return ifExpressions;
	}

	private static Set<Integer> resolveBranchVersions(KtIfExpression ifExpression, PsiElement elementInBranch, Set<Integer> universe) {
		final VersionConditionSupport.Evaluation evaluation = KotlinVersionConditionEvaluator.evaluate(ifExpression.getCondition(), universe);

		if (evaluation == null) {
			return universe;
		}

		final KtExpression thenBranch = ifExpression.getThen();

		if (thenBranch != null && PsiTreeUtil.isAncestor(thenBranch, elementInBranch, false)) {
			return evaluation.trueVersions();
		}

		final KtExpression elseBranch = ifExpression.getElse();

		if (elseBranch != null && PsiTreeUtil.isAncestor(elseBranch, elementInBranch, false)) {
			return evaluation.falseVersions();
		}

		return universe;
	}

	private static Set<Integer> intersectWithKotlinAnnotations(Set<Integer> current, KtNamedFunction function) {
		return function == null ? current : intersect(current, readKotlinVersions(function.getAnnotationEntries()));
	}

	private static Set<Integer> intersectWithKotlinAnnotations(Set<Integer> current, KtClassOrObject klass) {
		return klass == null ? current : intersect(current, readKotlinVersions(klass.getAnnotationEntries()));
	}

	private static Set<Integer> readKotlinVersions(Iterable<KtAnnotationEntry> entries) {
		return RequiresApiAnnotationReader.readKotlinAnnotationVersions(entries);
	}

	private static Set<Integer> intersect(Set<Integer> left, Set<Integer> right) {
		return VersionConditionSupport.intersect(left, right);
	}

	private static Set<Integer> resolveUniverseVersions(PsiElement usage, MultiversionModuleClassifier.ModuleMode moduleMode) {
		if (moduleMode.kind() == MultiversionModuleClassifier.Kind.TARGET) {
			return Set.of(RequiresApiInspectionSupport.packVersion(moduleMode.targetVersion()));
		}

		return RequiresApiInspectionSupport.resolveProjectVersions(usage);
	}

	private static MultiversionModuleClassifier.ModuleMode resolveModuleMode(PsiElement element, Module module) {
		final MultiversionModuleClassifier.ModuleMode mode = MultiversionModuleClassifier.classify(module);

		if (mode.kind() != MultiversionModuleClassifier.Kind.NONE) {
			return mode;
		}

		return RequiresApiInspectionSupport.resolveProjectVersions(element).isEmpty()
				? mode
				: MultiversionModuleClassifier.ModuleMode.common(null);
	}
}
