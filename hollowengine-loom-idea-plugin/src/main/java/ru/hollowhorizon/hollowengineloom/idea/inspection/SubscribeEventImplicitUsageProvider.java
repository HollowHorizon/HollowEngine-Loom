package ru.hollowhorizon.hollowengineloom.idea.inspection;

import com.intellij.codeInsight.daemon.ImplicitUsageProvider;
import com.intellij.psi.PsiElement;

public final class SubscribeEventImplicitUsageProvider implements ImplicitUsageProvider {
	@Override
	public boolean isImplicitUsage(PsiElement element) {
		return SubscribeEventSupport.isSubscribeEventHandler(element) || SubscribeEventSupport.isSubscribeEventParameter(element);
	}

	@Override
	public boolean isImplicitRead(PsiElement element) {
		return SubscribeEventSupport.isSubscribeEventParameter(element);
	}

	@Override
	public boolean isImplicitWrite(PsiElement element) {
		return false;
	}
}
