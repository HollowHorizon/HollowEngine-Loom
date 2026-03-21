/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2026 FabricMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.fabricmc.loom.test.unit

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.regex.Pattern

import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.StandardJavaFileManager
import javax.tools.ToolProvider

import spock.lang.Specification

import net.fabricmc.loom.configuration.multiversion.MultiversionApiMetadata
import net.fabricmc.loom.configuration.multiversion.MultiversionApiUsageValidator

class MultiversionConditionMatrixTest extends Specification {
	private static final Set<Integer> ALL = [1201, 1211] as Set
	private static final Set<Integer> ONLY_1201 = [1201] as Set
	private static final Set<Integer> ONLY_1211 = [1211] as Set
	private static final List<ConditionCase> CONDITIONS = [
			new ConditionCase("Constants.MINECRAFT_VERSION == Constants.V1_20_1", ONLY_1201),
			new ConditionCase("Constants.V1_21_1 == Constants.MINECRAFT_VERSION", ONLY_1211),
			new ConditionCase("Constants.MINECRAFT_VERSION != Constants.V1_20_1", ONLY_1211),
			new ConditionCase("Constants.MINECRAFT != Constants.V1_21_1", ONLY_1201),
			new ConditionCase("Constants.MINECRAFT <= Constants.V1_20_1", ONLY_1201),
			new ConditionCase("Constants.MINECRAFT < Constants.V1_21_1", ONLY_1201),
			new ConditionCase("Constants.MINECRAFT >= Constants.V1_21_1", ONLY_1211),
			new ConditionCase("Constants.MINECRAFT > Constants.V1_20_1", ONLY_1211),
			new ConditionCase("Constants.MINECRAFT == Constants.V1_20_1", ONLY_1201),
			new ConditionCase("Constants.MINECRAFT == Constants.V1_21_1", ONLY_1211),
			new ConditionCase("Constants.MINECRAFT >= Constants.V1_21_1", ONLY_1211),
			new ConditionCase("Constants.MINECRAFT <= Constants.V1_20_1", ONLY_1201),
			new ConditionCase("!(Constants.MINECRAFT == Constants.V1_20_1)", ONLY_1211),
			new ConditionCase("(Constants.MINECRAFT == Constants.V1_20_1) || (Constants.MINECRAFT == Constants.V1_21_1)", ALL),
			new ConditionCase("(Constants.MINECRAFT == Constants.V1_20_1) && (Constants.MINECRAFT == Constants.V1_21_1)", [] as Set),
			new ConditionCase("(Constants.MINECRAFT != Constants.V1_20_1) && (Constants.MINECRAFT != Constants.V1_21_1)", [] as Set),
			new ConditionCase("(Constants.MINECRAFT < Constants.V1_21_1) || (Constants.MINECRAFT == Constants.V1_21_1)", ALL),
			new ConditionCase("(Constants.MINECRAFT >= Constants.V1_21_1) && !(Constants.MINECRAFT == Constants.V1_20_1)", ONLY_1211),
			new ConditionCase("(Constants.MC_MINOR == 20) || (Constants.MC_MINOR == 21)", ALL),
			new ConditionCase("(Constants.MC_MINOR == 20) && (Constants.MC_PATCH == 1)", ONLY_1201)
	]

	def "exhaustively validates single branch and else branch conditions"() {
		given:
		def methods = new StringBuilder()
		def expectedViolations = [] as Set

		CONDITIONS.eachWithIndex { ConditionCase conditionCase, int index ->
			methods.append("""
				public void then_${index}() {
					if (${conditionCase.expression}) {
						BlockPos.max(null, null);
					}
				}

				public void else_${index}() {
					if (${conditionCase.expression}) {
						System.out.println("skip");
					} else {
						BlockPos.max(null, null);
					}
				}
			""".stripIndent())

			if (conditionCase.trueVersions.contains(1201)) {
				expectedViolations.add("then_${index}")
			}

			if (falseVersions(conditionCase.trueVersions).contains(1201)) {
				expectedViolations.add("else_${index}")
			}
		}

		def classBytes = compileJavaCaseClass(methods.toString())

		when:
		def violations = new MultiversionApiUsageValidator(metadata(), "example.Constants")
				.validateClass("example/TestCases.class", classBytes)

		then:
		extractMethodNames(violations) == expectedViolations
	}

	def "exhaustively validates nested branch sequences"() {
		given:
		def methods = new StringBuilder()
		def expectedViolations = [] as Set

		CONDITIONS.eachWithIndex { ConditionCase outerCondition, int outerIndex ->
			CONDITIONS.eachWithIndex { ConditionCase innerCondition, int innerIndex ->
				def combinations = [
						[path: "tt", reachable: outerCondition.trueVersions.intersect(innerCondition.trueVersions)],
						[path: "tf", reachable: outerCondition.trueVersions.intersect(falseVersions(innerCondition.trueVersions))],
						[path: "et", reachable: falseVersions(outerCondition.trueVersions).intersect(innerCondition.trueVersions)],
						[path: "ef", reachable: falseVersions(outerCondition.trueVersions).intersect(falseVersions(innerCondition.trueVersions))]
				]

				combinations.each { combination ->
					def methodName = "seq_${outerIndex}_${innerIndex}_${combination.path}"
					methods.append(sequenceMethodSource(methodName, outerCondition.expression, innerCondition.expression, combination.path))

					if (combination.reachable.contains(1201)) {
						expectedViolations.add(methodName)
					}
				}
			}
		}

		def classBytes = compileJavaCaseClass(methods.toString())

		when:
		def violations = new MultiversionApiUsageValidator(metadata(), "example.Constants")
				.validateClass("example/TestCases.class", classBytes)

		then:
		extractMethodNames(violations) == expectedViolations
	}

