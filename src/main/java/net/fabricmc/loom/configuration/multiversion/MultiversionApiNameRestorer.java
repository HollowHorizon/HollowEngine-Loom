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
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.util.ZipUtils;

final class MultiversionApiNameRestorer {
	private final MultiversionApiMetadata metadata;
	private final Remapper remapper;

	MultiversionApiNameRestorer(MultiversionApiMetadata metadata) {
		this.metadata = metadata;
		this.remapper = createRemapper(metadata);
	}

	void restoreDirectories(List<Path> directories) throws IOException {
		for (Path directory : directories) {
			if (directory == null || Files.notExists(directory)) {
				continue;
			}

			try (var stream = Files.walk(directory)) {
				stream.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".class"))
						.forEach(path -> restoreClass(path));
			}
		}
	}

	private void restoreClass(Path path) {
		try {
			final byte[] restored = restoreClass(Files.readAllBytes(path));
			Files.write(path, restored);
		} catch (IOException e) {
			throw new RuntimeException("Failed to restore multiversion API names in " + path, e);
		}
	}

	byte[] restoreClass(byte[] bytes) {
		final ClassReader reader = new ClassReader(bytes);
		final ClassWriter writer = new ClassWriter(0);
		reader.accept(new ClassRemapper(writer, remapper), 0);
		return writer.toByteArray();
	}

	static MultiversionApiMetadata readMetadata(Path stubJar) throws IOException {
		return LoomGradlePlugin.GSON.fromJson(new String(ZipUtils.unpack(stubJar, MultiversionApiMetadata.PATH), StandardCharsets.UTF_8), MultiversionApiMetadata.class);
	}

	private static Remapper createRemapper(MultiversionApiMetadata metadata) {
		final Map<String, String> methodAliases = new LinkedHashMap<>();
		final Map<String, String> fieldAliases = new LinkedHashMap<>();

		for (Map.Entry<String, String> entry : metadata.syntheticMethods().entrySet()) {
			final MemberKey original = MemberKey.parseMethod(entry.getKey());
			methodAliases.put(MemberKey.methodKey(original.owner(), entry.getValue(), original.descriptor()), original.name());
		}

		for (Map.Entry<String, String> entry : metadata.syntheticFields().entrySet()) {
			final MemberKey original = MemberKey.parseField(entry.getKey());
			fieldAliases.put(MemberKey.fieldKey(original.owner(), entry.getValue(), original.descriptor()), original.name());
		}

		return new Remapper() {
			@Override
			public String mapMethodName(String owner, String name, String descriptor) {
				return methodAliases.getOrDefault(MemberKey.methodKey(owner, name, descriptor), name);
			}

			@Override
			public String mapFieldName(String owner, String name, String descriptor) {
				return fieldAliases.getOrDefault(MemberKey.fieldKey(owner, name, descriptor), name);
			}
		};
	}

	private record MemberKey(String owner, String name, String descriptor) {
		static MemberKey parseMethod(String key) {
			final int hash = key.indexOf('#');
			final int paren = key.indexOf('(', hash);
			return new MemberKey(key.substring(0, hash), key.substring(hash + 1, paren), key.substring(paren));
		}

		static MemberKey parseField(String key) {
			final int hash = key.indexOf('#');
			final int colon = key.indexOf(':', hash);
			return new MemberKey(key.substring(0, hash), key.substring(hash + 1, colon), key.substring(colon + 1));
		}

		static String methodKey(String owner, String name, String descriptor) {
			return owner + "#" + name + descriptor;
		}

		static String fieldKey(String owner, String name, String descriptor) {
			return owner + "#" + name + ":" + descriptor;
		}
	}
}
