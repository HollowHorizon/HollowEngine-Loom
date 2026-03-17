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

package net.fabricmc.loom.test.integration

import groovy.io.FileType
import spock.lang.Specification

import net.fabricmc.loom.test.LoomTestConstants
import net.fabricmc.loom.test.util.GradleProjectTestTrait
import net.fabricmc.loom.util.ZipUtils

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS

class MultiversionProjectTest extends Specification implements GradleProjectTestTrait {
	def "shared stub and target constants build for multiple fabric targets"() {
		setup:
		def gradle = gradleProject(project: "multiversion", version: LoomTestConstants.DEFAULT_GRADLE, warningMode: "summary")

		when:
		def result = gradle.run(tasks: [
			":common:classes",
			":fabric1201:classes",
			":fabric1211:classes",
			":fabric1201:ideaSyncTask",
			":fabric1211:ideaSyncTask"
		], configurationCache: false)

		then:
		result.task(":common:classes").outcome == SUCCESS
		result.task(":fabric1201:classes").outcome == SUCCESS
		result.task(":fabric1211:classes").outcome == SUCCESS
		result.task(":fabric1201:ideaSyncTask").outcome != null
		result.task(":fabric1211:ideaSyncTask").outcome != null

		new File(gradle.projectDir, "common/build/classes/java/main/com/example/common/CommonBlockPos.class").exists()
		new File(gradle.projectDir, "fabric1201/build/classes/java/main/com/example/FabricTarget.class").exists()
		new File(gradle.projectDir, "fabric1211/build/classes/java/main/com/example/FabricTarget1211.class").exists()
		new File(gradle.projectDir, "common/build/loom-cache/validateMultiversionApiUsage.ok").exists()
		!new File(gradle.projectDir, "fabric1201/build/loom-cache/validateMultiversionApiUsage.ok").exists()
		!new File(gradle.projectDir, "fabric1211/build/loom-cache/validateMultiversionApiUsage.ok").exists()

		def constants1201 = new File(gradle.projectDir, "fabric1201/build/generated/sources/loomMultiversion/constants/com/example/Constants.java")
		def constants1211 = new File(gradle.projectDir, "fabric1211/build/generated/sources/loomMultiversion/constants/com/example/Constants.java")
		constants1201.exists()
		constants1211.exists()
		constants1201.text.contains("public static final int MINECRAFT_VERSION = 1201;")
		constants1211.text.contains("public static final int MINECRAFT_VERSION = 1211;")
		constants1201.text.contains("public static boolean isAtLeast")
		constants1211.text.contains("public static boolean isAtLeast")

		def stubJar = findArtifact(gradle.projectDir, { it.name.startsWith("multiversion-stub-") && it.name.endsWith(".jar") && !it.name.endsWith("-sources.jar") })
		def sourcesJar = findArtifact(gradle.projectDir, { it.name.startsWith("multiversion-stub-") && it.name.endsWith("-sources.jar") })

		ZipUtils.unpackNullable(stubJar.toPath(), "multiversion/api/RequiresApi.class") != null
		ZipUtils.unpackNullable(stubJar.toPath(), "META-INF/loom/multiversion-api.json") != null
		ZipUtils.unpackNullable(stubJar.toPath(), "com/example/Constants.class") != null
		ZipUtils.unpackNullable(sourcesJar.toPath(), "com/example/Constants.java") != null
		ZipUtils.unpackNullable(sourcesJar.toPath(), "com/example/FabricTarget.java") == null
	}

	private static File findArtifact(File root, Closure<Boolean> predicate) {
		final List<File> matches = []
		root.eachFileRecurse(FileType.FILES) { file ->
			if (predicate.call(file)) {
				matches.add(file)
			}
		}

		assert matches
		return matches.first()
	}
}
