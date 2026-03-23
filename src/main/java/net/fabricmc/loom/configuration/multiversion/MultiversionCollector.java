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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.FileSystemUtil;

public final class MultiversionCollector {
	private final Map<String, MultiversionClassInfo> classes = new LinkedHashMap<>();
	private final Set<String> allVersions = new LinkedHashSet<>();

	public void addVersion(String version, Path jar) throws IOException {
		allVersions.add(version);
		final List<MultiversionClassInfo> parsedClasses;

		try (FileSystemUtil.Delegate fs = FileSystemUtil.getJarFileSystem(jar, false)) {
			final List<Path> classFiles = new ArrayList<>();

			for (Path root : fs.get().getRootDirectories()) {
				try (var stream = java.nio.file.Files.walk(root)) {
					stream.filter(path -> java.nio.file.Files.isRegularFile(path) && path.toString().endsWith(".class"))
							.forEach(classFiles::add);
				}
			}

			parsedClasses = classFiles.parallelStream()
					.map(path -> readClass(version, path))
					.filter(Objects::nonNull)
					.sorted(Comparator.comparing(MultiversionClassInfo::getName))
					.toList();
		}

		parsedClasses.forEach(this::mergeClass);
	}

	private MultiversionClassInfo readClass(String version, Path path) {
		try {
			final byte[] bytes = java.nio.file.Files.readAllBytes(path);
			final ClassReader reader = new ClassReader(bytes);
			final CollectorVisitor visitor = new CollectorVisitor(version);
			reader.accept(visitor, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			return visitor.getCollectedClass();
		} catch (IOException e) {
			throw new RuntimeException("Failed to read multiversion class " + path, e);
		}
	}

	private void mergeClass(MultiversionClassInfo candidate) {
		final MultiversionClassInfo merged = classes.compute(candidate.getName(), (key, existing) -> {
			if (existing == null) {
				return candidate;
			}

			existing.verifyCompatible(candidate);
			existing.getVersions().addAll(candidate.getVersions());
			mergeInterfaces(existing, candidate);
			existing.setInnerAccess(mergeInnerClassAccess(existing.getInnerAccess(), candidate.getInnerAccess()));
			mergeFields(existing, candidate);
			mergeMethods(existing, candidate);
			return existing;
		});

		if (merged == candidate) {
			return;
		}
	}

	private static void mergeFields(MultiversionClassInfo target, MultiversionClassInfo source) {
		for (var entry : source.getFields().entrySet()) {
			target.getFields().compute(entry.getKey(), (key, existing) -> {
				if (existing == null) {
					return entry.getValue();
				}

				existing.getVersions().addAll(entry.getValue().getVersions());
				return existing;
			});
		}
	}

	private static void mergeMethods(MultiversionClassInfo target, MultiversionClassInfo source) {
		for (var entry : source.getMethods().entrySet()) {
			target.getMethods().compute(entry.getKey(), (key, existing) -> {
				if (existing == null) {
					return entry.getValue();
				}

				existing.getVersions().addAll(entry.getValue().getVersions());
				return existing;
			});
		}
	}

	private static void mergeInterfaces(MultiversionClassInfo target, MultiversionClassInfo source) {
		for (var entry : source.getInterfaces().entrySet()) {
			target.getInterfaces().compute(entry.getKey(), (key, existing) -> {
				if (existing == null) {
					return new LinkedHashSet<>(entry.getValue());
				}

				existing.addAll(entry.getValue());
				return existing;
			});
		}
	}

	public Map<String, MultiversionClassInfo> getClasses() {
		return classes;
	}

	public Set<String> getAllVersions() {
		return allVersions;
	}

	private final class CollectorVisitor extends ClassVisitor {
		private final String version;
		private MultiversionClassInfo currentClass;

		private CollectorVisitor(String version) {
			super(Constants.ASM_VERSION);
			this.version = version;
		}

		private MultiversionClassInfo getCollectedClass() {
			return currentClass;
		}

		@Override
		public void visit(int jversion, int access, String name, String signature, String superName, String[] interfaces) {
			if (!isVisible(access) || isIgnoredClass(name) || !isMinecraftClass(name)) {
				return;
			}

			final MultiversionClassInfo candidate = new MultiversionClassInfo(name, sanitizeClassAccess(access), signature, superName, interfaces == null ? null : java.util.List.of(interfaces));
			currentClass = candidate;
			currentClass.getVersions().add(version);
			currentClass.addInterfaces(interfaces == null ? null : java.util.List.of(interfaces), version);
		}

		@Override
		public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
			if (currentClass == null || !isVisible(access) || isSynthetic(access)) {
				return null;
			}

			final var key = new MultiversionClassInfo.FieldSignature(name, descriptor);
			final var field = currentClass.getFields().computeIfAbsent(key, unused -> new MultiversionClassInfo.MultiversionFieldInfo(name, descriptor, signature, value, sanitizeMemberAccess(access)));
			field.getVersions().add(version);
			return null;
		}

		@Override
		public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
			if (currentClass == null || !isVisible(access) || isSynthetic(access) || "<clinit>".equals(name)) {
				return null;
			}

			final var key = new MultiversionClassInfo.MethodSignature(name, descriptor);
			final var method = currentClass.getMethods().computeIfAbsent(key, unused -> new MultiversionClassInfo.MultiversionMethodInfo(name, descriptor, signature, exceptions == null ? null : java.util.List.of(exceptions), sanitizeMemberAccess(access)));
			method.getVersions().add(version);
			return null;
		}

