package ru.hollowhorizon.hollowengineloom.idea.inspection;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionResult;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.completion.InsertHandler;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.codeInsight.lookup.LookupElementDecorator;
import com.intellij.codeInsight.lookup.LookupManager;
import com.intellij.codeInsight.lookup.LookupElementPresentation;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;

import org.jetbrains.annotations.NotNull;

public final class MultiversionCompletionContributor extends CompletionContributor {
	@Override
	public void fillCompletionVariants(@NotNull CompletionParameters parameters, @NotNull CompletionResultSet result) {
		final boolean kotlin = parameters.getOriginalFile().getLanguage().getID().equalsIgnoreCase("kotlin");

		if (kotlin) {
			result.runRemainingContributors(parameters, result::passResult, true);
			customizeKotlinLookup(parameters);
			return;
		}

		final Set<Integer> projectVersions = RequiresApiInspectionSupport.resolveProjectVersions(parameters.getPosition());
		final Map<PsiElement, Set<Integer>> versionCache = new ConcurrentHashMap<>();

		result.runRemainingContributors(parameters, completionResult -> {
			final LookupElement decorated = decorate(completionResult.getLookupElement(), projectVersions, versionCache, false);
			result.passResult(CompletionResult.wrap(decorated, completionResult.getPrefixMatcher(), completionResult.getSorter()));
		}, true);
	}

	private static void customizeKotlinLookup(CompletionParameters parameters) {
		final var lookup = LookupManager.getActiveLookup(parameters.getEditor());

		if (lookup != null) {
			KotlinLookupPresentationListener.customize(lookup);
			return;
		}

		ApplicationManager.getApplication().invokeLater(() -> {
			final var delayedLookup = LookupManager.getActiveLookup(parameters.getEditor());

			if (delayedLookup != null) {
				KotlinLookupPresentationListener.customize(delayedLookup);
			}
		});
	}

	static LookupElement decorate(LookupElement lookupElement, Set<Integer> projectVersions, Map<PsiElement, Set<Integer>> versionCache, boolean kotlin) {
		final Set<Integer> requiredVersions = resolveRequiredVersions(lookupElement, versionCache);

		if (requiredVersions.isEmpty() || RequiresApiInspectionSupport.isAvailableEverywhere(requiredVersions, projectVersions)) {
			return lookupElement;
		}

		final String versionsText = RequiresApiInspectionSupport.buildCompletionHint(requiredVersions, projectVersions);
		final LookupElement rebuilt = kotlin ? null : rebuildLookupElement(lookupElement, versionsText);

		if (rebuilt != null) {
			return rebuilt;
		}

		return LookupElementDecorator.withRenderer(lookupElement, new com.intellij.codeInsight.lookup.LookupElementRenderer<>() {
			@Override
			public void renderElement(LookupElementDecorator<LookupElement> item, LookupElementPresentation presentation) {
				item.getDelegate().renderElement(presentation);
				appendTail(presentation, versionsText);
			}
		});
	}

	private static LookupElement rebuildLookupElement(LookupElement lookupElement, String versionsText) {
		if (lookupElement instanceof LookupElementBuilder builder) {
			return builder.appendTailText(" in " + versionsText, true);
		}

		if (!(lookupElement instanceof LookupElementDecorator<?> decorator)) {
			return null;
		}

		final LookupElement delegate = (LookupElement) decorator.getDelegate();

		final LookupElement rebuiltDelegate = rebuildLookupElement(delegate, versionsText);

		if (rebuiltDelegate == null) {
			return null;
		}

		final InsertHandler<LookupElement> delegateInsertHandler = castInsertHandler(decorator.getDelegateInsertHandler());

		if (delegateInsertHandler != null) {
			return LookupElementDecorator.withDelegateInsertHandler(rebuiltDelegate, delegateInsertHandler);
		}

		return rebuiltDelegate;
	}

	@SuppressWarnings("unchecked")
	private static InsertHandler<LookupElement> castInsertHandler(InsertHandler<?> handler) {
		return (InsertHandler<LookupElement>) handler;
	}

	static Set<Integer> resolveRequiredVersions(LookupElement lookupElement, Map<PsiElement, Set<Integer>> versionCache) {
		return ReadAction.compute(() -> resolveCandidates(lookupElement)
				.map(candidate -> versionCache.computeIfAbsent(candidate, RequiresApiInspectionSupport::resolveSymbolVersions))
				.filter(versions -> !versions.isEmpty())
				.findFirst()
				.orElse(Set.of()));
	}

	static Stream<PsiElement> resolveCandidates(LookupElement lookupElement) {
		final PsiElement psiElement = lookupElement.getPsiElement();
		final Object delegate = lookupElement.getObject();
		final PsiElement delegateElement = delegate instanceof PsiElement element ? element : null;
		final PsiElement psiNavigation = psiElement != null ? psiElement.getNavigationElement() : null;
		final PsiElement delegateNavigation = delegateElement != null ? delegateElement.getNavigationElement() : null;

		return Stream.of(psiElement, psiNavigation, delegateElement, delegateNavigation)
				.filter(element -> element != null)
				.distinct();
	}

	static void appendTail(LookupElementPresentation presentation, String versionsText) {
		String existingTail = presentation.getTailText();
		if (existingTail == null) existingTail = "";

		String prefix = " in ";
		String newFullTail = existingTail + prefix + versionsText;
		int startOfVersion = existingTail.length() + prefix.length();

		presentation.setTailText(newFullTail, true);
		presentation.decorateTailItemTextRange(
				new TextRange(startOfVersion, newFullTail.length()),
				LookupElementPresentation.LookupItemDecoration.HIGHLIGHT_MATCHED
		);
	}
}
