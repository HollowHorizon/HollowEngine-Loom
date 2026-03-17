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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;

public final class MultiversionApiUsageValidator {
	private static final String REQUIRES_API_DESCRIPTOR = "L" + MultiversionStubGenerator.REQUIRES_API_INTERNAL_NAME + ";";

	private final MultiversionApiMetadata metadata;
	private final String constantsClassInternalName;
	private final List<String> availableVersions;
	private final Map<String, Integer> versionIndexes = new LinkedHashMap<>();
	private final Map<String, Integer> packedVersions = new LinkedHashMap<>();
	private final Map<String, BitSet> cachedVersionMasks = new LinkedHashMap<>();
	private final BitSet allVersions;

	public MultiversionApiUsageValidator(MultiversionApiMetadata metadata, String constantsClassName) {
		this.metadata = metadata;
		this.constantsClassInternalName = constantsClassName.replace('.', '/');
		this.availableVersions = metadata.availableVersions().stream()
				.sorted(Comparator
						.comparingInt((String version) -> MultiversionConstantsGenerator.VersionParts.parse(version).packed())
						.thenComparing(String::compareTo))
				.toList();

		for (int index = 0; index < availableVersions.size(); index++) {
			final String version = availableVersions.get(index);
			versionIndexes.put(version, index);
			packedVersions.put(version, MultiversionConstantsGenerator.VersionParts.parse(version).packed());
		}

		this.allVersions = new BitSet(availableVersions.size());
		this.allVersions.set(0, availableVersions.size());
	}

