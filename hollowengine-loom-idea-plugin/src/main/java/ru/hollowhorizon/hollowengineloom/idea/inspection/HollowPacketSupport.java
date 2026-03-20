package ru.hollowhorizon.hollowengineloom.idea.inspection;

import javax.swing.Icon;

import com.intellij.icons.AllIcons;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiEnumConstant;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.PsiReferenceExpression;
import com.intellij.psi.PsiType;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.util.PsiTypesUtil;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.psi.KtAnnotationEntry;
import org.jetbrains.kotlin.psi.KtClassOrObject;
import org.jetbrains.uast.UCallExpression;
import org.jetbrains.uast.UExpression;

final class HollowPacketSupport {
	private HollowPacketSupport() {
	}

	enum PacketDirection {
		TO_CLIENT,
		TO_SERVER,
		ANY
	}

	enum SendDirection {
		TO_CLIENT,
		TO_SERVER
	}

	record PacketSend(PsiClass packetClass, PacketDirection packetDirection, SendDirection sendDirection) {
	}

	static @Nullable PacketDirection resolvePacketDirection(PsiElement element) {
		if (element instanceof KtClassOrObject klass) {
			return isKotlinHollowPacketClass(klass) ? readDirection(klass) != null ? readDirection(klass) : PacketDirection.ANY : null;
		}

		final PsiClass packetClass = resolvePacketClass(element);
		return packetClass == null ? null : resolvePacketDirection(packetClass);
	}

	static @Nullable PacketDirection resolvePacketDirection(PsiClass packetClass) {
		if (!isHollowPacketClass(packetClass)) {
			return null;
		}

		final PacketDirection direct = readDirection(packetClass);

		if (direct != null) {
			return direct;
		}

		final PsiElement navigation = packetClass.getNavigationElement();

		if (navigation instanceof KtClassOrObject klass) {
			final PacketDirection kotlinDirection = readDirection(klass);
			return kotlinDirection != null ? kotlinDirection : PacketDirection.ANY;
		}

		if (navigation instanceof PsiClass navigationClass && navigationClass != packetClass) {
			final PacketDirection nested = readDirection(navigationClass);
			return nested != null ? nested : PacketDirection.ANY;
		}

		return PacketDirection.ANY;
	}

	static @Nullable PacketSend resolveSend(UCallExpression callExpression) {
		final SendDirection sendDirection = resolveSendDirection(callExpression);

		if (sendDirection == null) {
			return null;
		}

		final UExpression receiver = callExpression.getReceiver();
		final PsiClass packetClass = receiver == null ? null : resolvePsiClass(receiver.getExpressionType());

		if (packetClass == null || !isHollowPacketClass(packetClass)) {
			return null;
		}

		final PacketDirection packetDirection = resolvePacketDirection(packetClass);
		return packetDirection == null ? null : new PacketSend(packetClass, packetDirection, sendDirection);
	}

	static boolean isDirectionMismatch(PacketSend send) {
		return switch (send.packetDirection()) {
		case TO_CLIENT -> send.sendDirection() == SendDirection.TO_SERVER;
		case TO_SERVER -> send.sendDirection() == SendDirection.TO_CLIENT;
		case ANY -> false;
		};
	}

	static String buildDirectionMismatchMessage(PacketSend send) {
		return "This packet is declared for " + format(send.packetDirection()) + " but this send call goes to " + format(send.sendDirection()) + ".";
	}

	static String buildDeclarationTooltip(PacketDirection direction) {
		return "HollowPacket declaration: " + format(direction);
	}

	static String buildSendTooltip(PacketSend send) {
		return "HollowPacket send: " + format(send.sendDirection()) + " (" + send.packetClass().getName() + " is " + format(send.packetDirection()) + ")";
	}

	static Icon iconFor(PacketDirection direction) {
		return switch (direction) {
		case TO_CLIENT -> AllIcons.Actions.Back;
		case TO_SERVER -> AllIcons.Actions.Forward;
		case ANY -> AllIcons.Nodes.Plugin;
		};
	}

