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

import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import spock.lang.Specification

import net.fabricmc.loom.LoomGradlePlugin
import net.fabricmc.loom.configuration.multiversion.MultiversionApiMetadata
import net.fabricmc.loom.configuration.multiversion.MultiversionCollector
import net.fabricmc.loom.configuration.multiversion.MultiversionStubGenerator
import net.fabricmc.loom.util.ZipUtils

class MultiversionStubGeneratorTest extends Specification {
	def "generates metadata and annotates members"() {
		given:
		def collector = new MultiversionCollector()
		def jar = MultiversionCollectorTest.createJar([
				"example/PoseStack.class": MultiversionCollectorTest.poseStackClass("example/PoseStack", [
						[name: "<init>", desc: "()V", access: Opcodes.ACC_PUBLIC],
						[name: "translate", desc: "(FFF)V", access: Opcodes.ACC_PUBLIC]
				])
		])
		collector.addVersion("1.20.1", jar)
		def stubJar = File.createTempFile("stub", ".jar").toPath()
		stubJar.toFile().delete()
		def sourcesJar = File.createTempFile("stub-sources", ".jar").toPath()
		sourcesJar.toFile().delete()

		when:
		def artifacts = new MultiversionStubGenerator().generate(collector, stubJar, sourcesJar, "com.example.Constants")
		def metadata = LoomGradlePlugin.GSON.fromJson(new String(ZipUtils.unpack(stubJar, MultiversionApiMetadata.PATH)), MultiversionApiMetadata)
		def classBytes = ZipUtils.unpack(stubJar, "example/PoseStack.class")

		then:
		artifacts.metadata().classes().get("example/PoseStack") == ["1.20.1"] as Set
		metadata.methods().containsKey(MultiversionApiMetadata.methodKey("example/PoseStack", "translate", "(FFF)V"))
		ZipUtils.contains(stubJar, "multiversion/api/RequiresApi.class")
		ZipUtils.contains(stubJar, "com/example/Constants.class")
		ZipUtils.contains(sourcesJar, "example/PoseStack.java")
		ZipUtils.contains(sourcesJar, "com/example/Constants.java")

		and:
		def seenAnnotation = false
		new ClassReader(classBytes).accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
			@Override
			org.objectweb.asm.AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
				if (descriptor == "Lmultiversion/api/RequiresApi;") {
					seenAnnotation = true
				}
				return super.visitAnnotation(descriptor, visible)
			}
		}, 0)
		seenAnnotation

		and:
		new String(ZipUtils.unpack(sourcesJar, "example/PoseStack.java")).contains("@RequiresApi")
		new String(ZipUtils.unpack(sourcesJar, "com/example/Constants.java")).contains("public static boolean isAtLeast")
	}
}
