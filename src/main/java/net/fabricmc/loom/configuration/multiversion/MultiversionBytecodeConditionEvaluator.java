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

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;

final class MultiversionBytecodeConditionEvaluator {
	private final String constantsClassInternalName;
	private final List<String> availableVersions;
	private final Map<String, Integer> packedVersions = new LinkedHashMap<>();
	private final Map<String, Integer> constantFields = new LinkedHashMap<>();

	MultiversionBytecodeConditionEvaluator(String constantsClassName, Collection<String> availableVersions) {
		this.constantsClassInternalName = constantsClassName.replace('.', '/');
		this.availableVersions = List.copyOf(availableVersions);

		for (String version : this.availableVersions) {
			final int packed = MultiversionConstantsGenerator.VersionParts.parse(version).packed();
			packedVersions.put(version, packed);
			constantFields.put("V" + version.replaceAll("[^A-Za-z0-9]", "_"), packed);
		}
	}

	BranchEvaluation evaluate(AbstractInsnNode[] instructions, int branchIndex, BitSet currentVersions) {
		final AbstractInsnNode branchInstruction = instructions[branchIndex];

		if (!(branchInstruction instanceof JumpInsnNode jumpInsn)) {
			return null;
		}

		final int opcode = jumpInsn.getOpcode();

		if (opcode == Opcodes.IFEQ || opcode == Opcodes.IFNE) {
			final ParsedInt parsedInt = parseIntBefore(instructions, branchIndex);

			if (parsedInt == null) {
				return null;
			}

			return buildEvaluation(currentVersions, packed -> (opcode == Opcodes.IFNE) == (parsedInt.expression().eval(packed) != 0), parsedInt.consumedInstructions());
		}

		if (opcode == Opcodes.IF_ICMPEQ || opcode == Opcodes.IF_ICMPNE || opcode == Opcodes.IF_ICMPLT
				|| opcode == Opcodes.IF_ICMPGE || opcode == Opcodes.IF_ICMPGT || opcode == Opcodes.IF_ICMPLE) {
			final ParsedInt right = parseIntBefore(instructions, branchIndex);

			if (right == null) {
				return null;
			}

			final ParsedInt left = parseIntBefore(instructions, right.nextIndexExclusive());

			if (left == null) {
				return null;
			}

			final Set<AbstractInsnNode> consumed = new LinkedHashSet<>(left.consumedInstructions());
			consumed.addAll(right.consumedInstructions());
			return buildEvaluation(currentVersions, packed -> compare(opcode, left.expression().eval(packed), right.expression().eval(packed)), consumed);
		}

		return null;
	}

	Boolean constantBooleanResult(AbstractInsnNode[] instructions, AbstractInsnNode instruction, BitSet currentVersions) {
		final int branchIndex = indexOf(instructions, instruction);

		if (branchIndex < 0) {
			return null;
		}

		final ParsedInt parsedInt = parseIntBefore(instructions, branchIndex + 1);
		return parsedInt == null ? null : collapseToBoolean(buildEvaluation(currentVersions, packed -> parsedInt.expression().eval(packed) != 0, parsedInt.consumedInstructions()));
	}

	private ParsedInt parseIntBefore(AbstractInsnNode[] instructions, int beforeIndexExclusive) {
		final int index = previousMeaningfulIndex(instructions, beforeIndexExclusive);
		return index < 0 ? null : parseIntAt(instructions, index);
	}

	private ParsedInt parseIntAt(AbstractInsnNode[] instructions, int index) {
		final AbstractInsnNode instruction = instructions[index];
		final Integer literal = readLiteral(instruction);

		if (literal != null) {
			return new ParsedInt(packed -> literal, index, Set.of(instruction));
		}

		if (instruction instanceof FieldInsnNode fieldInsn
				&& fieldInsn.getOpcode() == Opcodes.GETSTATIC
				&& constantsClassInternalName.equals(fieldInsn.owner)
				&& "I".equals(fieldInsn.desc)) {
			final IntExpression expression = switch (fieldInsn.name) {
			case "MINECRAFT_VERSION", "MINECRAFT" -> packed -> packed;
			case "MC_MAJOR" -> packed -> packed / 1000;
			case "MC_MINOR" -> packed -> (packed % 1000) / 10;
			case "MC_PATCH" -> packed -> packed % 10;
			default -> {
				final Integer constant = constantFields.get(fieldInsn.name);
				yield constant == null ? null : packed -> constant;
			}
			};

			if (expression != null) {
				return new ParsedInt(expression, index, Set.of(instruction));
			}
		}

		return switch (instruction.getOpcode()) {
		case Opcodes.INEG -> unary(instructions, index, value -> -value, instruction);
		case Opcodes.IADD -> binary(instructions, index, Integer::sum, instruction);
		case Opcodes.ISUB -> binary(instructions, index, (left, right) -> left - right, instruction);
		case Opcodes.IMUL -> binary(instructions, index, (left, right) -> left * right, instruction);
		case Opcodes.IDIV -> binary(instructions, index, (left, right) -> right == 0 ? 0 : left / right, instruction);
		case Opcodes.IREM -> binary(instructions, index, (left, right) -> right == 0 ? 0 : left % right, instruction);
		case Opcodes.IAND -> binary(instructions, index, (left, right) -> left & right, instruction);
		case Opcodes.IOR -> binary(instructions, index, (left, right) -> left | right, instruction);
		case Opcodes.IXOR -> binary(instructions, index, (left, right) -> left ^ right, instruction);
		case Opcodes.ISHL -> binary(instructions, index, (left, right) -> left << right, instruction);
		case Opcodes.ISHR -> binary(instructions, index, (left, right) -> left >> right, instruction);
		case Opcodes.IUSHR -> binary(instructions, index, (left, right) -> left >>> right, instruction);
		default -> null;
		};
	}