	private static Set<Integer> falseVersions(Set<Integer> trueVersions) {
		def falseVersions = new LinkedHashSet<>(ALL)
		falseVersions.removeAll(trueVersions)
		return falseVersions
	}

	private static String sequenceMethodSource(String methodName, String outerCondition, String innerCondition, String path) {
		if (path == "tt") {
			return """
			public void ${methodName}() {
				if (${outerCondition}) {
					if (${innerCondition}) {
						BlockPos.max(null, null);
					}
				}
			}
		""".stripIndent()
		}

		if (path == "tf") {
			return """
			public void ${methodName}() {
				if (${outerCondition}) {
					if (${innerCondition}) {
						System.out.println("skip");
					} else {
						BlockPos.max(null, null);
					}
				}
			}
		""".stripIndent()
		}

		if (path == "et") {
			return """
			public void ${methodName}() {
				if (${outerCondition}) {
					System.out.println("skip");
				} else {
					if (${innerCondition}) {
						BlockPos.max(null, null);
					}
				}
			}
		""".stripIndent()
		}

		if (path == "ef") {
			return """
			public void ${methodName}() {
				if (${outerCondition}) {
					System.out.println("skip");
				} else {
					if (${innerCondition}) {
						System.out.println("skip");
					} else {
						BlockPos.max(null, null);
					}
				}
			}
		""".stripIndent()
		}

		throw new IllegalArgumentException("Unsupported path " + path)
	}

	private static Set<String> extractMethodNames(List<String> violations) {
		def pattern = Pattern.compile("#([^\\(]+)\\(")
		def methodNames = [] as Set

		violations.each { violation ->
			def matcher = pattern.matcher(violation)

			if (matcher.find()) {
				methodNames.add(matcher.group(1))
			}
		}

		return methodNames
	}

	private static MultiversionApiMetadata metadata() {
		def builder = MultiversionApiMetadata.builder(["1.20.1", "1.21.1"] as Set)
		builder.addClass("example/api/BlockPos", ["1.20.1", "1.21.1"] as Set)
		builder.addMethod("example/api/BlockPos", "max", "(Lexample/api/BlockPos;Lexample/api/BlockPos;)Lexample/api/BlockPos;", ["1.21.1"] as Set)
		return builder.build()
	}

	private static byte[] compileJavaCaseClass(String methodsSource) {
		def compiler = ToolProvider.systemJavaCompiler
		assert compiler != null

		Path tempDir = Files.createTempDirectory("mv-condition-matrix")

		try {
			def sources = writeSources(tempDir, methodsSource)
			DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>()
			StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)

			try {
				def units = fileManager.getJavaFileObjectsFromFiles(sources.collect { it.toFile() })
				def task = compiler.getTask(null, fileManager, diagnostics, ["--release", "21", "-d", tempDir.toString()], null, units)
				def success = task.call()

				if (!success) {
					throw new AssertionError("Compilation failed:\n" + diagnostics.getDiagnostics().join("\n"))
				}
			} finally {
				fileManager.close()
			}

			return Files.readAllBytes(tempDir.resolve("example/TestCases.class"))
		} finally {
			Files.walk(tempDir)
					.sorted(Comparator.reverseOrder())
					.forEach { Files.delete(it) }
		}
	}

	private static List<Path> writeSources(Path tempDir, String methodsSource) {
		def constantsPath = tempDir.resolve("example/Constants.java")
		def blockPosPath = tempDir.resolve("example/api/BlockPos.java")
		def testCasesPath = tempDir.resolve("example/TestCases.java")
		Files.createDirectories(constantsPath.parent)
		Files.createDirectories(blockPosPath.parent)

		Files.writeString(constantsPath, """
			package example;

			public final class Constants {
				public static final int MINECRAFT_VERSION = currentVersion();
				public static final int MINECRAFT = MINECRAFT_VERSION;
				public static final int MC_MAJOR = MINECRAFT_VERSION / 1000;
				public static final int MC_MINOR = (MINECRAFT_VERSION % 1000) / 10;
				public static final int MC_PATCH = MINECRAFT_VERSION % 10;
				public static final int V1_20_1 = 1201;
				public static final int V1_21_1 = 1211;

				private static int currentVersion() {
					return 0;
				}

			}
		""".stripIndent())
		Files.writeString(blockPosPath, """
			package example.api;

			public final class BlockPos {
				public static BlockPos max(BlockPos left, BlockPos right) {
					return left;
				}
			}
		""".stripIndent())
		Files.writeString(testCasesPath, """
			package example;

			import example.api.BlockPos;

			public class TestCases {
				${methodsSource}
			}
		""".stripIndent())

		return [constantsPath, blockPosPath, testCasesPath]
	}

	private static final class ConditionCase {
		final String expression
		final Set<Integer> trueVersions

		ConditionCase(String expression, Set<Integer> trueVersions) {
			this.expression = expression
			this.trueVersions = trueVersions
		}
	}
}
