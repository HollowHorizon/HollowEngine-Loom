package ru.hollowhorizon.hollowengineloom.idea.inspection;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.util.PsiTreeUtil;

import org.jetbrains.kotlin.psi.KtAnnotationEntry;
import org.jetbrains.kotlin.psi.KtNamedFunction;
import org.jetbrains.kotlin.psi.KtParameter;

final class SubscribeEventSupport {
	private SubscribeEventSupport() {
	}

	static boolean isSubscribeEventHandler(PsiElement element) {
		if (element instanceof PsiModifierListOwner owner) {
			return RequiresApiInspectionSupport.hasSubscribeEvent(owner);
		}

		if (element instanceof KtNamedFunction function) {
			return hasKotlinSubscribeEvent(function);
		}

		return false;
	}

	static boolean isSubscribeEventParameter(PsiElement element) {
		if (element instanceof PsiParameter parameter && parameter.getDeclarationScope() instanceof PsiMethod method) {
			return isSubscribeEventHandler(method);
		}

		if (element instanceof KtParameter parameter) {
			final KtNamedFunction function = PsiTreeUtil.getParentOfType(parameter, KtNamedFunction.class);
			return function != null && isSubscribeEventHandler(function);
		}

		return false;
	}

	static boolean hasKotlinSubscribeEvent(KtNamedFunction function) {
		for (KtAnnotationEntry entry : function.getAnnotationEntries()) {
			if (entry.getShortName() != null && "SubscribeEvent".equals(entry.getShortName().asString())) {
				return true;
			}
		}

		return false;
	}
}
