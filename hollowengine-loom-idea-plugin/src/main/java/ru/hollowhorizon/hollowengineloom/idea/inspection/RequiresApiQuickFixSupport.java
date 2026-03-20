package ru.hollowhorizon.hollowengineloom.idea.inspection;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.util.PsiTreeUtil;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.psi.KtBlockExpression;
import org.jetbrains.kotlin.psi.KtExpression;

final class RequiresApiQuickFixSupport {
	private RequiresApiQuickFixSupport() {
	}

	static String resolveConstantsClass(PsiElement context) {
		final Module module = ModuleUtilCore.findModuleForPsiElement(context);
		final IdeaMultiversionMetadata metadata = IdeaMetadataResolver.resolve(module);
		return metadata != null && metadata.constantsClass != null && !metadata.constantsClass.isBlank() ? metadata.constantsClass : "Constants";
	}

	static String buildGuardExpression(PsiElement context, Set<Integer> requiredVersions) {
		final String constantsClass = resolveConstantsClass(context);
		return requiredVersions.stream()
				.sorted()
				.map(version -> constantsClass + ".is(" + constantsClass + "." + versionFieldName(version) + ")")
				.collect(Collectors.joining(" || "));
	}

	static String buildJavaAnnotationText(Set<Integer> requiredVersions) {
		return "@%s({%s})".formatted(
				HollowEngineConstants.REQUIRES_API_FQN,
				requiredVersions.stream()
						.sorted()
						.map(RequiresApiInspectionSupport::formatVersion)
						.map(version -> "\"" + version + "\"")
						.collect(Collectors.joining(", "))
		);
	}

	static String buildKotlinAnnotationText(Set<Integer> requiredVersions) {
		return "@%s(%s)".formatted(
				HollowEngineConstants.REQUIRES_API_FQN,
				requiredVersions.stream()
						.sorted()
						.map(RequiresApiInspectionSupport::formatVersion)
						.map(version -> "\"" + version + "\"")
						.collect(Collectors.joining(", "))
		);
	}

	static Set<Integer> mergeWithExisting(Set<Integer> requiredVersions, Set<Integer> existingVersions) {
		final Set<Integer> merged = new LinkedHashSet<>(existingVersions);
		merged.addAll(requiredVersions);
		return merged;
	}

	static @Nullable PsiStatement findJavaStatement(PsiElement element) {
		return PsiTreeUtil.getParentOfType(element, PsiStatement.class, false);
	}

	static @Nullable KtExpression findKotlinStatement(PsiElement element) {
		KtExpression expression = PsiTreeUtil.getParentOfType(element, KtExpression.class, false);

		while (expression != null && !(expression.getParent() instanceof KtBlockExpression)) {
			expression = PsiTreeUtil.getParentOfType(expression, KtExpression.class, true);
		}

		return expression;
	}

	private static String versionFieldName(int packedVersion) {
		return "V" + RequiresApiInspectionSupport.formatVersion(packedVersion).replace('.', '_');
	}
}
