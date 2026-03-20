package ru.hollowhorizon.hollowengineloom.idea.inspection;

import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiModifierListOwner;

import org.jetbrains.kotlin.psi.KtAnnotationEntry;
import org.jetbrains.kotlin.psi.KtNamedFunction;

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

	static boolean hasKotlinSubscribeEvent(KtNamedFunction function) {
		for (KtAnnotationEntry entry : function.getAnnotationEntries()) {
			if (entry.getShortName() != null && "SubscribeEvent".equals(entry.getShortName().asString())) {
				return true;
			}
		}

		return false;
	}
}
