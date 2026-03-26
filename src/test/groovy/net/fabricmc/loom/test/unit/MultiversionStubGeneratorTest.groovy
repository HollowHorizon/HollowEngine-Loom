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

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
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
				"net/minecraft/example/PoseStack.class": MultiversionCollectorTest.poseStackClass("net/minecraft/example/PoseStack", [
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
		def classBytes = ZipUtils.unpack(stubJar, "net/minecraft/example/PoseStack.class")

		then:
		artifacts.metadata().classes().get("net/minecraft/example/PoseStack") == ["1.20.1"] as Set
		metadata.methods().containsKey(MultiversionApiMetadata.methodKey("net/minecraft/example/PoseStack", "translate", "(FFF)V"))
		ZipUtils.contains(stubJar, "multiversion/api/RequiresApi.class")
		ZipUtils.contains(stubJar, "com/example/Constants.class")
		ZipUtils.contains(sourcesJar, "net/minecraft/example/PoseStack.java")
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
		new String(ZipUtils.unpack(sourcesJar, "net/minecraft/example/PoseStack.java")).contains("@RequiresApi")
		!new String(ZipUtils.unpack(sourcesJar, "com/example/Constants.java")).contains("public static boolean isAtLeast")
	}

	def "renders extends and implements in generated sources"() {
		given:
		def collector = new MultiversionCollector()
		def jar = MultiversionCollectorTest.createJar([
				"net/minecraft/example/Base.class": MultiversionCollectorTest.simpleClass("net/minecraft/example/Base", Opcodes.ACC_PUBLIC, "java/lang/Object", null),
				"net/minecraft/example/Marker.class": MultiversionCollectorTest.simpleClass("net/minecraft/example/Marker", Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, "java/lang/Object", null, []),
				"net/minecraft/example/Child.class": MultiversionCollectorTest.simpleClass("net/minecraft/example/Child", Opcodes.ACC_PUBLIC, "net/minecraft/example/Base", ["net/minecraft/example/Marker"])
		])
		collector.addVersion("1.20.1", jar)
		def stubJar = File.createTempFile("stub", ".jar").toPath()
		stubJar.toFile().delete()
		def sourcesJar = File.createTempFile("stub-sources", ".jar").toPath()
		sourcesJar.toFile().delete()

		when:
		new MultiversionStubGenerator().generate(collector, stubJar, sourcesJar, "com.example.Constants")
		def source = new String(ZipUtils.unpack(sourcesJar, "net/minecraft/example/Child.java"))

		then:
		source.contains("public class Child extends net.minecraft.example.Base implements net.minecraft.example.Marker")
	}

	def "keeps shared interfaces across versions"() {
		given:
		def collector = new MultiversionCollector()
		def v1Jar = MultiversionCollectorTest.createJar([
				"net/minecraft/example/BufferSource.class": MultiversionCollectorTest.simpleClass("net/minecraft/example/BufferSource", Opcodes.ACC_PUBLIC, "java/lang/Object", ["net/minecraft/example/MultiBufferSource"])
		])
		def v2Jar = MultiversionCollectorTest.createJar([
				"net/minecraft/example/BufferSource.class": MultiversionCollectorTest.simpleClass("net/minecraft/example/BufferSource", Opcodes.ACC_PUBLIC, "java/lang/Object", ["net/minecraft/example/MultiBufferSource"])
		])
		def stubJar = File.createTempFile("stub", ".jar").toPath()
		stubJar.toFile().delete()
		def sourcesJar = File.createTempFile("stub-sources", ".jar").toPath()
		sourcesJar.toFile().delete()

		when:
		collector.addVersion("1.20.1", v1Jar)
		collector.addVersion("1.21.1", v2Jar)
		new MultiversionStubGenerator().generate(collector, stubJar, sourcesJar, "com.example.Constants")
		def classNode = new org.objectweb.asm.tree.ClassNode()
		new ClassReader(ZipUtils.unpack(stubJar, "net/minecraft/example/BufferSource.class")).accept(classNode, 0)
		def source = new String(ZipUtils.unpack(sourcesJar, "net/minecraft/example/BufferSource.java"))

		then:
		classNode.interfaces == ["net/minecraft/example/MultiBufferSource"]
		source.contains("implements net.minecraft.example.MultiBufferSource")
	}

	def "keeps private and protected members in stubs"() {
		given:
		def collector = new MultiversionCollector()
		def jar = MultiversionCollectorTest.createJar([
				"net/minecraft/network/Connection.class": MultiversionCollectorTest.simpleClass("net/minecraft/network/Connection", Opcodes.ACC_PUBLIC, "java/lang/Object", null, [
						[name: "<init>", desc: "()V", access: Opcodes.ACC_PUBLIC],
						[name: "send", desc: "(I)V", access: Opcodes.ACC_PRIVATE],
						[name: "relay", desc: "(I)V", access: Opcodes.ACC_PROTECTED]
				])
		])
		def stubJar = File.createTempFile("stub", ".jar").toPath()
		stubJar.toFile().delete()
		def sourcesJar = File.createTempFile("stub-sources", ".jar").toPath()
		sourcesJar.toFile().delete()

		when:
		collector.addVersion("1.20.1", jar)
		new MultiversionStubGenerator().generate(collector, stubJar, sourcesJar, "com.example.Constants")
		def classNode = new ClassNode()
		new ClassReader(ZipUtils.unpack(stubJar, "net/minecraft/network/Connection.class")).accept(classNode, 0)
		def source = new String(ZipUtils.unpack(sourcesJar, "net/minecraft/network/Connection.java"))

		then:
		classNode.methods*.name.containsAll(["send", "relay"])
		source.contains("private void send(int arg0)")
		source.contains("protected void relay(int arg0)")
	}

	def "preserves notnull and nullable annotations"() {
		given:
		def collector = new MultiversionCollector()
		def jar = MultiversionCollectorTest.createJar([
				"net/minecraft/example/NullableExample.class": annotatedClass(
						"net/minecraft/example/NullableExample",
						[
								[name: "value", desc: "Ljava/lang/String;", access: Opcodes.ACC_PRIVATE, annotations: ["Lorg/jetbrains/annotations/Nullable;"]]
						],
						[
								[name: "name", desc: "()Ljava/lang/String;", access: Opcodes.ACC_PUBLIC, annotations: ["Lorg/jetbrains/annotations/NotNull;"]]
						]
				)
		])
		def stubJar = File.createTempFile("stub", ".jar").toPath()
		stubJar.toFile().delete()
		def sourcesJar = File.createTempFile("stub-sources", ".jar").toPath()
		sourcesJar.toFile().delete()

		when:
		collector.addVersion("1.20.1", jar)
		new MultiversionStubGenerator().generate(collector, stubJar, sourcesJar, "com.example.Constants")
		def classNode = new ClassNode()
		new ClassReader(ZipUtils.unpack(stubJar, "net/minecraft/example/NullableExample.class")).accept(classNode, 0)
		def source = new String(ZipUtils.unpack(sourcesJar, "net/minecraft/example/NullableExample.java"))

		then:
		source.contains("@org.jetbrains.annotations.Nullable")
		source.contains("@org.jetbrains.annotations.NotNull")
		classNode.fields.any { it.name == "value" && it.visibleAnnotations?.any { annotation -> annotation.desc == "Lorg/jetbrains/annotations/Nullable;" } }
		classNode.methods.any { it.name == "name" && it.visibleAnnotations?.any { annotation -> annotation.desc == "Lorg/jetbrains/annotations/NotNull;" } }
	}

	def "drops target-only interfaces from generated stubs"() {
		given:
		def collector = new MultiversionCollector()
		def v1Jar = MultiversionCollectorTest.createJar([
				"net/minecraft/example/Level.class": MultiversionCollectorTest.simpleClass("net/minecraft/example/Level", Opcodes.ACC_PUBLIC, "java/lang/Object", null)
		])
		def v2Jar = MultiversionCollectorTest.createJar([
				"net/minecraft/example/Level.class": MultiversionCollectorTest.simpleClass("net/minecraft/example/Level", Opcodes.ACC_PUBLIC, "java/lang/Object", [
						"net/fabricmc/fabric/api/attachment/v1/AttachmentTarget"
				])
		])
		def stubJar = File.createTempFile("stub", ".jar").toPath()
		stubJar.toFile().delete()
		def sourcesJar = File.createTempFile("stub-sources", ".jar").toPath()
		sourcesJar.toFile().delete()

		when:
		collector.addVersion("1.20.1", v1Jar)
		collector.addVersion("1.21.1", v2Jar)
		new MultiversionStubGenerator().generate(collector, stubJar, sourcesJar, "com.example.Constants")
		def classNode = new org.objectweb.asm.tree.ClassNode()
		new ClassReader(ZipUtils.unpack(stubJar, "net/minecraft/example/Level.class")).accept(classNode, 0)
		def source = new String(ZipUtils.unpack(sourcesJar, "net/minecraft/example/Level.java"))

		then:
		classNode.interfaces.isEmpty()
		!source.contains("AttachmentTarget")
	}

	def "keeps minecraft classes and drops platform-only types"() {
		given:
		def collector = new MultiversionCollector()
		def jar = MultiversionCollectorTest.createJar([
				"net/minecraft/client/resources/model/BakedModel.class": MultiversionCollectorTest.simpleClass(
						"net/minecraft/client/resources/model/BakedModel",
						Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
						"java/lang/Object",
						["net/fabricmc/fabric/api/renderer/v1/model/FabricBakedModel"],
						[
								[name: "useAmbientOcclusion", desc: "()Z", access: Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT]
						]
				),
				"net/minecraft/client/renderer/MultiBufferSource.class": MultiversionCollectorTest.simpleClass(
						"net/minecraft/client/renderer/MultiBufferSource",
						Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
						"java/lang/Object",
						[],
						[
								[name: "getBuffer", desc: "()V", access: Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT]
						]
				),
				"net/minecraftforge/client/extensions/IForgeBakedModel.class": MultiversionCollectorTest.simpleClass(
						"net/minecraftforge/client/extensions/IForgeBakedModel",
						Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
						"java/lang/Object",
						[],
						[]
				)
		])
		collector.addVersion("1.20.1", jar)
		def stubJar = File.createTempFile("stub", ".jar").toPath()
		stubJar.toFile().delete()

		when:
		new MultiversionStubGenerator().generateClassesOnly(collector, stubJar, "com.example.Constants")
		def bakedModelNode = new org.objectweb.asm.tree.ClassNode()
		new ClassReader(ZipUtils.unpack(stubJar, "net/minecraft/client/resources/model/BakedModel.class")).accept(bakedModelNode, 0)

		then:
		ZipUtils.contains(stubJar, "net/minecraft/client/resources/model/BakedModel.class")
		ZipUtils.contains(stubJar, "net/minecraft/client/renderer/MultiBufferSource.class")
		!ZipUtils.contains(stubJar, "net/minecraftforge/client/extensions/IForgeBakedModel.class")
		!ZipUtils.contains(stubJar, "net/neoforged/neoforge/client/extensions/IBakedModelExtension.class")
		bakedModelNode.interfaces.isEmpty()
	}

	def "writes nest host and nest members for inner classes"() {
		given:
		def collector = new MultiversionCollector()
		def jar = MultiversionCollectorTest.createJar([
				"net/minecraft/example/Outer.class": MultiversionCollectorTest.simpleClass("net/minecraft/example/Outer", Opcodes.ACC_PUBLIC, "java/lang/Object", null),
				"net/minecraft/example/Outer\$Inner.class": MultiversionCollectorTest.simpleClass("net/minecraft/example/Outer\$Inner", Opcodes.ACC_PUBLIC, "java/lang/Object", null),
				"net/minecraft/example/Outer\$Inner\$Nested.class": MultiversionCollectorTest.simpleClass("net/minecraft/example/Outer\$Inner\$Nested", Opcodes.ACC_PUBLIC, "java/lang/Object", null)
		])
		collector.addVersion("1.20.1", jar)
		def stubJar = File.createTempFile("stub", ".jar").toPath()
		stubJar.toFile().delete()
		def sourcesJar = File.createTempFile("stub-sources", ".jar").toPath()
		sourcesJar.toFile().delete()

		when:
		new MultiversionStubGenerator().generate(collector, stubJar, sourcesJar, "com.example.Constants")
		def outerBytes = ZipUtils.unpack(stubJar, "net/minecraft/example/Outer.class")
		def innerBytes = ZipUtils.unpack(stubJar, "net/minecraft/example/Outer\$Inner.class")
		def nestedBytes = ZipUtils.unpack(stubJar, "net/minecraft/example/Outer\$Inner\$Nested.class")
		def nestMembers = [] as Set
		def innerNestHost = null
		def nestedNestHost = null
		new ClassReader(outerBytes).accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
			@Override
			void visitNestMember(String nestMember) {
				nestMembers.add(nestMember)
			}
		}, 0)
		new ClassReader(innerBytes).accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
			@Override
			void visitNestHost(String nestHost) {
				innerNestHost = nestHost
			}
		}, 0)
		new ClassReader(nestedBytes).accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
			@Override
			void visitNestHost(String nestHost) {
				nestedNestHost = nestHost
			}
		}, 0)

		then:
		nestMembers == ["net/minecraft/example/Outer\$Inner", "net/minecraft/example/Outer\$Inner\$Nested"] as Set
		innerNestHost == "net/minecraft/example/Outer"
		nestedNestHost == "net/minecraft/example/Outer"
	}

	def "writes referenced inner classes into inner class table"() {
		given:
		def collector = new MultiversionCollector()
		def jar = MultiversionCollectorTest.createJar([
				"net/minecraft/example/Owner.class": MultiversionCollectorTest.simpleClass("net/minecraft/example/Owner", Opcodes.ACC_PUBLIC, "java/lang/Object", null),
				"net/minecraft/example/Owner\$Inner.class": MultiversionCollectorTest.simpleClass("net/minecraft/example/Owner\$Inner", Opcodes.ACC_PUBLIC, "java/lang/Object", null),
				"net/minecraft/example/Builder.class": MultiversionCollectorTest.simpleClass("net/minecraft/example/Builder", Opcodes.ACC_PUBLIC, "java/lang/Object", null, [
						[name: "<init>", desc: "()V", access: Opcodes.ACC_PUBLIC],
						[name: "accept", desc: "(Lnet/minecraft/example/Owner\$Inner;)V", access: Opcodes.ACC_PUBLIC]
				])
		])
		collector.addVersion("1.20.1", jar)
		def stubJar = File.createTempFile("stub", ".jar").toPath()
		stubJar.toFile().delete()
		def sourcesJar = File.createTempFile("stub-sources", ".jar").toPath()
		sourcesJar.toFile().delete()

		when:
		new MultiversionStubGenerator().generate(collector, stubJar, sourcesJar, "com.example.Constants")
		def builderBytes = ZipUtils.unpack(stubJar, "net/minecraft/example/Builder.class")
		def innerClasses = [] as Set
		new ClassReader(builderBytes).accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
			@Override
			void visitInnerClass(String name, String outerName, String innerName, int access) {
				innerClasses.add(name)
			}
		}, 0)

		then:
		innerClasses.contains("net/minecraft/example/Owner\$Inner")
	}

	private static byte[] annotatedClass(String name, List<Map<String, Object>> fields, List<Map<String, Object>> methods) {
		def writer = new ClassWriter(0)
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null)

		fields.each { field ->
			FieldVisitor visitor = writer.visitField(field.access as int, field.name as String, field.desc as String, null, null)

			(field.annotations as List<String>).each { annotation ->
				visitor.visitAnnotation(annotation, true).visitEnd()
			}

			visitor.visitEnd()
		}

		methods.each { method ->
			MethodVisitor visitor = writer.visitMethod(method.access as int, method.name as String, method.desc as String, null, null)

			(method.annotations as List<String>).each { annotation ->
				visitor.visitAnnotation(annotation, true).visitEnd()
			}

			if ((method.access as int & Opcodes.ACC_ABSTRACT) == 0) {
				visitor.visitCode()

				if (method.name == "<init>") {
					visitor.visitVarInsn(Opcodes.ALOAD, 0)
					visitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
					visitor.visitInsn(Opcodes.RETURN)
					visitor.visitMaxs(1, 1)
				} else {
					visitor.visitInsn(Opcodes.ARETURN)
					visitor.visitMaxs(1, 1)
				}
			}

			visitor.visitEnd()
		}

		writer.visitEnd()
		return writer.toByteArray()
	}
}
