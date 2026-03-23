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

import org.objectweb.asm.ClassReader
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
				"net/minecraft/example/PoseStack.class": poseStackClass("net/minecraft/example/PoseStack", [
						[name: "<init>", desc: "()V", access: Opcodes.ACC_PUBLIC],
						[name: "translate", desc: "(FFF)V", access: Opcodes.ACC_PUBLIC],
						[name: "shared", desc: "()V", access: Opcodes.ACC_PROTECTED]
				]),
				"net/minecraft/example/PoseStack\$Inner.class": poseStackClass("net/minecraft/example/PoseStack\$Inner", [
						[name: "<init>", desc: "()V", access: Opcodes.ACC_PUBLIC]
				])
		])
		def v2Jar = createJar([
				"net/minecraft/example/PoseStack.class": poseStackClass("net/minecraft/example/PoseStack", [
						[name: "<init>", desc: "()V", access: Opcodes.ACC_PUBLIC],
						[name: "translate", desc: "(DDD)V", access: Opcodes.ACC_PUBLIC],
						[name: "shared", desc: "()V", access: Opcodes.ACC_PROTECTED]
				]),
				"net/minecraft/example/PoseStack\$Inner.class": poseStackClass("net/minecraft/example/PoseStack\$Inner", [
						[name: "<init>", desc: "()V", access: Opcodes.ACC_PUBLIC]
				])
		])
		def collector = new MultiversionCollector()

		when:
		collector.addVersion("1.20.1", v1Jar)
		collector.addVersion("1.21.1", v2Jar)
		Map<String, MultiversionClassInfo> classes = collector.getClasses()

		then:
		classes.containsKey("net/minecraft/example/PoseStack")
		classes.containsKey("net/minecraft/example/PoseStack\$Inner")
		classes["net/minecraft/example/PoseStack"].versions == ["1.20.1", "1.21.1"] as Set
		classes["net/minecraft/example/PoseStack"].methods.containsKey(new MultiversionClassInfo.MethodSignature("translate", "(FFF)V"))
		classes["net/minecraft/example/PoseStack"].methods.containsKey(new MultiversionClassInfo.MethodSignature("translate", "(DDD)V"))
		classes["net/minecraft/example/PoseStack"].methods.get(new MultiversionClassInfo.MethodSignature("translate", "(FFF)V")).versions == ["1.20.1"] as Set
		classes["net/minecraft/example/PoseStack"].methods.get(new MultiversionClassInfo.MethodSignature("translate", "(DDD)V")).versions == ["1.21.1"] as Set
		classes["net/minecraft/example/PoseStack"].methods.get(new MultiversionClassInfo.MethodSignature("shared", "()V")).versions == ["1.20.1", "1.21.1"] as Set
	}

	def "keeps first compatible class header when supertypes drift"() {
		given:
		def v1Jar = createJar([
				"net/minecraft/example/Compatible.class": simpleClass("net/minecraft/example/Compatible", Opcodes.ACC_PUBLIC, "java/lang/Object", null)
		])
		def v2Jar = createJar([
				"net/minecraft/example/Compatible.class": simpleClass("net/minecraft/example/Compatible", Opcodes.ACC_PUBLIC, "java/lang/Number", ["java/io/Serializable"])
		])
		def collector = new MultiversionCollector()

		when:
		collector.addVersion("1.20.1", v1Jar)
		collector.addVersion("1.21.1", v2Jar)
		def classInfo = collector.getClasses().get("net/minecraft/example/Compatible")

	then:
	classInfo != null
	classInfo.superName == "java/lang/Object"
	classInfo.interfaces.keySet() == ["java/io/Serializable"] as Set
	classInfo.interfaces.get("java/io/Serializable") == ["1.21.1"] as Set
	classInfo.versions == ["1.20.1", "1.21.1"] as Set
	}

	def "drops target-only interfaces from shared stubs"() {
		given:
		def v1Jar = createJar([
				"net/minecraft/example/Level.class": simpleClass("net/minecraft/example/Level", Opcodes.ACC_PUBLIC, "java/lang/Object", null)
		])
		def v2Jar = createJar([
				"net/minecraft/example/Level.class": simpleClass("net/minecraft/example/Level", Opcodes.ACC_PUBLIC, "java/lang/Object", [
						"net/fabricmc/fabric/api/attachment/v1/AttachmentTarget"
				])
		])
		def collector = new MultiversionCollector()
		def stubJar = File.createTempFile("stub", ".jar").toPath()
		stubJar.toFile().delete()
		def sourcesJar = File.createTempFile("stub-sources", ".jar").toPath()
		sourcesJar.toFile().delete()

		when:
		collector.addVersion("1.20.1", v1Jar)
		collector.addVersion("1.21.1", v2Jar)
		new net.fabricmc.loom.configuration.multiversion.MultiversionStubGenerator().generate(collector, stubJar, sourcesJar, "com.example.Constants")
		def classNode = new org.objectweb.asm.tree.ClassNode()
		new ClassReader(ZipUtils.unpack(stubJar, "net/minecraft/example/Level.class")).accept(classNode, 0)
		def source = new String(ZipUtils.unpack(sourcesJar, "net/minecraft/example/Level.java"))

		then:
		classNode.interfaces.isEmpty()
		!source.contains("AttachmentTarget")
	}

	def "keeps first class kind when class kind changes across versions"() {
		given:
		def v1Jar = createJar([
				"net/minecraft/example/Incompatible.class": simpleClass("net/minecraft/example/Incompatible", Opcodes.ACC_PUBLIC, "java/lang/Object", null)
		])
		def v2Jar = createJar([
				"net/minecraft/example/Incompatible.class": simpleClass("net/minecraft/example/Incompatible", Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, "java/lang/Object", null)
		])
		def collector = new MultiversionCollector()

		when:
		collector.addVersion("1.20.1", v1Jar)
		collector.addVersion("1.21.1", v2Jar)
		def classInfo = collector.getClasses().get("net/minecraft/example/Incompatible")

		then:
		noExceptionThrown()
		classInfo != null
		classInfo.access == Opcodes.ACC_PUBLIC
		classInfo.versions == ["1.20.1", "1.21.1"] as Set
	}

	def "merges inner class visibility without invalid combined flags"() {
		given:
		def v1Jar = createJar([
				"net/minecraft/example/Outer.class": outerClassWithInner("net/minecraft/example/Outer", "net/minecraft/example/Outer\$Inner", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC),
				"net/minecraft/example/Outer\$Inner.class": simpleClass("net/minecraft/example/Outer\$Inner", Opcodes.ACC_PUBLIC, "java/lang/Object", null)
		])
		def v2Jar = createJar([
				"net/minecraft/example/Outer.class": outerClassWithInner("net/minecraft/example/Outer", "net/minecraft/example/Outer\$Inner", Opcodes.ACC_PROTECTED | Opcodes.ACC_STATIC),
				"net/minecraft/example/Outer\$Inner.class": simpleClass("net/minecraft/example/Outer\$Inner", Opcodes.ACC_PUBLIC, "java/lang/Object", null)
		])
		def collector = new MultiversionCollector()

		when:
		collector.addVersion("1.20.1", v1Jar)
		collector.addVersion("1.21.1", v2Jar)
		def classInfo = collector.getClasses().get("net/minecraft/example/Outer\$Inner")

		then:
		classInfo != null
		(classInfo.innerAccess & Opcodes.ACC_PUBLIC) != 0
		(classInfo.innerAccess & Opcodes.ACC_PROTECTED) == 0
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

	static byte[] outerClassWithInner(String name, String innerName, int innerAccess) {
		def writer = new ClassWriter(0)
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
		writer.visitInnerClass(innerName, name, "Inner", innerAccess)
		MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
		constructor.visitCode()
		constructor.visitVarInsn(Opcodes.ALOAD, 0)
		constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
		constructor.visitInsn(Opcodes.RETURN)
		constructor.visitMaxs(1, 1)
		constructor.visitEnd()
		writer.visitEnd()
		return writer.toByteArray()
	}
}
