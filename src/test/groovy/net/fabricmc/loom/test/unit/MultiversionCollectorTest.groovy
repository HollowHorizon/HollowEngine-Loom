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

import java.nio.file.Path

import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import spock.lang.Specification

import net.fabricmc.loom.configuration.multiversion.MultiversionCollector
import net.fabricmc.loom.configuration.multiversion.MultiversionClassInfo
import net.fabricmc.loom.util.ZipUtils

class MultiversionCollectorTest extends Specification {
	def "collects exact signatures separately across versions"() {
		given:
		def v1Jar = createJar([
				"example/PoseStack.class": poseStackClass("example/PoseStack", [
						[name: "<init>", desc: "()V", access: Opcodes.ACC_PUBLIC],
						[name: "translate", desc: "(FFF)V", access: Opcodes.ACC_PUBLIC],
						[name: "shared", desc: "()V", access: Opcodes.ACC_PROTECTED]
				]),
				"example/PoseStack\$Inner.class": poseStackClass("example/PoseStack\$Inner", [
						[name: "<init>", desc: "()V", access: Opcodes.ACC_PUBLIC]
				])
		])
		def v2Jar = createJar([
				"example/PoseStack.class": poseStackClass("example/PoseStack", [
						[name: "<init>", desc: "()V", access: Opcodes.ACC_PUBLIC],
						[name: "translate", desc: "(DDD)V", access: Opcodes.ACC_PUBLIC],
						[name: "shared", desc: "()V", access: Opcodes.ACC_PROTECTED]
				]),
				"example/PoseStack\$Inner.class": poseStackClass("example/PoseStack\$Inner", [
						[name: "<init>", desc: "()V", access: Opcodes.ACC_PUBLIC]
				])
		])
		def collector = new MultiversionCollector()

		when:
		collector.addVersion("1.20.1", v1Jar)
		collector.addVersion("1.21.1", v2Jar)
		Map<String, MultiversionClassInfo> classes = collector.getClasses()

		then:
		classes.containsKey("example/PoseStack")
		classes.containsKey("example/PoseStack\$Inner")
		classes["example/PoseStack"].versions == ["1.20.1", "1.21.1"] as Set
		classes["example/PoseStack"].methods.containsKey(new MultiversionClassInfo.MethodSignature("translate", "(FFF)V"))
		classes["example/PoseStack"].methods.containsKey(new MultiversionClassInfo.MethodSignature("translate", "(DDD)V"))
		classes["example/PoseStack"].methods.get(new MultiversionClassInfo.MethodSignature("translate", "(FFF)V")).versions == ["1.20.1"] as Set
		classes["example/PoseStack"].methods.get(new MultiversionClassInfo.MethodSignature("translate", "(DDD)V")).versions == ["1.21.1"] as Set
		classes["example/PoseStack"].methods.get(new MultiversionClassInfo.MethodSignature("shared", "()V")).versions == ["1.20.1", "1.21.1"] as Set
	}

	def "keeps first compatible class header when supertypes drift"() {
		given:
		def v1Jar = createJar([
				"example/Compatible.class": simpleClass("example/Compatible", Opcodes.ACC_PUBLIC, "java/lang/Object", null)
		])
		def v2Jar = createJar([
				"example/Compatible.class": simpleClass("example/Compatible", Opcodes.ACC_PUBLIC, "java/lang/Number", ["java/io/Serializable"])
		])
		def collector = new MultiversionCollector()

		when:
		collector.addVersion("1.20.1", v1Jar)
		collector.addVersion("1.21.1", v2Jar)
		def classInfo = collector.getClasses().get("example/Compatible")

		then:
		classInfo != null
		classInfo.superName == "java/lang/Object"
		classInfo.interfaces == []
		classInfo.versions == ["1.20.1", "1.21.1"] as Set
	}

	def "keeps first class kind when class kind changes across versions"() {
		given:
		def v1Jar = createJar([
				"example/Incompatible.class": simpleClass("example/Incompatible", Opcodes.ACC_PUBLIC, "java/lang/Object", null)
		])
		def v2Jar = createJar([
				"example/Incompatible.class": simpleClass("example/Incompatible", Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, "java/lang/Object", null)
		])
		def collector = new MultiversionCollector()

		when:
		collector.addVersion("1.20.1", v1Jar)
		collector.addVersion("1.21.1", v2Jar)
		def classInfo = collector.getClasses().get("example/Incompatible")

		then:
		noExceptionThrown()
		classInfo != null
		classInfo.access == Opcodes.ACC_PUBLIC
		classInfo.versions == ["1.20.1", "1.21.1"] as Set
	}

	static Path createJar(Map<String, byte[]> entries) {
		def jar = File.createTempFile("multiversion", ".jar").toPath()
		jar.toFile().delete()
		ZipUtils.add(jar, entries.collect { path, bytes -> new net.fabricmc.loom.util.Pair<String, byte[]>(path, bytes) })
		return jar
	}

	static byte[] poseStackClass(String name, List<Map<String, Object>> methods) {
		return simpleClass(name, Opcodes.ACC_PUBLIC, "java/lang/Object", null, methods)
	}

	static byte[] simpleClass(String name, int access, String superName, List<String> interfaces, List<Map<String, Object>> methods = [
			[name: "<init>", desc: "()V", access: Opcodes.ACC_PUBLIC]
	]) {
		def writer = new ClassWriter(0)
		writer.visit(Opcodes.V17, access, name, null, superName, interfaces as String[])

		methods.each { method ->
			MethodVisitor visitor = writer.visitMethod(method.access as int, method.name as String, method.desc as String, null, null)

			if ((method.access as int & Opcodes.ACC_ABSTRACT) == 0) {
				visitor.visitCode()

				if (method.name == "<init>") {
					visitor.visitVarInsn(Opcodes.ALOAD, 0)
					visitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
					visitor.visitInsn(Opcodes.RETURN)
				} else {
					visitor.visitInsn(Opcodes.RETURN)
				}

				visitor.visitMaxs(1, 1)
			}

			visitor.visitEnd()
		}

		writer.visitEnd()
		return writer.toByteArray()
	}
}
