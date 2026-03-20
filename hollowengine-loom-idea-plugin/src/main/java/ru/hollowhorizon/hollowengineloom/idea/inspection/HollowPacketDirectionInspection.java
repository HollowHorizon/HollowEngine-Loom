package ru.hollowhorizon.hollowengineloom.idea.inspection;

import com.intellij.codeInspection.LocalInspectionTool;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.psi.PsiElementVisitor;
import com.intellij.uast.UastHintedVisitorAdapter;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.uast.UCallExpression;
import org.jetbrains.uast.visitor.AbstractUastNonRecursiveVisitor;

public final class HollowPacketDirectionInspection extends LocalInspectionTool {
	@Override
	public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
		return UastHintedVisitorAdapter.create(holder.getFile().getLanguage(), new AbstractUastNonRecursiveVisitor() {
			@Override
			public boolean visitCallExpression(@NotNull UCallExpression node) {
				final HollowPacketSupport.PacketSend send = HollowPacketSupport.resolveSend(node);

				if (send != null && HollowPacketSupport.isDirectionMismatch(send) && node.getSourcePsi() != null) {
					holder.registerProblem(node.getSourcePsi(), HollowPacketSupport.buildDirectionMismatchMessage(send));
				}

				return true;
			}
		}, new Class[] { UCallExpression.class });
	}
}
