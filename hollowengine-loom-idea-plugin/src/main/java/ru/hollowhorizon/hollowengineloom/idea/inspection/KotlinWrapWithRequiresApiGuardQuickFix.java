package ru.hollowhorizon.hollowengineloom.idea.inspection;

import java.util.Set;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.project.Project;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.psi.KtExpression;
import org.jetbrains.kotlin.psi.KtPsiFactory;

public final class KotlinWrapWithRequiresApiGuardQuickFix implements LocalQuickFix {
	private final Set<Integer> requiredVersions;

	public KotlinWrapWithRequiresApiGuardQuickFix(Set<Integer> requiredVersions) {
		this.requiredVersions = Set.copyOf(requiredVersions);
	}

	@Override
	public @NotNull String getName() {
		return "Wrap in matching Constants guard";
	}

	@Override
	public @NotNull String getFamilyName() {
		return "HollowEngine Loom";
	}

	@Override
	public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
		final KtExpression expression = RequiresApiQuickFixSupport.findKotlinStatement(descriptor.getPsiElement());

		if (expression == null) {
			return;
		}

		final String guard = RequiresApiQuickFixSupport.buildKotlinGuardExpression(expression, requiredVersions);
		final KtPsiFactory factory = new KtPsiFactory(project);
		final KtExpression wrapped = factory.createExpression("if (" + guard + ") {\n" + expression.getText() + "\n}");
		expression.replace(wrapped);
	}
}
