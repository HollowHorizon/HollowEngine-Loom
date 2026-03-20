package ru.hollowhorizon.hollowengineloom.idea.inspection;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementDecorator;
import com.intellij.codeInsight.lookup.LookupElementPresentation;
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.editor.colors.EditorColorsScheme;
import com.intellij.openapi.editor.markup.TextAttributes;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiModifierListOwner;

import org.jetbrains.annotations.NotNull;

public final class MultiversionCompletionContributor extends CompletionContributor {
	@Override
	public void fillCompletionVariants(@NotNull CompletionParameters parameters, @NotNull CompletionResultSet result) {
		final Set<Integer> projectVersions = RequiresApiInspectionSupport.resolveProjectVersions(parameters.getPosition());
		final Map<PsiModifierListOwner, Set<Integer>> versionCache = new IdentityHashMap<>();

		result.runRemainingContributors(parameters, completionResult -> {
			result.passResult(completionResult.withLookupElement(decorate(completionResult.getLookupElement(), projectVersions, versionCache)));
		}, true);
	}

	private static LookupElement decorate(LookupElement lookupElement, Set<Integer> projectVersions, Map<PsiModifierListOwner, Set<Integer>> versionCache) {
		final PsiElement psiElement = lookupElement.getPsiElement();

		if (!(psiElement instanceof PsiModifierListOwner owner)) {
			return lookupElement;
		}

		final Set<Integer> requiredVersions = versionCache.computeIfAbsent(owner, RequiresApiInspectionSupport::resolveSymbolVersions);

		if (requiredVersions.isEmpty()) {
			return lookupElement;
		}

		if (RequiresApiInspectionSupport.isAvailableEverywhere(requiredVersions, projectVersions)) {
			return lookupElement;
		}

		return new VersionHintLookupElement(lookupElement, RequiresApiInspectionSupport.buildCompletionHint(requiredVersions, projectVersions));
	}

	private static final class VersionHintLookupElement extends LookupElementDecorator<LookupElement> {
		private final String versionsText;
		private final TextAttributes color;

		private VersionHintLookupElement(LookupElement delegate, String tailText) {
			super(delegate);
			this.versionsText = tailText;
			EditorColorsScheme scheme = EditorColorsManager.getInstance().getGlobalScheme();
			this.color = scheme.getAttributes(DefaultLanguageHighlighterColors.KEYWORD);
		}

		@Override
		public void renderElement(LookupElementPresentation presentation) {
			super.renderElement(presentation);

			String existingTail = presentation.getTailText();
			if (existingTail == null) existingTail = "";

			String prefix = " in ";


			int startOfVersion = existingTail.length() + prefix.length();
			String newFullTail = existingTail + prefix + versionsText;

			presentation.setTailText(newFullTail, true);

			presentation.decorateTailItemTextRange(
					new TextRange(startOfVersion, newFullTail.length()),
					LookupElementPresentation.LookupItemDecoration.HIGHLIGHT_MATCHED
			);
		}
	}
}
