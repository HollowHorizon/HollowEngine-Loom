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

package net.fabricmc.loom.configuration.multiversion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

public final class MultiversionConstantsInliner {
	private final String constantsClassInternalName;
	private final Map<String, Integer> constantValues = new LinkedHashMap<>();

	public MultiversionConstantsInliner(String constantsClassName, String targetVersion, List<String> availableVersions) {
		this.constantsClassInternalName = constantsClassName.replace('.', '/');
		final var target = MultiversionConstantsGenerator.VersionParts.parse(targetVersion);
		constantValues.put("MINECRAFT_VERSION", target.packed());
		constantValues.put("MINECRAFT", target.packed());
		constantValues.put("MC_MAJOR", target.major());
		constantValues.put("MC_MINOR", target.minor());
		constantValues.put("MC_PATCH", target.patch());

		for (String version : availableVersions) {
			constantValues.put("V" + version.replaceAll("[^A-Za-z0-9]", "_"), MultiversionConstantsGenerator.VersionParts.parse(version).packed());
		}
	}

	public void inlineDirectories(Iterable<Path> directories) throws IOException {
		for (Path directory : directories) {
			if (directory == null || Files.notExists(directory)) {
				continue;
			}

			try (var stream = Files.walk(directory)) {
				stream.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".class"))
						.forEach(this::inlineClassFile);
			}
		}
	}

	public byte[] inline(byte[] input) {
		final ClassNode classNode = new ClassNode();
		new ClassReader(input).accept(classNode, 0);

		boolean changed = false;

		for (MethodNode method : classNode.methods) {
			changed |= inlineMethod(method);
		}

		if (!changed) {
			return input;
		}

		final ClassWriter writer = new ClassWriter(0);
		classNode.accept(writer);
		return writer.toByteArray();
	}

	private void inlineClassFile(Path path) {
		try {
			final byte[] input = Files.readAllBytes(path);
			final byte[] output = inline(input);

			if (output != input) {
				Files.write(path, output);
			}
		} catch (IOException e) {
			throw new RuntimeException("Failed to inline multiversion constants in " + path, e);
		}
	}

	private boolean inlineMethod(MethodNode method) {
		boolean changed = false;
		final InsnList instructions = method.instructions;

		for (AbstractInsnNode instruction = instructions.getFirst(); instruction != null; ) {
			final AbstractInsnNode next = instruction.getNext();

			if (instruction instanceof FieldInsnNode fieldInsn && fieldInsn.getOpcode() == Opcodes.GETSTATIC
					&& constantsClassInternalName.equals(fieldInsn.owner) && "I".equals(fieldInsn.desc)) {
				final Integer value = constantValues.get(fieldInsn.name);

				if (value != null) {
					instructions.set(fieldInsn, intInstruction(value));
					changed = true;
				}
			} else if (instruction instanceof JumpInsnNode jumpInsn) {
				changed |= simplifyConditionalJump(instructions, jumpInsn);
			} else if (instruction instanceof MethodInsnNode methodInsn
					&& methodInsn.getOpcode() == Opcodes.INVOKESTATIC
					&& constantsClassInternalName.equals(methodInsn.owner)
					&& "(I)Z".equals(methodInsn.desc)) {
				if (isConditionalJump(nextMeaningful(instruction))) {
					instruction = next;
					continue;
				}

				final AbstractInsnNode previous = previousMeaningful(instruction);
				final Integer value = readIntConstant(previous);

				if (value != null) {
					final boolean result = switch (methodInsn.name) {
					case "is" -> constantValues.get("MINECRAFT_VERSION").intValue() == value.intValue();
					case "isAtLeast" -> constantValues.get("MINECRAFT_VERSION") >= value;
					case "isAtMost" -> constantValues.get("MINECRAFT_VERSION") <= value;
					default -> false;
					};

					if (isConstantInstruction(previous)) {
						instructions.remove(previous);
						instructions.set(methodInsn, new InsnNode(result ? Opcodes.ICONST_1 : Opcodes.ICONST_0));
						changed = true;
					}
				}
			}

			instruction = next;
		}

		return changed;
	}

	private boolean simplifyConditionalJump(InsnList instructions, JumpInsnNode jumpInsn) {
		final int opcode = jumpInsn.getOpcode();

		if (opcode == Opcodes.IFEQ || opcode == Opcodes.IFNE) {
			final AbstractInsnNode helperInstruction = previousMeaningful(jumpInsn);

			if (helperInstruction instanceof MethodInsnNode methodInsn
					&& methodInsn.getOpcode() == Opcodes.INVOKESTATIC
					&& constantsClassInternalName.equals(methodInsn.owner)
					&& "(I)Z".equals(methodInsn.desc)) {
				final AbstractInsnNode argumentInstruction = previousMeaningful(methodInsn);
				final Integer value = readIntConstant(argumentInstruction);

				if (value != null && isConstantInstruction(argumentInstruction)) {
					final boolean result = switch (methodInsn.name) {
					case "is" -> constantValues.get("MINECRAFT_VERSION").intValue() == value.intValue();
					case "isAtLeast" -> constantValues.get("MINECRAFT_VERSION") >= value;
					case "isAtMost" -> constantValues.get("MINECRAFT_VERSION") <= value;
					default -> false;
					};
					removeInstruction(instructions, argumentInstruction);
					removeInstruction(instructions, methodInsn);
					applyResolvedJump(instructions, jumpInsn, opcode, result);
					return true;
				}
			}
		}

		if (opcode == Opcodes.IF_ICMPEQ || opcode == Opcodes.IF_ICMPNE || opcode == Opcodes.IF_ICMPLT
				|| opcode == Opcodes.IF_ICMPGE || opcode == Opcodes.IF_ICMPGT || opcode == Opcodes.IF_ICMPLE) {
			final AbstractInsnNode rightInstruction = previousMeaningful(jumpInsn);
			final AbstractInsnNode leftInstruction = previousMeaningful(rightInstruction);
			final Integer right = readIntConstant(rightInstruction);
			final Integer left = readIntConstant(leftInstruction);

			if (left != null && right != null && isConstantInstruction(leftInstruction) && isConstantInstruction(rightInstruction)) {
				final boolean result = switch (opcode) {
				case Opcodes.IF_ICMPEQ -> left.intValue() == right.intValue();
				case Opcodes.IF_ICMPNE -> left.intValue() != right.intValue();
				case Opcodes.IF_ICMPLT -> left.intValue() < right.intValue();
				case Opcodes.IF_ICMPGE -> left.intValue() >= right.intValue();
				case Opcodes.IF_ICMPGT -> left.intValue() > right.intValue();
				case Opcodes.IF_ICMPLE -> left.intValue() <= right.intValue();
				default -> false;
				};
				removeInstruction(instructions, leftInstruction);
				removeInstruction(instructions, rightInstruction);
				applyResolvedJump(instructions, jumpInsn, opcode, result);
				return true;
			}
		}

		return false;
	}

	private static void applyResolvedJump(InsnList instructions, JumpInsnNode jumpInsn, int opcode, boolean result) {
		final boolean jumpTaken = switch (opcode) {
		case Opcodes.IFEQ -> !result;
		case Opcodes.IFNE -> result;
		default -> result;
		};

		if (jumpTaken) {
			instructions.set(jumpInsn, new JumpInsnNode(Opcodes.GOTO, jumpInsn.label));
		} else {
			instructions.remove(jumpInsn);
		}
	}

	private static AbstractInsnNode previousMeaningful(AbstractInsnNode instruction) {
		AbstractInsnNode current = instruction.getPrevious();

		while (current != null && (current.getType() == AbstractInsnNode.FRAME || current.getType() == AbstractInsnNode.LINE || current.getType() == AbstractInsnNode.LABEL)) {
			current = current.getPrevious();
		}

		return current;
	}

	private static AbstractInsnNode nextMeaningful(AbstractInsnNode instruction) {
		AbstractInsnNode current = instruction == null ? null : instruction.getNext();

		while (current != null && (current.getType() == AbstractInsnNode.FRAME || current.getType() == AbstractInsnNode.LINE || current.getType() == AbstractInsnNode.LABEL)) {
			current = current.getNext();
		}

		return current;
	}

	private static boolean isConditionalJump(AbstractInsnNode instruction) {
		if (!(instruction instanceof JumpInsnNode jumpInsn)) {
			return false;
		}

		final int opcode = jumpInsn.getOpcode();
		return opcode == Opcodes.IFEQ || opcode == Opcodes.IFNE;
	}

	private static Integer readIntConstant(AbstractInsnNode instruction) {
		if (instruction == null) {
			return null;
		}

		return switch (instruction.getOpcode()) {
		case Opcodes.ICONST_M1 -> -1;
		case Opcodes.ICONST_0 -> 0;
		case Opcodes.ICONST_1 -> 1;
		case Opcodes.ICONST_2 -> 2;
		case Opcodes.ICONST_3 -> 3;
		case Opcodes.ICONST_4 -> 4;
		case Opcodes.ICONST_5 -> 5;
		case Opcodes.BIPUSH, Opcodes.SIPUSH -> ((IntInsnNode) instruction).operand;
		default -> instruction instanceof LdcInsnNode ldcInsn && ldcInsn.cst instanceof Integer integer ? integer : null;
		};
	}

	private static boolean isConstantInstruction(AbstractInsnNode instruction) {
		if (instruction == null) {
			return false;
		}

		final int opcode = instruction.getOpcode();
		return switch (opcode) {
		case Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2, Opcodes.ICONST_3, Opcodes.ICONST_4, Opcodes.ICONST_5,
				Opcodes.BIPUSH, Opcodes.SIPUSH -> true;
		default -> instruction instanceof LdcInsnNode ldcInsn && ldcInsn.cst instanceof Integer;
		};
	}

	private static void removeInstruction(InsnList instructions, AbstractInsnNode instruction) {
		if (instruction != null && (instruction == instructions.getFirst() || instruction.getPrevious() != null || instruction.getNext() != null)) {
			instructions.remove(instruction);
		}
	}

	private static AbstractInsnNode intInstruction(int value) {
		return switch (value) {
		case -1 -> new InsnNode(Opcodes.ICONST_M1);
		case 0 -> new InsnNode(Opcodes.ICONST_0);
		case 1 -> new InsnNode(Opcodes.ICONST_1);
		case 2 -> new InsnNode(Opcodes.ICONST_2);
		case 3 -> new InsnNode(Opcodes.ICONST_3);
		case 4 -> new InsnNode(Opcodes.ICONST_4);
		case 5 -> new InsnNode(Opcodes.ICONST_5);
		default -> value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE
				? new IntInsnNode(Opcodes.BIPUSH, value)
				: value >= Short.MIN_VALUE && value <= Short.MAX_VALUE
					? new IntInsnNode(Opcodes.SIPUSH, value)
					: new LdcInsnNode(value);
		};
	}
}