	private ParsedInt unary(AbstractInsnNode[] instructions, int operatorIndex, IntUnaryOperator operator, AbstractInsnNode operatorInstruction) {
		final ParsedInt operand = parseIntBefore(instructions, operatorIndex);

		if (operand == null) {
			return null;
		}

		final Set<AbstractInsnNode> consumed = new LinkedHashSet<>(operand.consumedInstructions());
		consumed.add(operatorInstruction);
		return new ParsedInt(packed -> operator.apply(operand.expression().eval(packed)), operand.nextIndexExclusive(), consumed);
	}

	private ParsedInt binary(AbstractInsnNode[] instructions, int operatorIndex, IntBinaryOperator operator, AbstractInsnNode operatorInstruction) {
		final ParsedInt right = parseIntBefore(instructions, operatorIndex);

		if (right == null) {
			return null;
		}

		final ParsedInt left = parseIntBefore(instructions, right.nextIndexExclusive());

		if (left == null) {
			return null;
		}

		final Set<AbstractInsnNode> consumed = new LinkedHashSet<>(left.consumedInstructions());
		consumed.addAll(right.consumedInstructions());
		consumed.add(operatorInstruction);
		return new ParsedInt(packed -> operator.apply(left.expression().eval(packed), right.expression().eval(packed)), left.nextIndexExclusive(), consumed);
	}

	private BranchEvaluation buildEvaluation(BitSet currentVersions, BooleanExpression condition, Collection<AbstractInsnNode> consumedInstructions) {
		final BitSet jumpVersions = new BitSet(availableVersions.size());

		for (int bit = currentVersions.nextSetBit(0); bit >= 0; bit = currentVersions.nextSetBit(bit + 1)) {
			if (condition.test(packedVersions.get(availableVersions.get(bit)))) {
				jumpVersions.set(bit);
			}
		}

		final BitSet fallthroughVersions = copy(currentVersions);
		fallthroughVersions.andNot(jumpVersions);
		return new BranchEvaluation(jumpVersions, fallthroughVersions, List.copyOf(consumedInstructions));
	}

	private static Boolean collapseToBoolean(BranchEvaluation evaluation) {
		if (evaluation == null) {
			return null;
		}

		if (!evaluation.jumpVersions().isEmpty() && evaluation.fallthroughVersions().isEmpty()) {
			return true;
		}

		if (evaluation.jumpVersions().isEmpty() && !evaluation.fallthroughVersions().isEmpty()) {
			return false;
		}

		return null;
	}

	private static int previousMeaningfulIndex(AbstractInsnNode[] instructions, int currentIndex) {
		for (int index = currentIndex - 1; index >= 0; index--) {
			final int type = instructions[index].getType();

			if (type != AbstractInsnNode.FRAME && type != AbstractInsnNode.LINE && type != AbstractInsnNode.LABEL) {
				return index;
			}
		}

		return -1;
	}

	private static Integer readLiteral(AbstractInsnNode instruction) {
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

	private static boolean compare(int opcode, int left, int right) {
		return switch (opcode) {
		case Opcodes.IF_ICMPEQ -> left == right;
		case Opcodes.IF_ICMPNE -> left != right;
		case Opcodes.IF_ICMPLT -> left < right;
		case Opcodes.IF_ICMPGE -> left >= right;
		case Opcodes.IF_ICMPGT -> left > right;
		case Opcodes.IF_ICMPLE -> left <= right;
		default -> false;
		};
	}

	private static int indexOf(AbstractInsnNode[] instructions, AbstractInsnNode target) {
		for (int index = 0; index < instructions.length; index++) {
			if (instructions[index] == target) {
				return index;
			}
		}

		return -1;
	}

	private static BitSet copy(BitSet source) {
		return (BitSet) source.clone();
	}

	record BranchEvaluation(BitSet jumpVersions, BitSet fallthroughVersions, List<AbstractInsnNode> consumedInstructions) {
	}

	private record ParsedInt(IntExpression expression, int nextIndexExclusive, Set<AbstractInsnNode> consumedInstructions) {
	}

	@FunctionalInterface
	private interface IntExpression {
		int eval(int packedVersion);
	}

	@FunctionalInterface
	private interface BooleanExpression {
		boolean test(int packedVersion);
	}

	@FunctionalInterface
	private interface IntUnaryOperator {
		int apply(int value);
	}

	@FunctionalInterface
	private interface IntBinaryOperator {
		int apply(int left, int right);
	}
}