		@Override
		public void visitInnerClass(String name, String outerName, String innerName, int access) {
			if (currentClass == null || name == null || !name.equals(currentClass.getName())) {
				return;
			}

			currentClass.setInnerAccess(sanitizeInnerClassAccess(access));
		}

		private static boolean isVisible(int access) {
			return (access & Opcodes.ACC_PUBLIC) != 0 || (access & Opcodes.ACC_PROTECTED) != 0;
		}

		private static boolean isSynthetic(int access) {
			return (access & Opcodes.ACC_SYNTHETIC) != 0 || (access & Opcodes.ACC_BRIDGE) != 0;
		}

		private static boolean isIgnoredClass(String name) {
			if (name == null || name.indexOf('/') >= 0) {
				return false;
			}

			final int nestedSeparator = name.indexOf('$');
			final String topLevelName = nestedSeparator >= 0 ? name.substring(0, nestedSeparator) : name;
			return topLevelName.length() <= 3 && topLevelName.equals(topLevelName.toLowerCase(java.util.Locale.ROOT));
		}

		private static boolean isMinecraftClass(String name) {
			return name != null && (name.startsWith("net/minecraft/") || name.startsWith("com/mojang/"));
		}
	}

	private static int sanitizeClassAccess(int access) {
		return access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED);
	}

	private static int sanitizeMemberAccess(int access) {
		return access & ~Opcodes.ACC_PRIVATE;
	}

	private static int sanitizeInnerClassAccess(int access) {
		final int supportedFlags = Opcodes.ACC_PUBLIC | Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED | Opcodes.ACC_STATIC
				| Opcodes.ACC_FINAL | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_ANNOTATION | Opcodes.ACC_ENUM;
		return access & supportedFlags;
	}

	private static int mergeInnerClassAccess(int left, int right) {
		final int visibilityMask = Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE;
		final int otherFlags = (left | right) & ~visibilityMask;
		return otherFlags | mergeVisibility(left & visibilityMask, right & visibilityMask);
	}

	private static int mergeVisibility(int leftVisibility, int rightVisibility) {
		return switch (Math.min(visibilityRank(leftVisibility), visibilityRank(rightVisibility))) {
		case 0 -> Opcodes.ACC_PUBLIC;
		case 1 -> Opcodes.ACC_PROTECTED;
		case 2 -> Opcodes.ACC_PRIVATE;
		default -> 0;
		};
	}

	private static int visibilityRank(int visibility) {
		if ((visibility & Opcodes.ACC_PUBLIC) != 0) {
			return 0;
		}

		if ((visibility & Opcodes.ACC_PROTECTED) != 0) {
			return 1;
		}

		if ((visibility & Opcodes.ACC_PRIVATE) != 0) {
			return 2;
		}

		return 3;
	}
}
