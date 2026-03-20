package ru.hollowhorizon.hollowengineloom.idea.inspection;

import javax.swing.Icon;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor;
import com.intellij.icons.AllIcons;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethod;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.psi.KtNamedFunction;

public final class SubscribeEventLineMarkerProvider extends LineMarkerProviderDescriptor {
	@Override
	public @Nullable String getName() {
		return "SubscribeEvent handler";
	}

	@Override
	public @Nullable Icon getIcon() {
		return AllIcons.Actions.Lightning;
	}

	@Override
	public @Nullable LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
		if (element instanceof PsiIdentifier identifier && identifier.getParent() instanceof PsiMethod method && SubscribeEventSupport.isSubscribeEventHandler(method)) {
			return createMarker(identifier);
		}

		if (element.getParent() instanceof KtNamedFunction function
				&& function.getNameIdentifier() == element
				&& SubscribeEventSupport.isSubscribeEventHandler(function)) {
			return createMarker(element);
		}

		return null;
	}

	private static LineMarkerInfo<PsiElement> createMarker(PsiElement anchor) {
		return new LineMarkerInfo<>(
				anchor,
				anchor.getTextRange(),
				AllIcons.Actions.Lightning,
				elt -> "SubscribeEvent handler",
				null,
				com.intellij.openapi.editor.markup.GutterIconRenderer.Alignment.LEFT,
				() -> "SubscribeEvent handler"
		);
	}
}
