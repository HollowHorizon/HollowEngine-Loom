package ru.hollowhorizon.hollowengineloom.idea.inspection;

import java.util.LinkedHashSet;
import java.util.Set;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiLiteralExpression;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.PsiReference;
import com.intellij.psi.util.PsiTreeUtil;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.psi.KtAnnotationEntry;
import org.jetbrains.kotlin.psi.KtBinaryExpression;
import org.jetbrains.kotlin.psi.KtCallExpression;
import org.jetbrains.kotlin.psi.KtClassOrObject;
import org.jetbrains.kotlin.psi.KtExpression;
import org.jetbrains.kotlin.psi.KtIfExpression;
import org.jetbrains.kotlin.psi.KtNameReferenceExpression;
import org.jetbrains.kotlin.psi.KtNamedFunction;
import org.jetbrains.kotlin.psi.KtParenthesizedExpression;
import org.jetbrains.kotlin.psi.KtQualifiedExpression;
import org.jetbrains.kotlin.psi.KtReferenceExpression;
import org.jetbrains.kotlin.psi.KtVisitorVoid;

public final class KotlinRequiresApiGuardInspection extends LocalInspectionTool {
	@Override
	public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
		return new KtVisitorVoid() {
			@Override
			public void visitCallExpression(@NotNull KtCallExpression expression) {
				super.visitCallExpression(expression);
				checkUsage(expression, resolveTarget(expression.getCalleeExpression()), holder);
			}

			@Override
			public void visitReferenceExpression(@NotNull KtReferenceExpression expression) {
				super.visitReferenceExpression(expression);

				if (expression.getParent() instanceof KtCallExpression) {
					return;
				}

				checkUsage(expression, resolveTarget(expression), holder);
			}
		};
	}

	private static void checkUsage(PsiElement usage, PsiModifierListOwner target, ProblemsHolder holder) {
		if (target == null || usage == null) {
			return;
		}

		final Set<Integer> requiredVersions = collectRequiredVersions(target);
		final Set<Integer> projectVersions = RequiresApiInspectionSupport.resolveProjectVersions(usage);

		if (requiredVersions.isEmpty() || RequiresApiInspectionSupport.isAvailableEverywhere(requiredVersions, projectVersions)) {
			return;
		}

		final Set<Integer> contextVersions = collectContextVersions(usage);

		if (!contextVersions.isEmpty() && requiredVersions.containsAll(contextVersions)) {
			return;
		}

		holder.registerProblem(usage, RequiresApiInspectionSupport.buildMessage(requiredVersions));
	}

	private static PsiModifierListOwner resolveTarget(KtExpression expression) {
		if (expression == null) {
			return null;
		}

		final PsiReference[] references = expression.getReferences();

		for (PsiReference reference : references) {
			final PsiElement resolved = reference.resolve();

			if (resolved instanceof PsiModifierListOwner owner) {
				return owner;
			}
		}

		return null;
	}

	private static Set<Integer> collectRequiredVersions(PsiModifierListOwner target) {
		Set<Integer> versions = intersect(null, MultiversionMetadataResolver.resolveVersions(target, target.getProject()));
		versions = intersectWithJavaAnnotation(versions, target);

		if (target instanceof PsiMember member) {
			versions = intersect(versions, MultiversionMetadataResolver.resolveVersions(member.getContainingClass(), target.getProject()));
			versions = intersectWithJavaAnnotation(versions, member.getContainingClass());
		}

		return versions == null ? Set.of() : versions;
	}

	private static Set<Integer> collectContextVersions(PsiElement usage) {
		Set<Integer> versions = null;

		versions = intersectWithKotlinAnnotations(versions, PsiTreeUtil.getParentOfType(usage, KtNamedFunction.class));
		versions = intersectWithKotlinAnnotations(versions, PsiTreeUtil.getParentOfType(usage, KtClassOrObject.class));

		PsiElement current = usage;
		final KtNamedFunction function = PsiTreeUtil.getParentOfType(usage, KtNamedFunction.class);
		final KtClassOrObject klass = PsiTreeUtil.getParentOfType(usage, KtClassOrObject.class);

		while (current != null && current != function && current != klass) {
			if (current.getParent() instanceof KtIfExpression ifExpression) {
				final Set<Integer> branchVersions = resolveThenBranchVersions(ifExpression, current);

				if (!branchVersions.isEmpty()) {
					versions = intersect(versions, branchVersions);
				}
			}

			current = current.getParent();
		}

		return versions == null ? Set.of() : versions;
	}

	private static Set<Integer> resolveThenBranchVersions(KtIfExpression ifExpression, PsiElement elementInBranch) {
		final KtExpression thenBranch = ifExpression.getThen();

		if (thenBranch != null && PsiTreeUtil.isAncestor(thenBranch, elementInBranch, false)) {
			return resolveExactVersions(ifExpression.getCondition());
		}

		return Set.of();
	}

	private static Set<Integer> resolveExactVersions(KtExpression expression) {
		final KtExpression unwrapped = unwrap(expression);

		if (unwrapped instanceof KtCallExpression callExpression) {
			return resolveConstantsIsCall(callExpression);
		}

		if (unwrapped instanceof KtBinaryExpression binaryExpression) {
			return resolveVersionEquality(binaryExpression);
		}

		return Set.of();
	}

	private static Set<Integer> resolveConstantsIsCall(KtCallExpression callExpression) {
		if (!(callExpression.getParent() instanceof KtQualifiedExpression qualifiedExpression)) {
			return Set.of();
		}

		if (!(qualifiedExpression.getReceiverExpression() instanceof KtNameReferenceExpression qualifier) || !"Constants".equals(qualifier.getReferencedName())) {
			return Set.of();
		}

		if (qualifiedExpression.getSelectorExpression() != callExpression) {
			return Set.of();
		}

		if (!(callExpression.getCalleeExpression() instanceof KtNameReferenceExpression selector) || !"is".equals(selector.getReferencedName())) {
			return Set.of();
		}

		if (callExpression.getValueArguments().size() != 1) {
			return Set.of();
		}

		final Integer packed = readPackedVersion(callExpression.getValueArguments().get(0).getArgumentExpression());
		return packed == null ? Set.of() : Set.of(packed);
	}

	private static Set<Integer> resolveVersionEquality(KtBinaryExpression binaryExpression) {
		if (binaryExpression.getOperationToken() != org.jetbrains.kotlin.lexer.KtTokens.EQEQ) {
			return Set.of();
		}

		final KtExpression left = unwrap(binaryExpression.getLeft());
		final KtExpression right = unwrap(binaryExpression.getRight());

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

	private static boolean isMinecraftVersionField(KtExpression expression) {
		if (!(expression instanceof KtQualifiedExpression qualifiedExpression)) {
			return false;
		}

		if (!(qualifiedExpression.getReceiverExpression() instanceof KtNameReferenceExpression qualifier) || !"Constants".equals(qualifier.getReferencedName())) {
			return false;
		}

		return qualifiedExpression.getSelectorExpression() instanceof KtNameReferenceExpression selector
				&& "MINECRAFT_VERSION".equals(selector.getReferencedName());
	}

	private static Integer readPackedVersion(KtExpression expression) {
		final KtExpression unwrapped = unwrap(expression);

		if (unwrapped == null) {
			return null;
		}

		try {
			return Integer.valueOf(unwrapped.getText());
		} catch (NumberFormatException ignored) {
			return null;
		}
	}

	private static KtExpression unwrap(KtExpression expression) {
		KtExpression current = expression;

		while (current instanceof KtParenthesizedExpression parenthesizedExpression) {
			current = parenthesizedExpression.getExpression();
		}

		return current;
	}

	private static Set<Integer> intersectWithJavaAnnotation(Set<Integer> current, PsiModifierListOwner owner) {
		if (owner == null) {
			return current;
		}

		final PsiAnnotation annotation = owner.getAnnotation(RequiresApiInspectionSupport.REQUIRES_API_FQN);

		if (annotation == null) {
			return current;
		}

		final Set<Integer> annotationVersions = new LinkedHashSet<>();
		final PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue("value");

		if (value instanceof com.intellij.psi.PsiArrayInitializerMemberValue arrayValue) {
			for (PsiAnnotationMemberValue initializer : arrayValue.getInitializers()) {
				addPsiVersion(initializer, annotationVersions);
			}
		} else if (value != null) {
			addPsiVersion(value, annotationVersions);
		}

		return intersect(current, annotationVersions);
	}

	private static Set<Integer> intersectWithKotlinAnnotations(Set<Integer> current, KtNamedFunction function) {
		if (function == null) {
			return current;
		}

		return intersect(current, readKotlinVersions(function.getAnnotationEntries()));
	}

	private static Set<Integer> intersectWithKotlinAnnotations(Set<Integer> current, KtClassOrObject klass) {
		if (klass == null) {
			return current;
		}

		return intersect(current, readKotlinVersions(klass.getAnnotationEntries()));
	}

	private static Set<Integer> readKotlinVersions(Iterable<KtAnnotationEntry> entries) {
		final Set<Integer> versions = new LinkedHashSet<>();

		for (KtAnnotationEntry entry : entries) {
			final String shortName = entry.getShortName() == null ? null : entry.getShortName().asString();

			if (!"RequiresApi".equals(shortName)) {
				continue;
			}

			for (ValueArgumentIterator argument : ValueArgumentIterator.of(entry)) {
				final KtExpression expression = argument.expression();

				if (expression == null) {
					continue;
				}

				final String text = expression.getText().replace("\"", "").replace("'", "");

				if (text.indexOf('.') > 0) {
					versions.add(RequiresApiInspectionSupport.packVersion(text));
				}
			}
		}

		return versions;
	}

	private static void addPsiVersion(PsiAnnotationMemberValue annotationValue, Set<Integer> versions) {
		if (annotationValue instanceof PsiLiteralExpression literalExpression && literalExpression.getValue() instanceof String stringValue) {
			versions.add(RequiresApiInspectionSupport.packVersion(stringValue));
		}
	}

	private static Set<Integer> intersect(Set<Integer> left, Set<Integer> right) {
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

	private record ValueArgumentIterator(KtExpression expression) {
		static Iterable<ValueArgumentIterator> of(KtAnnotationEntry entry) {
			final java.util.List<ValueArgumentIterator> arguments = new java.util.ArrayList<>();

			for (org.jetbrains.kotlin.psi.ValueArgument argument : entry.getValueArguments()) {
				arguments.add(new ValueArgumentIterator(argument.getArgumentExpression()));
			}

			return arguments;
		}
	}
}
