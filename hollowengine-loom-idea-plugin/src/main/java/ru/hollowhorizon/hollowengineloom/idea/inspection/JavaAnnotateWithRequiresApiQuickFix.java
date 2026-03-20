package ru.hollowhorizon.hollowengineloom.idea.inspection;

import java.util.Set;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.util.PsiTreeUtil;

import org.jetbrains.annotations.NotNull;

public final class JavaAnnotateWithRequiresApiQuickFix implements LocalQuickFix {
	private final Set<Integer> requiredVersions;

	public JavaAnnotateWithRequiresApiQuickFix(Set<Integer> requiredVersions) {
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
		final PsiModifierListOwner owner = PsiTreeUtil.getParentOfType(descriptor.getPsiElement(), PsiMethod.class, false);

		if (owner == null) {
			return;
		}

		final Set<Integer> mergedVersions = RequiresApiQuickFixSupport.mergeWithExisting(requiredVersions, RequiresApiInspectionSupport.resolveDirectRequiresApi(owner));
		final PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
		final PsiAnnotation annotation = factory.createAnnotationFromText(RequiresApiQuickFixSupport.buildJavaAnnotationText(mergedVersions), owner);
		final PsiModifierList modifierList = owner.getModifierList();

		if (modifierList == null) {
			return;
		}

		final PsiAnnotation existing = owner.getAnnotation(HollowEngineConstants.REQUIRES_API_FQN);

		if (existing != null) {
			existing.replace(annotation);
		} else {
			modifierList.addBefore(annotation, modifierList.getFirstChild());
		}
	}
}
