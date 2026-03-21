package ru.hollowhorizon.hollowengineloom.idea.inspection;

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementPresentation;
import com.intellij.psi.PsiElement;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class KotlinCompletionContributorTest extends BasePlatformTestCase {
	public void testCompletionTailIncludesVersionsInKotlinFile() {
		myFixture.addFileToProject("multiversion/api/RequiresApi.java", """
				package multiversion.api;
				public @interface RequiresApi {
					String[] value();
				}
				""");
		myFixture.addFileToProject("sample/Api.java", """
				package sample;

				import multiversion.api.RequiresApi;

				public class Api {
					@RequiresApi({"1.21.1"})
					public static void onlyNew() {
					}

					public static void alwaysAvailable() {
					}
				}
				""");

		myFixture.configureByText("Use.kt", """
				import sample.Api

				fun test() {
				    Api.<caret>
				}
				""");

		LookupElement[] elements = myFixture.completeBasic();
		assertNotNull(elements);

		LookupElement onlyNew = null;

		for (LookupElement element : elements) {
			if ("onlyNew".equals(element.getLookupString())) {
				onlyNew = element;
				break;
			}
		}

		assertNotNull("Expected onlyNew completion item", onlyNew);
		PsiElement psiElement = onlyNew.getPsiElement();
		Object object = onlyNew.getObject();
		Set<Integer> requiredVersions = MultiversionCompletionContributor.resolveRequiredVersions(onlyNew, new ConcurrentHashMap<>());
		LookupElementPresentation presentation = KotlinLookupPresentationListener.applyPresentationHint(
				onlyNew,
				Set.of(1201, 1211),
				new ConcurrentHashMap<>(),
				new LookupElementPresentation()
		);
		String diagnostic = "requiredVersions=" + requiredVersions
				+ ", lookupClass=" + onlyNew.getClass().getName()
				+ ", rawTail=" + presentation.getTailText()
				+ ", psi=" + (psiElement == null ? "null" : psiElement.getClass().getName() + " -> " + RequiresApiInspectionSupport.resolveSymbolVersions(psiElement))
				+ ", object=" + (object == null ? "null" : object.getClass().getName())
				+ ", objectVersions=" + (object instanceof PsiElement element ? RequiresApiInspectionSupport.resolveSymbolVersions(element) : "n/a");
		assertTrue(diagnostic, requiredVersions.contains(1211));
		assertTrue(diagnostic, presentation.getTailText() != null && presentation.getTailText().contains("1.21.1"));
	}
}
