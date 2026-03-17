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

import spock.lang.Specification

import net.fabricmc.loom.configuration.multiversion.MultiversionConstantsGenerator

class MultiversionConstantsGeneratorTest extends Specification {
	def "writes packed and structured constants"() {
		given:
		def outputDir = File.createTempDir().toPath()

		when:
		def output = new MultiversionConstantsGenerator().write(outputDir, "com.example.Constants", "1.21.1")
		def text = output.toFile().text

		then:
		text.contains("public static final int MINECRAFT_VERSION = 1211;")
		text.contains("public static final int MC_MAJOR = 1;")
		text.contains("public static final int MC_MINOR = 21;")
		text.contains("public static final int MC_PATCH = 1;")
		text.contains("public static boolean isAtLeast(int version)")
	}
}
