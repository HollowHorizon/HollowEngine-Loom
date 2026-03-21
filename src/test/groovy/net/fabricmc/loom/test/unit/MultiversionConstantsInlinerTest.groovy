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
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import spock.lang.Specification

import net.fabricmc.loom.configuration.multiversion.MultiversionConstantsInliner

class MultiversionConstantsInlinerTest extends Specification {
	def "equality comparison is folded into the branch itself"() {
		given:
		def writer = new ClassWriter(0)
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "example/Test", null, "java/lang/Object", null)

		def init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
		init.visitCode()
		init.visitVarInsn(Opcodes.ALOAD, 0)
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
		init.visitInsn(Opcodes.RETURN)
		init.visitMaxs(1, 1)
		init.visitEnd()

		def method = writer.visitMethod(Opcodes.ACC_PUBLIC, "testHello", "()V", null, null)
		def falseLabel = new Label()
		def endLabel = new Label()
		method.visitCode()
		method.visitFieldInsn(Opcodes.GETSTATIC, "com/example/Constants", "MINECRAFT", "I")
		method.visitFieldInsn(Opcodes.GETSTATIC, "com/example/Constants", "V1_21_1", "I")
		method.visitJumpInsn(Opcodes.IF_ICMPNE, falseLabel)
		method.visitInsn(Opcodes.RETURN)
		method.visitLabel(falseLabel)
		method.visitInsn(Opcodes.RETURN)
		method.visitLabel(endLabel)
		method.visitMaxs(1, 1)
		method.visitEnd()

		writer.visitEnd()
		def inliner = new MultiversionConstantsInliner("com.example.Constants", "1.21.1", ["1.20.1", "1.21.1"])

		when:
		def output = inliner.inline(writer.toByteArray())
		def classNode = new ClassNode()
		new ClassReader(output).accept(classNode, 0)
		def instructions = classNode.methods.find { it.name == "testHello" }.instructions.toArray()
		def opcodes = instructions
				.findAll { it.type != AbstractInsnNode.FRAME && it.type != AbstractInsnNode.LINE && it.type != AbstractInsnNode.LABEL }
				.collect { it.opcode }

		then:
		!opcodes.contains(Opcodes.GETSTATIC)
		!opcodes.contains(Opcodes.IF_ICMPNE)
		!opcodes.contains(Opcodes.IFNE)
		opcodes.every { it in [Opcodes.RETURN, Opcodes.GOTO] }
		opcodes.count { it == Opcodes.RETURN } == 2
	}

	def "version comparison is folded into an unconditional jump"() {
		given:
		def writer = new ClassWriter(0)
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "example/TestCompare", null, "java/lang/Object", null)

		def init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
		init.visitCode()
		init.visitVarInsn(Opcodes.ALOAD, 0)
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
		init.visitInsn(Opcodes.RETURN)
		init.visitMaxs(1, 1)
		init.visitEnd()

		def method = writer.visitMethod(Opcodes.ACC_PUBLIC, "testCompare", "()V", null, null)
		def falseLabel = new Label()
		method.visitCode()
		method.visitFieldInsn(Opcodes.GETSTATIC, "com/example/Constants", "MINECRAFT_VERSION", "I")
		method.visitFieldInsn(Opcodes.GETSTATIC, "com/example/Constants", "V1_21_1", "I")
		method.visitJumpInsn(Opcodes.IF_ICMPLT, falseLabel)
		method.visitInsn(Opcodes.RETURN)
		method.visitLabel(falseLabel)
		method.visitInsn(Opcodes.RETURN)
		method.visitMaxs(2, 1)
		method.visitEnd()

		writer.visitEnd()
		def inliner = new MultiversionConstantsInliner("com.example.Constants", "1.20.1", ["1.20.1", "1.21.1"])

		when:
		def output = inliner.inline(writer.toByteArray())
		def classNode = new ClassNode()
		new ClassReader(output).accept(classNode, 0)
		def instructions = classNode.methods.find { it.name == "testCompare" }.instructions.toArray()
		def opcodes = instructions
				.findAll { it.type != AbstractInsnNode.FRAME && it.type != AbstractInsnNode.LINE && it.type != AbstractInsnNode.LABEL }
				.collect { it.opcode }

		then:
		!opcodes.contains(Opcodes.GETSTATIC)
		!opcodes.contains(Opcodes.IF_ICMPLT)
		opcodes[0] == Opcodes.GOTO
	}

	def "reversed version comparison is folded into an unconditional jump"() {
		given:
		def writer = new ClassWriter(0)
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "example/TestCompareReversed", null, "java/lang/Object", null)

		def init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
		init.visitCode()
		init.visitVarInsn(Opcodes.ALOAD, 0)
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
		init.visitInsn(Opcodes.RETURN)
		init.visitMaxs(1, 1)
		init.visitEnd()

		def method = writer.visitMethod(Opcodes.ACC_PUBLIC, "testCompare", "()V", null, null)
		def falseLabel = new Label()
		method.visitCode()
		method.visitFieldInsn(Opcodes.GETSTATIC, "com/example/Constants", "V1_21_1", "I")
		method.visitFieldInsn(Opcodes.GETSTATIC, "com/example/Constants", "MINECRAFT_VERSION", "I")
		method.visitJumpInsn(Opcodes.IF_ICMPGT, falseLabel)
		method.visitInsn(Opcodes.RETURN)
		method.visitLabel(falseLabel)
		method.visitInsn(Opcodes.RETURN)
		method.visitMaxs(2, 1)
		method.visitEnd()

		writer.visitEnd()
		def inliner = new MultiversionConstantsInliner("com.example.Constants", "1.20.1", ["1.20.1", "1.21.1"])

		when:
		def output = inliner.inline(writer.toByteArray())
		def classNode = new ClassNode()
		new ClassReader(output).accept(classNode, 0)
		def instructions = classNode.methods.find { it.name == "testCompare" }.instructions.toArray()
		def opcodes = instructions
				.findAll { it.type != AbstractInsnNode.FRAME && it.type != AbstractInsnNode.LINE && it.type != AbstractInsnNode.LABEL }
				.collect { it.opcode }

		then:
		!opcodes.contains(Opcodes.GETSTATIC)
		!opcodes.contains(Opcodes.IF_ICMPGT)
		opcodes[0] == Opcodes.GOTO
	}
}
