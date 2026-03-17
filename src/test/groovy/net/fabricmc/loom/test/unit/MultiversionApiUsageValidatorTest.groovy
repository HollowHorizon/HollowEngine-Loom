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
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import spock.lang.Specification

import net.fabricmc.loom.configuration.multiversion.MultiversionApiMetadata
import net.fabricmc.loom.configuration.multiversion.MultiversionApiUsageValidator

class MultiversionApiUsageValidatorTest extends Specification {
	def "rejects unguarded version specific api usage"() {
		when:
		def violations = new MultiversionApiUsageValidator(metadata(), "com.example.Constants")
				.validateClass("example/CommonBlockPos.class", classWithMethod("test", false, false, false))

		then:
		violations.size() == 1
		violations.first().contains("net/minecraft/util/math/BlockPos#max")
		violations.first().contains("[1.20.1]")
	}

	def "allows guarded helper call"() {
		expect:
		new MultiversionApiUsageValidator(metadata(), "com.example.Constants")
				.validateClass("example/CommonBlockPos.class", classWithMethod("test", true, false, false))
				.isEmpty()
	}

	def "allows direct version compare guard"() {
		expect:
		new MultiversionApiUsageValidator(metadata(), "com.example.Constants")
				.validateClass("example/CommonBlockPos.class", classWithMethod("test", false, true, false))
				.isEmpty()
	}

	def "allows method level requires api annotation"() {
		expect:
		new MultiversionApiUsageValidator(metadata(), "com.example.Constants")
				.validateClass("example/CommonBlockPos.class", classWithMethod("test", false, false, true))
				.isEmpty()
	}

	private static MultiversionApiMetadata metadata() {
		def builder = MultiversionApiMetadata.builder(["1.20.1", "1.21.1"] as Set)
		builder.addClass("net/minecraft/util/math/BlockPos", ["1.20.1", "1.21.1"] as Set)
		builder.addMethod("net/minecraft/util/math/BlockPos", "max", "(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/util/math/BlockPos;", ["1.21.1"] as Set)
		return builder.build()
	}

	private static byte[] classWithMethod(String methodName, boolean useHelperGuard, boolean useDirectCompare, boolean annotateMethod) {
		def writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES)
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "example/CommonBlockPos", null, "java/lang/Object", null)

		def ctor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
		ctor.visitCode()
		ctor.visitVarInsn(Opcodes.ALOAD, 0)
		ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
		ctor.visitInsn(Opcodes.RETURN)
		ctor.visitMaxs(0, 0)
		ctor.visitEnd()

		MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, methodName, "()V", null, null)

		if (annotateMethod) {
			AnnotationVisitor annotation = method.visitAnnotation("Lmultiversion/api/RequiresApi;", false)
			AnnotationVisitor values = annotation.visitArray("value")
			values.visit(null, "1.21.1")
			values.visitEnd()
			annotation.visitEnd()
		}

		method.visitCode()
		Label end = new Label()

		if (useHelperGuard) {
			method.visitIntInsn(Opcodes.SIPUSH, 1211)
			method.visitMethodInsn(Opcodes.INVOKESTATIC, "com/example/Constants", "is", "(I)Z", false)
			method.visitJumpInsn(Opcodes.IFEQ, end)
		} else if (useDirectCompare) {
			method.visitFieldInsn(Opcodes.GETSTATIC, "com/example/Constants", "MINECRAFT_VERSION", "I")
			method.visitIntInsn(Opcodes.SIPUSH, 1211)
			method.visitJumpInsn(Opcodes.IF_ICMPLT, end)
		}

		method.visitInsn(Opcodes.ACONST_NULL)
		method.visitInsn(Opcodes.ACONST_NULL)
		method.visitMethodInsn(Opcodes.INVOKESTATIC, "net/minecraft/util/math/BlockPos", "max", "(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/util/math/BlockPos;", false)
		method.visitInsn(Opcodes.POP)

		if (useHelperGuard || useDirectCompare) {
			method.visitLabel(end)
		}

		method.visitInsn(Opcodes.RETURN)
		method.visitMaxs(0, 0)
		method.visitEnd()

		writer.visitEnd()
		return writer.toByteArray()
	}
}