	public List<String> validateDirectories(Iterable<Path> directories) throws IOException {
		final Set<String> violations = new LinkedHashSet<>();

		for (Path directory : directories) {
			if (directory == null || Files.notExists(directory)) {
				continue;
			}

			try (var stream = Files.walk(directory)) {
				stream.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".class"))
						.forEach(path -> validateClass(path.toString(), readBytes(path), violations));
			}
		}

		return List.copyOf(violations);
	}

	public List<String> validateClass(String sourceName, byte[] bytes) {
		final Set<String> violations = new LinkedHashSet<>();
		validateClass(sourceName, bytes, violations);
		return List.copyOf(violations);
	}

	private void validateClass(String sourceName, byte[] bytes, Set<String> violations) {
		final ClassNode classNode = new ClassNode();
		new ClassReader(bytes).accept(classNode, ClassReader.SKIP_FRAMES);

		final BitSet classVersions = applyRequiresApi(copy(allVersions), classNode.visibleAnnotations, classNode.invisibleAnnotations);
		final String classLocation = classNode.name + " (" + sourceName + ")";

		validateClassReference(classNode.superName, classVersions, classLocation, violations);

		for (String interfaceName : classNode.interfaces) {
			validateClassReference(interfaceName, classVersions, classLocation, violations);
		}

		for (FieldNode field : classNode.fields) {
			final BitSet fieldVersions = applyRequiresApi(classVersions, field.visibleAnnotations, field.invisibleAnnotations);
			validateType(Type.getType(field.desc), fieldVersions, classNode.name + "#" + field.name, violations);
		}

		for (MethodNode method : classNode.methods) {
			final BitSet methodVersions = applyRequiresApi(classVersions, method.visibleAnnotations, method.invisibleAnnotations);
			validateMethodDescriptor(method.desc, methodVersions, classNode.name + "#" + method.name + method.desc, violations);

			if (method.exceptions != null) {
				for (String exception : method.exceptions) {
					validateClassReference(exception, methodVersions, classNode.name + "#" + method.name + method.desc, violations);
				}
			}

			validateMethodCode(classNode, method, methodVersions, violations);
		}
	}

	private void validateMethodCode(ClassNode classNode, MethodNode method, BitSet methodVersions, Set<String> violations) {
		final AbstractInsnNode[] instructions = method.instructions.toArray();

		if (instructions.length == 0 || methodVersions.isEmpty()) {
			return;
		}

		final Map<LabelNode, Integer> labelIndexes = new LinkedHashMap<>();

		for (int index = 0; index < instructions.length; index++) {
			if (instructions[index] instanceof LabelNode labelNode) {
				labelIndexes.put(labelNode, index);
			}
		}

		final BitSet[] reachableStates = new BitSet[instructions.length];
		final Deque<Integer> queue = new ArrayDeque<>();
		mergeState(reachableStates, 0, methodVersions, queue);

		while (!queue.isEmpty()) {
			final int index = queue.removeFirst();
			final BitSet currentVersions = reachableStates[index];

			if (currentVersions == null || currentVersions.isEmpty()) {
				continue;
			}

			final AbstractInsnNode instruction = instructions[index];
			final String location = classNode.name + "#" + method.name + method.desc;
			validateInstruction(instruction, currentVersions, location, violations);
			propagateSuccessors(instructions, labelIndexes, index, instruction, currentVersions, reachableStates, queue);
		}
	}

	private void validateInstruction(AbstractInsnNode instruction, BitSet currentVersions, String location, Set<String> violations) {
		if (instruction instanceof FieldInsnNode fieldInsn) {
			validateClassReference(fieldInsn.owner, currentVersions, location, violations);
			validateType(Type.getType(fieldInsn.desc), currentVersions, location, violations);
			validateSymbol(metadata.fields(), MultiversionApiMetadata.fieldKey(fieldInsn.owner, fieldInsn.name, fieldInsn.desc), currentVersions, location, violations);
			return;
		}

		if (instruction instanceof MethodInsnNode methodInsn) {
			validateClassReference(methodInsn.owner, currentVersions, location, violations);
			validateMethodDescriptor(methodInsn.desc, currentVersions, location, violations);
			validateSymbol(metadata.methods(), MultiversionApiMetadata.methodKey(methodInsn.owner, methodInsn.name, methodInsn.desc), currentVersions, location, violations);
			return;
		}

		if (instruction instanceof TypeInsnNode typeInsn) {
			validateClassReference(typeInsn.desc, currentVersions, location, violations);
			return;
		}

		if (instruction instanceof MultiANewArrayInsnNode multiArrayInsn) {
			validateType(Type.getType(multiArrayInsn.desc), currentVersions, location, violations);
			return;
		}

		if (instruction instanceof LdcInsnNode ldcInsn && ldcInsn.cst instanceof Type type) {
			validateType(type, currentVersions, location, violations);
			return;
		}

		if (instruction instanceof InvokeDynamicInsnNode indyInsn) {
			validateMethodDescriptor(indyInsn.desc, currentVersions, location, violations);

			if (indyInsn.bsm != null) {
				validateHandle(indyInsn.bsm, currentVersions, location, violations);
			}

			for (Object argument : indyInsn.bsmArgs) {
				if (argument instanceof Type type) {
					validateType(type, currentVersions, location, violations);
				} else if (argument instanceof Handle handle) {
					validateHandle(handle, currentVersions, location, violations);
				}
			}
		}
	}

	private void validateHandle(Handle handle, BitSet currentVersions, String location, Set<String> violations) {
		validateClassReference(handle.getOwner(), currentVersions, location, violations);

		if (handle.getTag() <= Opcodes.H_PUTSTATIC) {
			validateType(Type.getType(handle.getDesc()), currentVersions, location, violations);
			validateSymbol(metadata.fields(), MultiversionApiMetadata.fieldKey(handle.getOwner(), handle.getName(), handle.getDesc()), currentVersions, location, violations);
		} else {
			validateMethodDescriptor(handle.getDesc(), currentVersions, location, violations);
			validateSymbol(metadata.methods(), MultiversionApiMetadata.methodKey(handle.getOwner(), handle.getName(), handle.getDesc()), currentVersions, location, violations);
		}
	}

	private void propagateSuccessors(AbstractInsnNode[] instructions, Map<LabelNode, Integer> labelIndexes, int index, AbstractInsnNode instruction, BitSet currentVersions, BitSet[] reachableStates, Deque<Integer> queue) {
		final int opcode = instruction.getOpcode();

		if (instruction instanceof JumpInsnNode jumpInsn) {
			final int targetIndex = labelIndexes.get(jumpInsn.label);

			if (opcode == Opcodes.GOTO) {
				mergeState(reachableStates, targetIndex, currentVersions, queue);
				return;
			}

			final BranchState branchState = refineConditionalJump(instructions, index, opcode, currentVersions);

			if (branchState == null) {
				mergeState(reachableStates, targetIndex, currentVersions, queue);
				mergeFallthrough(instructions, reachableStates, queue, index, currentVersions);
				return;
			}

			mergeState(reachableStates, targetIndex, branchState.jumpVersions(), queue);
			mergeFallthrough(instructions, reachableStates, queue, index, branchState.fallthroughVersions());
			return;
		}

		if (instruction instanceof TableSwitchInsnNode tableSwitchInsn) {
			mergeState(reachableStates, labelIndexes.get(tableSwitchInsn.dflt), currentVersions, queue);

			for (LabelNode label : tableSwitchInsn.labels) {
				mergeState(reachableStates, labelIndexes.get(label), currentVersions, queue);
			}

			return;
		}

		if (instruction instanceof LookupSwitchInsnNode lookupSwitchInsn) {
			mergeState(reachableStates, labelIndexes.get(lookupSwitchInsn.dflt), currentVersions, queue);

			for (LabelNode label : lookupSwitchInsn.labels) {
				mergeState(reachableStates, labelIndexes.get(label), currentVersions, queue);
			}

			return;
		}

		if (opcode == Opcodes.RETURN || opcode == Opcodes.ARETURN || opcode == Opcodes.IRETURN || opcode == Opcodes.LRETURN
				|| opcode == Opcodes.FRETURN || opcode == Opcodes.DRETURN || opcode == Opcodes.ATHROW) {
			return;
		}

		mergeFallthrough(instructions, reachableStates, queue, index, currentVersions);
	}

	private static void mergeFallthrough(AbstractInsnNode[] instructions, BitSet[] reachableStates, Deque<Integer> queue, int index, BitSet versions) {
		if (index + 1 < instructions.length) {
			mergeState(reachableStates, index + 1, versions, queue);
		}
	}

	private BranchState refineConditionalJump(AbstractInsnNode[] instructions, int branchIndex, int opcode, BitSet currentVersions) {
		if (opcode == Opcodes.IFEQ || opcode == Opcodes.IFNE) {
			final VersionCondition condition = extractHelperCondition(instructions, branchIndex);

			if (condition != null) {
				final BitSet trueVersions = condition.apply(currentVersions);
				final BitSet falseVersions = subtract(currentVersions, trueVersions);
				return opcode == Opcodes.IFNE
						? new BranchState(trueVersions, falseVersions)
						: new BranchState(falseVersions, trueVersions);
			}
		}

		if (opcode == Opcodes.IF_ICMPEQ || opcode == Opcodes.IF_ICMPNE || opcode == Opcodes.IF_ICMPLT
				|| opcode == Opcodes.IF_ICMPGE || opcode == Opcodes.IF_ICMPGT || opcode == Opcodes.IF_ICMPLE) {
			final VersionCondition condition = extractVersionCompareCondition(instructions, branchIndex, opcode);

			if (condition != null) {
				final BitSet trueVersions = condition.apply(currentVersions);
				return new BranchState(trueVersions, subtract(currentVersions, trueVersions));
			}
		}

		return null;
	}

	private VersionCondition extractHelperCondition(AbstractInsnNode[] instructions, int branchIndex) {
		final int callIndex = previousMeaningfulIndex(instructions, branchIndex);

		if (callIndex < 0 || !(instructions[callIndex] instanceof MethodInsnNode methodInsn)) {
			return null;
		}

		if (methodInsn.getOpcode() != Opcodes.INVOKESTATIC || !constantsClassInternalName.equals(methodInsn.owner) || !"(I)Z".equals(methodInsn.desc)) {
			return null;
		}

		final Integer constant = extractIntConstant(instructions, callIndex);

		if (constant == null) {
			return null;
		}

		return switch (methodInsn.name) {
		case "is" -> filter -> filterByPackedValue(filter, packed -> packed == constant);
		case "isAtLeast" -> filter -> filterByPackedValue(filter, packed -> packed >= constant);
		case "isAtMost" -> filter -> filterByPackedValue(filter, packed -> packed <= constant);
		default -> null;
		};
	}

	private VersionCondition extractVersionCompareCondition(AbstractInsnNode[] instructions, int branchIndex, int opcode) {
		final int rightIndex = previousMeaningfulIndex(instructions, branchIndex);
		final int leftIndex = previousMeaningfulIndex(instructions, rightIndex);

		if (leftIndex < 0 || rightIndex < 0) {
			return null;
		}

		final AbstractInsnNode leftInstruction = instructions[leftIndex];
		final AbstractInsnNode rightInstruction = instructions[rightIndex];

		if (isVersionField(leftInstruction)) {
			final Integer constant = readIntConstant(rightInstruction);
			return constant == null ? null : filter -> filterByPackedValue(filter, packed -> comparePacked(packed, constant, opcode));
		}

		if (isVersionField(rightInstruction)) {
			final Integer constant = readIntConstant(leftInstruction);
			return constant == null ? null : filter -> filterByPackedValue(filter, packed -> comparePackedReversed(constant, packed, opcode));
		}

		return null;
	}

	private BitSet filterByPackedValue(BitSet sourceVersions, IntPredicate predicate) {
		final BitSet filtered = new BitSet(availableVersions.size());

		for (int bit = sourceVersions.nextSetBit(0); bit >= 0; bit = sourceVersions.nextSetBit(bit + 1)) {
			final String version = availableVersions.get(bit);

			if (predicate.test(packedVersions.get(version))) {
				filtered.set(bit);
			}
		}

		return filtered;
	}

	private void validateMethodDescriptor(String descriptor, BitSet currentVersions, String location, Set<String> violations) {
		validateType(Type.getMethodType(descriptor), currentVersions, location, violations);
	}

	private void validateType(Type type, BitSet currentVersions, String location, Set<String> violations) {
		switch (type.getSort()) {
		case Type.ARRAY -> validateType(type.getElementType(), currentVersions, location, violations);
		case Type.OBJECT -> validateClassReference(type.getInternalName(), currentVersions, location, violations);
		case Type.METHOD -> {
			validateType(type.getReturnType(), currentVersions, location, violations);

			for (Type argumentType : type.getArgumentTypes()) {
				validateType(argumentType, currentVersions, location, violations);
			}
		}
		default -> {
		}
		}
	}

	private void validateClassReference(String internalName, BitSet currentVersions, String location, Set<String> violations) {
		if (internalName == null) {
			return;
		}

		validateSymbol(metadata.classes(), internalName, currentVersions, location, violations);
	}

	private void validateSymbol(Map<String, Set<String>> symbols, String key, BitSet currentVersions, String location, Set<String> violations) {
		if (currentVersions.isEmpty()) {
			return;
		}

		final Set<String> supportedVersions = symbols.get(key);

		if (supportedVersions == null) {
			return;
		}

		final BitSet supportedMask = maskForVersions(supportedVersions);
		final BitSet unsupportedReachable = subtract(currentVersions, supportedMask);

		if (unsupportedReachable.isEmpty()) {
			return;
		}

		violations.add(location + " references " + key + " on versions " + versionsToString(unsupportedReachable) + ", but the API is only available on " + supportedVersions);
	}

	private BitSet applyRequiresApi(BitSet parentVersions, List<AnnotationNode> visibleAnnotations, List<AnnotationNode> invisibleAnnotations) {
		BitSet constrained = copy(parentVersions);
		boolean found = false;

		for (AnnotationNode annotation : mergeAnnotations(visibleAnnotations, invisibleAnnotations)) {
			if (!REQUIRES_API_DESCRIPTOR.equals(annotation.desc)) {
				continue;
			}

			found = true;
			constrained.and(maskForVersions(extractAnnotationVersions(annotation)));
		}

		return found ? constrained : copy(parentVersions);
	}

	private static List<AnnotationNode> mergeAnnotations(List<AnnotationNode> visibleAnnotations, List<AnnotationNode> invisibleAnnotations) {
		final List<AnnotationNode> annotations = new ArrayList<>();

		if (visibleAnnotations != null) {
			annotations.addAll(visibleAnnotations);
		}

		if (invisibleAnnotations != null) {
			annotations.addAll(invisibleAnnotations);
		}

		return annotations;
	}

	private static Collection<String> extractAnnotationVersions(AnnotationNode annotation) {
		if (annotation.values == null) {
			return List.of();
		}

		for (int index = 0; index < annotation.values.size(); index += 2) {
			final Object key = annotation.values.get(index);
			final Object value = annotation.values.get(index + 1);

			if (!"value".equals(key) || !(value instanceof List<?> values)) {
				continue;
			}

			final List<String> versions = new ArrayList<>();

			for (Object entry : values) {
				if (entry instanceof String string) {
					versions.add(string);
				}
			}

			return versions;
		}

		return List.of();
	}

	private BitSet maskForVersions(Collection<String> versions) {
		if (versions.isEmpty()) {
			return new BitSet(availableVersions.size());
		}

		final String cacheKey = String.join("|", versions.stream().sorted().toList());
		final BitSet cached = cachedVersionMasks.get(cacheKey);

		if (cached != null) {
			return copy(cached);
		}

		final BitSet mask = new BitSet(availableVersions.size());

		for (String version : versions) {
			final Integer index = versionIndexes.get(version);

			if (index != null) {
				mask.set(index);
			}
		}

		cachedVersionMasks.put(cacheKey, copy(mask));
		return mask;
	}

	private String versionsToString(BitSet versions) {
		final List<String> names = new ArrayList<>();

		for (int bit = versions.nextSetBit(0); bit >= 0; bit = versions.nextSetBit(bit + 1)) {
			names.add(availableVersions.get(bit));
		}

		return names.toString();
	}

	private static void mergeState(BitSet[] states, int index, BitSet newState, Deque<Integer> queue) {
		if (newState == null || newState.isEmpty()) {
			return;
		}

		if (states[index] == null) {
			states[index] = copy(newState);
			queue.addLast(index);
			return;
		}

		final BitSet merged = copy(states[index]);
		merged.or(newState);

		if (!merged.equals(states[index])) {
			states[index] = merged;
			queue.addLast(index);
		}
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

	private Integer extractIntConstant(AbstractInsnNode[] instructions, int producerIndex) {
		final int constantIndex = previousMeaningfulIndex(instructions, producerIndex);
		return constantIndex < 0 ? null : readIntConstant(instructions[constantIndex]);
	}

	private static Integer readIntConstant(AbstractInsnNode instruction) {
		return switch (instruction.getOpcode()) {
		case Opcodes.ICONST_M1 -> -1;
		case Opcodes.ICONST_0 -> 0;
		case Opcodes.ICONST_1 -> 1;
		case Opcodes.ICONST_2 -> 2;
		case Opcodes.ICONST_3 -> 3;
		case Opcodes.ICONST_4 -> 4;
		case Opcodes.ICONST_5 -> 5;
		case Opcodes.BIPUSH, Opcodes.SIPUSH -> ((org.objectweb.asm.tree.IntInsnNode) instruction).operand;
		default -> instruction instanceof LdcInsnNode ldcInsn && ldcInsn.cst instanceof Integer integer ? integer : null;
		};
	}

	private boolean isVersionField(AbstractInsnNode instruction) {
		return instruction instanceof FieldInsnNode fieldInsn
				&& fieldInsn.getOpcode() == Opcodes.GETSTATIC
				&& constantsClassInternalName.equals(fieldInsn.owner)
				&& "MINECRAFT_VERSION".equals(fieldInsn.name)
				&& "I".equals(fieldInsn.desc);
	}

	private static boolean comparePacked(int left, int right, int opcode) {
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

	private static boolean comparePackedReversed(int leftConstant, int rightValue, int opcode) {
		return comparePacked(leftConstant, rightValue, opcode);
	}

	private static BitSet subtract(BitSet left, BitSet right) {
		final BitSet result = copy(left);
		result.andNot(right);
		return result;
	}

	private static BitSet copy(BitSet source) {
		return (BitSet) source.clone();
	}

	private static byte[] readBytes(Path path) {
		try {
			return Files.readAllBytes(path);
		} catch (IOException e) {
			throw new RuntimeException("Failed to read class file " + path, e);
		}
	}

	private record BranchState(BitSet jumpVersions, BitSet fallthroughVersions) { }

	@FunctionalInterface
	private interface VersionCondition {
		BitSet apply(BitSet versions);
	}
}
