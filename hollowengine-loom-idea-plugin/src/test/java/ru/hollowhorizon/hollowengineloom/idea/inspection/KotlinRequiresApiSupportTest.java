package ru.hollowhorizon.hollowengineloom.idea.inspection;

import java.util.List;
import java.util.Set;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import org.jetbrains.kotlin.psi.KtCallExpression;
import org.jetbrains.kotlin.psi.KtFile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class KotlinRequiresApiSupportTest extends BasePlatformTestCase {
	public void testResolveSymbolVersionsFromKotlinAnnotatedDeclarationInSameFile() {
		myFixture.addFileToProject("multiversion/api/RequiresApi.java", """
				package multiversion.api;
				public @interface RequiresApi {
					String[] value();
				}
				""");

		KtFile file = (KtFile) myFixture.configureByText("Test.kt", """
				import multiversion.api.RequiresApi

				@RequiresApi("1.20.1")
				fun only1201() {
				}

				fun test() {
				    only1201()
				}
				""");

		KtCallExpression callExpression = PsiTreeUtil.findChildOfType(file, KtCallExpression.class);
		assertNotNull(callExpression);
		assertNotNull(callExpression.getCalleeExpression());

		PsiElement resolved = null;

		for (var reference : callExpression.getCalleeExpression().getReferences()) {
			resolved = reference.resolve();

			if (resolved != null) {
				break;
			}
		}

		assertNotNull(resolved);
		Set<Integer> versions = RequiresApiInspectionSupport.resolveSymbolVersions(resolved);
		assertEquals(Set.of(1201), versions);
	}

	public void testEvaluateConstantsComparisonCondition() {
		KtFile file = (KtFile) myFixture.configureByText("Condition.kt", """
				object Constants {
				    const val V1_20_1 = 1201
				    const val V1_21_1 = 1211
				    const val MINECRAFT = 1211
				}

				fun test() {
				    if (Constants.MINECRAFT == Constants.V1_20_1) {
				    }
				}
				""");

		var binaryExpression = PsiTreeUtil.findChildOfType(file, org.jetbrains.kotlin.psi.KtBinaryExpression.class);
		assertNotNull(binaryExpression);
		var evaluation = KotlinVersionConditionEvaluator.evaluate(binaryExpression, Set.of(1201, 1211));
		assertNotNull(evaluation);
		assertEquals(Set.of(1201), evaluation.trueVersions());
		assertEquals(Set.of(1211), evaluation.falseVersions());
	}

	public void testElseIfConstantsComparisonsSuppressInspectionError() {
		myFixture.enableInspections(new KotlinRequiresApiGuardInspection());
		configureMultiversionProject();
		myFixture.addFileToProject("com/example/Constants.java", """
				package com.example;

				public final class Constants {
					public static final int V1_20_1 = 1201;
					public static final int V1_21_1 = 1211;
					public static final int MINECRAFT = 1211;
				}
				""");
		myFixture.addFileToProject("com/example/Api.kt", """
				package com.example

				import multiversion.api.RequiresApi

				object Api {
				    @RequiresApi("1.21.1")
				    fun onlyNew() {
				    }

				    @RequiresApi("1.20.1")
				    fun onlyOld() {
				    }
				}
				""");

		myFixture.configureByText("Use.kt", """
				import com.example.Api
				import com.example.Constants

				fun test() {
				    if (Constants.MINECRAFT == Constants.V1_21_1) {
				        Api.onlyNew()
				    } else if (Constants.MINECRAFT == Constants.V1_20_1) {
				        Api.onlyOld()
				    }
				}
				""");

		assertNoRequiresApiErrors(myFixture.doHighlighting());
	}

	public void testPlaygroundStyleCommonDemoReportsOnlyUnguardedOldApiUsage() {
		myFixture.enableInspections(new KotlinRequiresApiGuardInspection());
		configureMultiversionProject();
		myFixture.addFileToProject("com/example/Constants.java", """
				package com.example;

				public final class Constants {
					public static final int V1_20_1 = 1201;
					public static final int V1_21_1 = 1211;
					public static final int MINECRAFT = 1211;
				}
				""");
		myFixture.addFileToProject("com/mojang/blaze3d/systems/RenderSystem.java", """
				package com.mojang.blaze3d.systems;

				import multiversion.api.RequiresApi;
				import java.util.function.IntSupplier;

				public final class RenderSystem {
					@RequiresApi({"1.20.1"})
					public static void assertInInitPhase() {
					}

					@RequiresApi({"1.20.1"})
					public static void glBindBuffer(int target, IntSupplier supplier) {
					}

					@RequiresApi({"1.21.1"})
					public static void glBindBuffer(int target, int value) {
					}
				}
				""");

		myFixture.configureByText("CommonDemo.kt", """
				import com.example.Constants
				import com.mojang.blaze3d.systems.RenderSystem
				import multiversion.api.RequiresApi
				import java.util.function.IntSupplier

				object CommonDemo {
				    fun init() {
				        if (Constants.MINECRAFT == Constants.V1_21_1) {
				            only1_21_1()
				        } else if (Constants.MINECRAFT == Constants.V1_20_1) {
				            only1_20_1()
				            RenderSystem.assertInInitPhase()
				        }
				        only1_20_1()

				        if (Constants.MINECRAFT == Constants.V1_20_1) {
				            RenderSystem.assertInInitPhase()
				        }
				        RenderSystem.assertInInitPhase()
				    }

				    @RequiresApi("1.20.1")
				    fun only1_20_1() {
				        RenderSystem.glBindBuffer(0, IntSupplier { 123 })
				    }

				    @RequiresApi("1.21.1")
				    fun only1_21_1() {
				        RenderSystem.glBindBuffer(0, 123)
				    }
				}
				""");

		List<HighlightInfo> highlights = myFixture.doHighlighting();
		long guardedErrors = highlights.stream()
				.filter(info -> info.getSeverity() == HighlightSeverity.ERROR)
				.filter(info -> info.getDescription() != null && info.getDescription().contains("version-gated by @RequiresApi"))
				.filter(info -> info.getStartOffset() < 260)
				.count();
		assertEquals(highlights.toString(), 0L, guardedErrors);
	}

	public void testUserDefinedRequiresApiResolvesVersionsInKotlinMultiversionProject() {
		configureMultiversionProject();

		myFixture.configureByText("Test.kt", """
				import multiversion.api.RequiresApi

				@RequiresApi("1.21.1")
				fun onlyNew() {
				}

				fun test() {
				    onlyNew()
				}
				""");

		KtFile file = (KtFile) myFixture.getFile();
		KtCallExpression callExpression = PsiTreeUtil.findChildOfType(file, KtCallExpression.class);
		assertNotNull(callExpression);
		assertNotNull(callExpression.getCalleeExpression());

		PsiElement resolved = null;

		for (var reference : callExpression.getCalleeExpression().getReferences()) {
			resolved = reference.resolve();

			if (resolved != null) {
				break;
			}
		}

		assertNotNull(resolved);
		assertEquals(Set.of(1211), RequiresApiInspectionSupport.resolveSymbolVersions(resolved));
	}

	private void configureMultiversionProject() {
		myFixture.addFileToProject("build.gradle.kts", """
				loom {
				    multiversion {
				        versions {
				            create("1.20.1")
				            create("1.21.1")
				        }
				    }
				}
				""");
		myFixture.addFileToProject("multiversion/api/RequiresApi.kt", """
				package multiversion.api

				annotation class RequiresApi(vararg val value: String)
				""");
	}

	private static void assertNoRequiresApiErrors(List<HighlightInfo> highlights) {
		assertTrue(highlights.toString(), highlights.stream()
				.filter(info -> info.getSeverity() == HighlightSeverity.ERROR)
				.noneMatch(info -> info.getDescription() != null && info.getDescription().contains("version-gated by @RequiresApi")));
	}
}
