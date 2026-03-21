package ru.hollowhorizon.hollowengineloom.idea.inspection;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.intellij.codeInsight.lookup.Lookup;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementPresentation;
import com.intellij.codeInsight.lookup.impl.LookupCellRenderer;
import com.intellij.codeInsight.lookup.impl.LookupImpl;
import com.intellij.psi.PsiElement;

final class KotlinLookupPresentationListener {
	private KotlinLookupPresentationListener() {
	}

	static void customize(Lookup lookup) {
		if (!(lookup instanceof LookupImpl lookupImpl) || !lookup.isCompletion() || !isKotlinLookup(lookup)) {
			return;
		}

		final Set<Integer> projectVersions = RequiresApiInspectionSupport.resolveProjectVersions(lookup.getPsiElement());

		if (projectVersions.isEmpty()) {
			return;
		}

		final Map<PsiElement, Set<Integer>> versionCache = new ConcurrentHashMap<>();
		lookupImpl.addPresentationCustomizer(new LookupCellRenderer.ItemPresentationCustomizer() {
			@Override
			public LookupElementPresentation customizePresentation(LookupElement item, LookupElementPresentation presentation) {
				return applyPresentationHint(item, projectVersions, versionCache, presentation);
			}

			@Override
			public javax.swing.Icon customizeEmptyIcon(javax.swing.Icon icon) {
				return icon;
			}
		});

		for (LookupElement item : lookupImpl.getItems()) {
			lookupImpl.cellRenderer.updateItemPresentation(item);
		}
	}

	static LookupElementPresentation applyPresentationHint(LookupElement item,
			Set<Integer> projectVersions,
			Map<PsiElement, Set<Integer>> versionCache,
			LookupElementPresentation fallbackPresentation) {
		final Set<Integer> requiredVersions = MultiversionCompletionContributor.resolveRequiredVersions(item, versionCache);

		if (requiredVersions.isEmpty() || RequiresApiInspectionSupport.isAvailableEverywhere(requiredVersions, projectVersions)) {
			return fallbackPresentation;
		}

		final LookupElementPresentation presentation = new LookupElementPresentation();
		item.renderElement(presentation);
		MultiversionCompletionContributor.appendTail(
				presentation,
				RequiresApiInspectionSupport.buildCompletionHint(requiredVersions, projectVersions)
		);
		return presentation;
	}

	private static boolean isKotlinLookup(Lookup lookup) {
		return lookup.getPsiFile() != null && "kotlin".equalsIgnoreCase(lookup.getPsiFile().getLanguage().getID());
	}
}