	static Icon iconFor(SendDirection direction) {
		return direction == SendDirection.TO_CLIENT ? AllIcons.Actions.Back : AllIcons.Actions.Forward;
	}

	static boolean isHollowPacketDeclaration(PsiElement element) {
		return resolvePacketClass(element) != null;
	}

	private static @Nullable SendDirection resolveSendDirection(UCallExpression callExpression) {
		final String methodName = callExpression.getMethodName();

		if (methodName == null) {
			return null;
		}

		return switch (methodName) {
		case "send" -> callExpression.getValueArgumentCount() == 0 ? SendDirection.TO_SERVER : SendDirection.TO_CLIENT;
		case "sendTrackingEntity", "sendTrackingEntityAndSelf", "sendAllInDimension" -> SendDirection.TO_CLIENT;
		default -> null;
		};
	}

	private static @Nullable PsiClass resolvePacketClass(PsiElement element) {
		if (element instanceof PsiClass psiClass && isHollowPacketClass(psiClass)) {
			return psiClass;
		}

		if (element instanceof PsiModifierListOwner owner) {
			final PsiElement navigation = owner.getNavigationElement();

			if (navigation instanceof PsiClass navigationClass && isHollowPacketClass(navigationClass)) {
				return navigationClass;
			}
		}

		return null;
	}

	private static boolean isHollowPacketClass(PsiClass psiClass) {
		return psiClass != null && (HollowEngineConstants.HOLLOW_PACKET_FQN.equals(psiClass.getQualifiedName())
				|| InheritanceUtil.isInheritor(psiClass, HollowEngineConstants.HOLLOW_PACKET_FQN));
	}

	private static @Nullable PsiClass resolvePsiClass(PsiType type) {
		return type == null ? null : PsiTypesUtil.getPsiClass(type);
	}

	private static @Nullable PacketDirection readDirection(PsiModifierListOwner owner) {
		final PsiAnnotation annotation = owner.getAnnotation(HollowEngineConstants.HOLLOW_PACKET_HANDLER_FQN);

		if (annotation == null) {
			return null;
		}

		final PsiAnnotationMemberValue value = annotation.findAttributeValue("toTarget");

		if (value == null) {
			return PacketDirection.ANY;
		}

		if (value instanceof PsiReferenceExpression referenceExpression) {
			final PsiElement resolved = referenceExpression.resolve();

			if (resolved instanceof PsiEnumConstant enumConstant) {
				return parseDirection(enumConstant.getName());
			}

			return parseDirection(referenceExpression.getReferenceName());
		}

		return parseDirection(value.getText());
	}

	private static @Nullable PacketDirection readDirection(KtClassOrObject klass) {
		for (KtAnnotationEntry entry : klass.getAnnotationEntries()) {
			if (entry.getShortName() != null && "HollowPacketHandler".equals(entry.getShortName().asString())) {
				if (entry.getValueArguments().isEmpty()) {
					return PacketDirection.ANY;
				}

				return parseDirection(entry.getValueArguments().get(0).getArgumentExpression() != null ? entry.getValueArguments().get(0).getArgumentExpression().getText() : null);
			}
		}

		return null;
	}

	private static boolean isKotlinHollowPacketClass(KtClassOrObject klass) {
		return klass.getSuperTypeListEntries().stream().anyMatch(entry -> entry.getText().contains("HollowPacket"));
	}

	private static PacketDirection parseDirection(String text) {
		if (text != null && text.contains("TO_CLIENT")) {
			return PacketDirection.TO_CLIENT;
		}

		if (text != null && text.contains("TO_SERVER")) {
			return PacketDirection.TO_SERVER;
		}

		return PacketDirection.ANY;
	}

	private static String format(PacketDirection direction) {
		return switch (direction) {
		case TO_CLIENT -> "client";
		case TO_SERVER -> "server";
		case ANY -> "any side";
		};
	}

	private static String format(SendDirection direction) {
		return direction == SendDirection.TO_CLIENT ? "client" : "server";
	}
}
