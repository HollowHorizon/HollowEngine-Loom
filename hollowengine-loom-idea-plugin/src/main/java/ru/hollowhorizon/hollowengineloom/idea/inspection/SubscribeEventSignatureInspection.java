package ru.hollowhorizon.hollowengineloom.idea.inspection;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.psi.PsiMethod;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.kotlin.psi.KtNamedFunction;

public final class SubscribeEventSignatureInspection extends LocalInspectionTool {
	@Override
	public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
		return new PsiElementVisitor() {
			@Override
			public void visitElement(@NotNull PsiElement element) {
				if (element instanceof PsiMethod method && SubscribeEventSupport.isSubscribeEventHandler(method) && method.getParameterList().getParametersCount() > 1) {
					holder.registerProblem(method.getParameterList(), "SubscribeEvent handlers can declare at most one parameter.");
				} else if (element instanceof KtNamedFunction function && SubscribeEventSupport.isSubscribeEventHandler(function) && function.getValueParameters().size() > 1) {
					holder.registerProblem(function.getValueParameterList() != null ? function.getValueParameterList() : function, "SubscribeEvent handlers can declare at most one parameter.");
				}

				super.visitElement(element);
			}
		};
	}
}
