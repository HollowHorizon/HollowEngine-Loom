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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

public final class MultiversionRequiresApiMetadataCollector {
	private static final String REQUIRES_API_DESCRIPTOR = "L" + MultiversionStubGenerator.REQUIRES_API_INTERNAL_NAME + ";";

	public MultiversionApiMetadata collect(Iterable<Path> directories, Set<String> availableVersions) throws IOException {
		final MultiversionApiMetadata.Builder builder = MultiversionApiMetadata.builder(availableVersions);

		for (Path directory : directories) {
			if (directory == null || Files.notExists(directory)) {
				continue;
			}

			try (var stream = Files.walk(directory)) {
				stream.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".class"))
						.forEach(path -> collectClass(path, builder));
			}
		}

		return builder.build();
	}

	public MultiversionApiMetadata merge(MultiversionApiMetadata base, MultiversionApiMetadata extra) {
		final MultiversionApiMetadata.Builder builder = MultiversionApiMetadata.builder(base.availableVersions());
		copyInto(builder, base);
		copyInto(builder, extra);
		return builder.build();
	}

	private void collectClass(Path path, MultiversionApiMetadata.Builder builder) {
		try {
			final ClassNode classNode = new ClassNode();
			new ClassReader(Files.readAllBytes(path)).accept(classNode, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

			addAnnotatedClass(builder, classNode.name, classNode.visibleAnnotations, classNode.invisibleAnnotations);

			for (FieldNode field : classNode.fields) {
				addAnnotatedField(builder, classNode.name, field);
			}

			for (MethodNode method : classNode.methods) {
				addAnnotatedMethod(builder, classNode.name, method);
			}
		} catch (IOException e) {
			throw new RuntimeException("Failed to read multiversion metadata from " + path, e);
		}
	}

	private void addAnnotatedClass(MultiversionApiMetadata.Builder builder, String owner, List<AnnotationNode> visibleAnnotations, List<AnnotationNode> invisibleAnnotations) {
		final Set<String> versions = extractRequiresApiVersions(visibleAnnotations, invisibleAnnotations);

		if (!versions.isEmpty()) {
			builder.addClass(owner, versions);
		}
	}

	private void addAnnotatedField(MultiversionApiMetadata.Builder builder, String owner, FieldNode field) {
		final Set<String> versions = extractRequiresApiVersions(field.visibleAnnotations, field.invisibleAnnotations);

		if (!versions.isEmpty()) {
			builder.addField(owner, field.name, field.desc, versions);
		}
	}

	private void addAnnotatedMethod(MultiversionApiMetadata.Builder builder, String owner, MethodNode method) {
		final Set<String> versions = extractRequiresApiVersions(method.visibleAnnotations, method.invisibleAnnotations);

		if (!versions.isEmpty()) {
			builder.addMethod(owner, method.name, method.desc, versions);
		}
	}

	private static Set<String> extractRequiresApiVersions(List<AnnotationNode> visibleAnnotations, List<AnnotationNode> invisibleAnnotations) {
		for (AnnotationNode annotation : mergeAnnotations(visibleAnnotations, invisibleAnnotations)) {
			if (REQUIRES_API_DESCRIPTOR.equals(annotation.desc)) {
				return extractVersions(annotation);
			}
		}

		return Set.of();
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

	private static Set<String> extractVersions(AnnotationNode annotation) {
		if (annotation.values == null) {
			return Set.of();
		}

		for (int index = 0; index < annotation.values.size(); index += 2) {
			final Object key = annotation.values.get(index);
			final Object value = annotation.values.get(index + 1);

			if (!"value".equals(key) || !(value instanceof List<?> values)) {
				continue;
			}

			final Set<String> versions = new LinkedHashSet<>();

			for (Object entry : values) {
				if (entry instanceof String string) {
					versions.add(string);
				}
			}

			return Set.copyOf(versions);
		}

		return Set.of();
	}

	private static void copyInto(MultiversionApiMetadata.Builder builder, MultiversionApiMetadata metadata) {
		for (Map.Entry<String, Set<String>> entry : metadata.classes().entrySet()) {
			builder.addClass(entry.getKey(), entry.getValue());
		}

		for (Map.Entry<String, Set<String>> entry : metadata.methods().entrySet()) {
			final String key = entry.getKey();
			final int split = key.indexOf('#');
			builder.addMethod(key.substring(0, split), key.substring(split + 1, key.indexOf('(', split)), key.substring(key.indexOf('(', split)), entry.getValue());
		}

		for (Map.Entry<String, Set<String>> entry : metadata.fields().entrySet()) {
			final String key = entry.getKey();
			final int hash = key.indexOf('#');
			final int colon = key.indexOf(':', hash);
			builder.addField(key.substring(0, hash), key.substring(hash + 1, colon), key.substring(colon + 1), entry.getValue());
		}

		for (Map.Entry<String, String> entry : metadata.syntheticMethods().entrySet()) {
			final String key = entry.getKey();
			final int hash = key.indexOf('#');
			final int paren = key.indexOf('(', hash);
			builder.addSyntheticMethod(key.substring(0, hash), key.substring(hash + 1, paren), key.substring(paren), entry.getValue());
		}

		for (Map.Entry<String, String> entry : metadata.syntheticFields().entrySet()) {
			final String key = entry.getKey();
			final int hash = key.indexOf('#');
			final int colon = key.indexOf(':', hash);
			builder.addSyntheticField(key.substring(0, hash), key.substring(hash + 1, colon), key.substring(colon + 1), entry.getValue());
		}
	}
}
