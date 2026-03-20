package ru.hollowhorizon.hollowengineloom.idea.inspection;

import javax.swing.Icon;

import com.intellij.codeInsight.daemon.LineMarkerInfo;
import com.intellij.codeInsight.daemon.LineMarkerProviderDescriptor;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiIdentifier;
import com.intellij.psi.PsiMethodCallExpression;
import com.intellij.psi.PsiReferenceExpression;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.psi.KtCallExpression;
import org.jetbrains.kotlin.psi.KtClassOrObject;
import org.jetbrains.kotlin.psi.KtNameReferenceExpression;
import org.jetbrains.uast.UCallExpression;
import org.jetbrains.uast.UastContextKt;

public final class HollowPacketLineMarkerProvider extends LineMarkerProviderDescriptor {
	@Override
	public @Nullable String getName() {
		return "HollowPacket";
	}

	@Override
	public @Nullable Icon getIcon() {
		return null;
	}

	@Override
	public @Nullable LineMarkerInfo<?> getLineMarkerInfo(@NotNull PsiElement element) {
		if (element instanceof PsiIdentifier identifier && identifier.getParent() instanceof PsiClass psiClass) {
			final HollowPacketSupport.PacketDirection direction = HollowPacketSupport.resolvePacketDirection(psiClass);
			return direction == null ? null : marker(identifier, HollowPacketSupport.iconFor(direction), HollowPacketSupport.buildDeclarationTooltip(direction));
		}

		if (element.getParent() instanceof KtClassOrObject klass && klass.getNameIdentifier() == element) {
			final HollowPacketSupport.PacketDirection direction = HollowPacketSupport.resolvePacketDirection(klass);
			return direction == null ? null : marker(element, HollowPacketSupport.iconFor(direction), HollowPacketSupport.buildDeclarationTooltip(direction));
		}

		if (element instanceof PsiIdentifier identifier
				&& identifier.getParent() instanceof PsiReferenceExpression referenceExpression
				&& referenceExpression.getParent() instanceof PsiMethodCallExpression callExpression) {
			final UCallExpression uCall = UastContextKt.toUElement(callExpression, UCallExpression.class);
			final HollowPacketSupport.PacketSend send = uCall == null ? null : HollowPacketSupport.resolveSend(uCall);
			return send == null ? null : marker(identifier, HollowPacketSupport.iconFor(send.sendDirection()), HollowPacketSupport.buildSendTooltip(send));
		}

		if (element instanceof KtNameReferenceExpression nameReference && nameReference.getParent() instanceof KtCallExpression callExpression) {
			final UCallExpression uCall = UastContextKt.toUElement(callExpression, UCallExpression.class);
			final HollowPacketSupport.PacketSend send = uCall == null ? null : HollowPacketSupport.resolveSend(uCall);
			return send == null ? null : marker(element, HollowPacketSupport.iconFor(send.sendDirection()), HollowPacketSupport.buildSendTooltip(send));
		}

		return null;
	}

	private static LineMarkerInfo<PsiElement> marker(PsiElement anchor, Icon icon, String tooltip) {
		return new LineMarkerInfo<>(
				anchor,
				anchor.getTextRange(),
				icon,
				elt -> tooltip,
				null,
				com.intellij.openapi.editor.markup.GutterIconRenderer.Alignment.LEFT,
				() -> tooltip
		);
	}
}
