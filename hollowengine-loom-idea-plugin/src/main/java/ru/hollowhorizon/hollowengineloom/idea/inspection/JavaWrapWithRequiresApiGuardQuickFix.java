package ru.hollowhorizon.hollowengineloom.idea.inspection;

import java.util.Set;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;

import org.jetbrains.annotations.NotNull;

public final class JavaWrapWithRequiresApiGuardQuickFix implements LocalQuickFix {
	private final Set<Integer> requiredVersions;

	public JavaWrapWithRequiresApiGuardQuickFix(Set<Integer> requiredVersions) {
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
		final PsiElement element = descriptor.getPsiElement();
		final PsiStatement statement = RequiresApiQuickFixSupport.findJavaStatement(element);

		if (statement == null) {
			return;
		}

		final PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
		final String guard = RequiresApiQuickFixSupport.buildGuardExpression(element, requiredVersions);
		final PsiStatement wrapped = factory.createStatementFromText("if (" + guard + ") {\n" + statement.getText() + "\n}", statement);
		final PsiElement replaced = statement.replace(wrapped);
		JavaCodeStyleManager.getInstance(project).shortenClassReferences(replaced);
	}
}
