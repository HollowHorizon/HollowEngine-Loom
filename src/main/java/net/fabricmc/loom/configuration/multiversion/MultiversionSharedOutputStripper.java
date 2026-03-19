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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

public final class MultiversionSharedOutputStripper {
	private static final String REQUIRES_API_DESCRIPTOR = "L" + MultiversionStubGenerator.REQUIRES_API_INTERNAL_NAME + ";";

	public void stripDirectories(List<Path> inputDirectories, Path outputDirectory, String targetVersion) throws IOException {
		deleteDirectory(outputDirectory);
		Files.createDirectories(outputDirectory);

		for (Path inputDirectory : inputDirectories) {
			if (inputDirectory == null || Files.notExists(inputDirectory)) {
				continue;
			}

			try (var stream = Files.walk(inputDirectory)) {
				stream.filter(Files::isRegularFile).forEach(path -> {
					final Path relativePath = inputDirectory.relativize(path);
					final Path outputPath = outputDirectory.resolve(relativePath.toString());

					try {
						Files.createDirectories(outputPath.getParent());

						if (path.toString().endsWith(".class")) {
							writeStrippedClass(path, outputPath, targetVersion);
						} else {
							Files.copy(path, outputPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
						}
					} catch (IOException e) {
						throw new RuntimeException("Failed to prepare stripped multiversion output for " + path, e);
					}
				});
			}
		}
	}

	private void writeStrippedClass(Path inputPath, Path outputPath, String targetVersion) throws IOException {
		final ClassNode classNode = new ClassNode();
		new ClassReader(Files.readAllBytes(inputPath)).accept(classNode, 0);

		if (!isAvailableOnTarget(classNode.visibleAnnotations, classNode.invisibleAnnotations, targetVersion)) {
			Files.deleteIfExists(outputPath);
			return;
		}

		stripRequiresApi(classNode.visibleAnnotations, classNode.invisibleAnnotations);
		stripFields(classNode.fields, targetVersion);
		stripMethods(classNode.methods, targetVersion);

		final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
		classNode.accept(writer);
		Files.write(outputPath, writer.toByteArray());
	}

	private void stripFields(List<FieldNode> fields, String targetVersion) {
		for (Iterator<FieldNode> iterator = fields.iterator(); iterator.hasNext(); ) {
			final FieldNode field = iterator.next();

			if (!isAvailableOnTarget(field.visibleAnnotations, field.invisibleAnnotations, targetVersion)) {
				iterator.remove();
				continue;
			}

			stripRequiresApi(field.visibleAnnotations, field.invisibleAnnotations);
		}
	}

	private void stripMethods(List<MethodNode> methods, String targetVersion) {
		for (Iterator<MethodNode> iterator = methods.iterator(); iterator.hasNext(); ) {
			final MethodNode method = iterator.next();

			if (!isAvailableOnTarget(method.visibleAnnotations, method.invisibleAnnotations, targetVersion)) {
				iterator.remove();
				continue;
			}

			stripRequiresApi(method.visibleAnnotations, method.invisibleAnnotations);
		}
	}

	private static boolean isAvailableOnTarget(List<AnnotationNode> visibleAnnotations, List<AnnotationNode> invisibleAnnotations, String targetVersion) {
		boolean found = false;

		for (AnnotationNode annotation : mergeAnnotations(visibleAnnotations, invisibleAnnotations)) {
			if (!REQUIRES_API_DESCRIPTOR.equals(annotation.desc)) {
				continue;
			}

			found = true;

			if (extractVersions(annotation).contains(targetVersion)) {
				return true;
			}
		}

		return !found;
	}

	private static void stripRequiresApi(List<AnnotationNode> visibleAnnotations, List<AnnotationNode> invisibleAnnotations) {
		removeRequiresApi(visibleAnnotations);
		removeRequiresApi(invisibleAnnotations);
	}

	private static void removeRequiresApi(List<AnnotationNode> annotations) {
		if (annotations == null) {
			return;
		}

		annotations.removeIf(annotation -> REQUIRES_API_DESCRIPTOR.equals(annotation.desc));
	}

	private static List<AnnotationNode> mergeAnnotations(List<AnnotationNode> visibleAnnotations, List<AnnotationNode> invisibleAnnotations) {
		final List<AnnotationNode> merged = new ArrayList<>();

		if (visibleAnnotations != null) {
			merged.addAll(visibleAnnotations);
		}

		if (invisibleAnnotations != null) {
			merged.addAll(invisibleAnnotations);
		}

		return merged;
	}

	private static List<String> extractVersions(AnnotationNode annotation) {
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

	private static void deleteDirectory(Path directory) throws IOException {
		if (directory == null || Files.notExists(directory)) {
			return;
		}

		try (var stream = Files.walk(directory)) {
			stream.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			});
		}
	}
}
