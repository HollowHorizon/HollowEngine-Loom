package ru.hollowhorizon.hollowengineloom.idea.inspection;

import java.util.Set;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.util.PsiTreeUtil;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.psi.KtAnnotationEntry;
import org.jetbrains.kotlin.psi.KtNamedFunction;
import org.jetbrains.kotlin.psi.KtPsiFactory;

public final class KotlinAnnotateWithRequiresApiQuickFix implements LocalQuickFix {
	private final Set<Integer> requiredVersions;

	public KotlinAnnotateWithRequiresApiQuickFix(Set<Integer> requiredVersions) {
		this.requiredVersions = Set.copyOf(requiredVersions);
	}

	@Override
	public @NotNull String getName() {
		return "Annotate enclosing declaration with @RequiresApi";
	}

	@Override
	public @NotNull String getFamilyName() {
		return "HollowEngine Loom";
	}

	@Override
	public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
		final KtNamedFunction function = PsiTreeUtil.getParentOfType(descriptor.getPsiElement(), KtNamedFunction.class, false);

		if (function == null) {
			return;
		}

		final KtPsiFactory factory = new KtPsiFactory(project);
		final KtAnnotationEntry annotationEntry = factory.createAnnotationEntry(RequiresApiQuickFixSupport.buildKotlinAnnotationText(requiredVersions));
		final KtAnnotationEntry existing = function.getAnnotationEntries().stream()
				.filter(entry -> entry.getShortName() != null && "RequiresApi".equals(entry.getShortName().asString()))
				.findFirst()
				.orElse(null);

		if (existing != null) {
			existing.replace(annotationEntry);
		} else {
			function.addAnnotationEntry(annotationEntry);
		}
	}
}
