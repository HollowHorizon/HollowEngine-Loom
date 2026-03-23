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

package net.fabricmc.loom.configuration.multiversion

import java.nio.file.Files
import java.nio.file.Path

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import spock.lang.Specification

import net.fabricmc.loom.LoomGradlePlugin
import net.fabricmc.loom.test.unit.MultiversionCollectorTest
import net.fabricmc.loom.util.ZipUtils

class MultiversionSyntheticMemberNamesTest extends Specification {
	def "generates synthetic aliases for members with return-type drift"() {
		given:
		def collector = new MultiversionCollector()
		def v1Jar = MultiversionCollectorTest.createJar([
				"example/Api.class": apiClass("example/Api", [
						[name: "getId", desc: "()I", access: Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC],
						[name: "value", desc: "I", access: Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC],
						[name: "plain", desc: "()V", access: Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC],
						[name: "plain", desc: "(I)V", access: Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC]
				]),
				"example/MapId.class": simpleClass("example/MapId")
		])
		def v2Jar = MultiversionCollectorTest.createJar([
				"example/Api.class": apiClass("example/Api", [
						[name: "getId", desc: "()Lexample/MapId;", access: Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC],
						[name: "value", desc: "Lexample/MapId;", access: Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC],
						[name: "plain", desc: "()V", access: Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC],
						[name: "plain", desc: "(I)V", access: Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC]
				]),
				"example/MapId.class": simpleClass("example/MapId")
		])
		collector.addVersion("1.20.1", v1Jar)
		collector.addVersion("1.21.1", v2Jar)
		def stubJar = File.createTempFile("multiversion-stub", ".jar").toPath()
		stubJar.toFile().delete()
		def sourcesJar = File.createTempFile("multiversion-stub-sources", ".jar").toPath()
		sourcesJar.toFile().delete()

		when:
		new MultiversionStubGenerator().generate(collector, stubJar, sourcesJar, "com.example.Constants")
		def metadata = LoomGradlePlugin.GSON.fromJson(new String(ZipUtils.unpack(stubJar, MultiversionApiMetadata.PATH)), MultiversionApiMetadata)
		def source = new String(ZipUtils.unpack(sourcesJar, "example/Api.java"))
		def classBytes = ZipUtils.unpack(stubJar, "example/Api.class")
		def memberNames = []

		new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			MethodVisitor visitMethod(int access, String memberName, String memberDescriptor, String signature, String[] exceptions) {
				memberNames.add([kind: "method", name: memberName, desc: memberDescriptor])
				return super.visitMethod(access, memberName, memberDescriptor, signature, exceptions)
			}

			@Override
			FieldVisitor visitField(int access, String memberName, String memberDescriptor, String signature, Object value) {
				memberNames.add([kind: "field", name: memberName, desc: memberDescriptor])
				return super.visitField(access, memberName, memberDescriptor, signature, value)
			}
		}, 0)

		then:
		metadata.syntheticMethods()[MultiversionApiMetadata.methodKey("example/Api", "getId", "()I")] == 'getId$1_20_1'
		metadata.syntheticMethods()[MultiversionApiMetadata.methodKey("example/Api", "getId", "()Lexample/MapId;")] == 'getId$1_21_1'
		metadata.syntheticFields()[MultiversionApiMetadata.fieldKey("example/Api", "value", "I")] == 'value$1_20_1'
		metadata.syntheticFields()[MultiversionApiMetadata.fieldKey("example/Api", "value", "Lexample/MapId;")] == 'value$1_21_1'
		!metadata.syntheticMethods().containsKey(MultiversionApiMetadata.methodKey("example/Api", "plain", "()V"))
		!metadata.syntheticMethods().containsKey(MultiversionApiMetadata.methodKey("example/Api", "plain", "(I)V"))

		source.contains('int getId$1_20_1()')
		source.contains('example.MapId getId$1_21_1()')
		source.contains('int value$1_20_1;')
		source.contains('example.MapId value$1_21_1;')
		source.contains('void plain()')
		source.contains('void plain(int arg0)')

		memberNames.contains([kind: "method", name: 'getId$1_20_1', desc: "()I"])
		memberNames.contains([kind: "method", name: 'getId$1_21_1', desc: "()Lexample/MapId;"])
		memberNames.contains([kind: "field", name: 'value$1_20_1', desc: "I"])
		memberNames.contains([kind: "field", name: 'value$1_21_1', desc: "Lexample/MapId;"])
		memberNames.contains([kind: "method", name: 'plain', desc: "()V"])
		memberNames.contains([kind: "method", name: 'plain', desc: "(I)V"])
		!memberNames.any { it.name == "getId" || it.name == "value" }
	}

	def "restores synthetic names back to original after compilation"() {
		given:
		def collector = new MultiversionCollector()
		def v1Jar = MultiversionCollectorTest.createJar([
				"example/Api.class": apiClass("example/Api", [
						[name: "getId", desc: "()I", access: Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC],
						[name: "value", desc: "I", access: Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC],
						[name: "plain", desc: "()V", access: Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC]
				]),
				"example/MapId.class": simpleClass("example/MapId")
		])
		def v2Jar = MultiversionCollectorTest.createJar([
				"example/Api.class": apiClass("example/Api", [
						[name: "getId", desc: "()Lexample/MapId;", access: Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC],
						[name: "value", desc: "Lexample/MapId;", access: Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC],
						[name: "plain", desc: "()V", access: Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC]
				]),
				"example/MapId.class": simpleClass("example/MapId")
		])
		collector.addVersion("1.20.1", v1Jar)
		collector.addVersion("1.21.1", v2Jar)
		def stubJar = File.createTempFile("multiversion-stub", ".jar").toPath()
		stubJar.toFile().delete()
		def sourcesJar = File.createTempFile("multiversion-stub-sources", ".jar").toPath()
		sourcesJar.toFile().delete()

		when:
		new MultiversionStubGenerator().generate(collector, stubJar, sourcesJar, "com.example.Constants")
		def metadata = MultiversionApiNameRestorer.readMetadata(stubJar)
		def inputDir = Files.createTempDirectory("multiversion-restore-input")
		def outputClass = inputDir.resolve("example/Use.class")
		Files.createDirectories(outputClass.getParent())
		Files.write(outputClass, syntheticUseClass())
		new MultiversionApiNameRestorer(metadata).restoreDirectories([inputDir])

		def remapped = []
		new ClassReader(Files.readAllBytes(outputClass)).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			MethodVisitor visitMethod(int access, String memberName, String memberDescriptor, String signature, String[] exceptions) {
				return new MethodVisitor(Opcodes.ASM9) {
					@Override
					void visitMethodInsn(int opcode, String owner, String insnName, String insnDescriptor, boolean isInterface) {
						remapped.add([kind: "method", owner: owner, name: insnName, desc: insnDescriptor])
						super.visitMethodInsn(opcode, owner, insnName, insnDescriptor, isInterface)
					}

					@Override
					void visitFieldInsn(int opcode, String owner, String insnName, String insnDescriptor) {
						remapped.add([kind: "field", owner: owner, name: insnName, desc: insnDescriptor])
						super.visitFieldInsn(opcode, owner, insnName, insnDescriptor)
					}
				}
			}
		}, 0)

		then:
		remapped.contains([kind: "method", owner: "example/Api", name: "getId", desc: "()I"])
		remapped.contains([kind: "method", owner: "example/Api", name: "getId", desc: "()Lexample/MapId;"])
		remapped.contains([kind: "field", owner: "example/Api", name: "value", desc: "I"])
		remapped.contains([kind: "field", owner: "example/Api", name: "value", desc: "Lexample/MapId;"])
		remapped.contains([kind: "method", owner: "example/Api", name: "plain", desc: "()V"])
		!remapped.any { it.name.contains("\$1_20_1") || it.name.contains("\$1_21_1") }
	}

	private static byte[] apiClass(String name, List<Map<String, Object>> members) {
		def writer = new ClassWriter(0)
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)

		members.each { member ->
			if ((member.access as int) & Opcodes.ACC_STATIC) {
				if ((member.desc as String).startsWith("(")) {
					def visitor = writer.visitMethod(member.access as int, member.name as String, member.desc as String, null, null)
					visitor.visitCode()
					if ((member.desc as String).endsWith("V")) {
						visitor.visitInsn(Opcodes.RETURN)
					} else if ((member.desc as String).endsWith("I")) {
						visitor.visitInsn(Opcodes.ICONST_0)
						visitor.visitInsn(Opcodes.IRETURN)
					} else {
						visitor.visitInsn(Opcodes.ACONST_NULL)
						visitor.visitInsn(Opcodes.ARETURN)
					}
					visitor.visitMaxs(1, 0)
					visitor.visitEnd()
				} else {
					writer.visitField(member.access as int, member.name as String, member.desc as String, null, null).visitEnd()
				}
			}
		}

		writer.visitEnd()
		return writer.toByteArray()
	}

	private static byte[] simpleClass(String name) {
		def writer = new ClassWriter(0)
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)
		writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null).with { visitor ->
			visitor.visitCode()
			visitor.visitVarInsn(Opcodes.ALOAD, 0)
			visitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
			visitor.visitInsn(Opcodes.RETURN)
			visitor.visitMaxs(1, 1)
			visitor.visitEnd()
		}
		writer.visitEnd()
		return writer.toByteArray()
	}

	private static byte[] syntheticUseClass() {
		def writer = new ClassWriter(0)
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "example/Use", null, "java/lang/Object", null)
		def visitor = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "call", "()V", null, null)
		visitor.visitCode()
		visitor.visitMethodInsn(Opcodes.INVOKESTATIC, "example/Api", 'getId$1_20_1', "()I", false)
		visitor.visitInsn(Opcodes.POP)
		visitor.visitMethodInsn(Opcodes.INVOKESTATIC, "example/Api", 'getId$1_21_1', "()Lexample/MapId;", false)
		visitor.visitInsn(Opcodes.POP)
		visitor.visitFieldInsn(Opcodes.GETSTATIC, "example/Api", 'value$1_20_1', "I")
		visitor.visitInsn(Opcodes.POP)
		visitor.visitFieldInsn(Opcodes.GETSTATIC, "example/Api", 'value$1_21_1', "Lexample/MapId;")
		visitor.visitInsn(Opcodes.POP)
		visitor.visitMethodInsn(Opcodes.INVOKESTATIC, "example/Api", 'plain', "()V", false)
		visitor.visitInsn(Opcodes.RETURN)
		visitor.visitMaxs(1, 0)
		visitor.visitEnd()
		writer.visitEnd()
		return writer.toByteArray()
	}
}
