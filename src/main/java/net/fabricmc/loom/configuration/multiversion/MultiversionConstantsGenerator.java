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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

public final class MultiversionConstantsGenerator {
	public Path write(Path outputDirectory, String className, String minecraftVersion) throws IOException {
		return write(outputDirectory, className, minecraftVersion, List.of(minecraftVersion));
	}

	public Path write(Path outputDirectory, String className, String minecraftVersion, List<String> availableVersions) throws IOException {
		final int packageSeparator = className.lastIndexOf('.');
		final String packageName = packageSeparator >= 0 ? className.substring(0, packageSeparator) : "";
		final String simpleName = packageSeparator >= 0 ? className.substring(packageSeparator + 1) : className;
		final VersionParts versionParts = VersionParts.parse(minecraftVersion);
		final Path output = outputDirectory.resolve(className.replace('.', '/') + ".java");
		Files.createDirectories(output.getParent());
		Files.writeString(output, render(packageName, simpleName, versionParts, availableVersions), StandardCharsets.UTF_8);
		return output;
	}

	private String render(String packageName, String simpleName, VersionParts versionParts, List<String> availableVersions) {
		final StringBuilder builder = new StringBuilder();

		if (!packageName.isEmpty()) {
			builder.append("package ").append(packageName).append(";\n\n");
		}

		builder.append("public final class ").append(simpleName).append(" {\n");
		builder.append("\tpublic static final String MINECRAFT_VERSION_NAME = \"").append(versionParts.raw()).append("\";\n");
		builder.append("\tpublic static final int MINECRAFT_VERSION = ").append(versionParts.packed()).append(";\n");
		builder.append("\tpublic static final int MINECRAFT = MINECRAFT_VERSION;\n");
		builder.append("\tpublic static final int MC_MAJOR = ").append(versionParts.major()).append(";\n");
		builder.append("\tpublic static final int MC_MINOR = ").append(versionParts.minor()).append(";\n");
		builder.append("\tpublic static final int MC_PATCH = ").append(versionParts.patch()).append(";\n\n");

		for (VersionParts availableVersion : availableVersions.stream()
				.map(VersionParts::parse)
				.sorted(Comparator.comparingInt(VersionParts::packed))
				.toList()) {
			builder.append("\tpublic static final int V")
					.append(availableVersion.raw().replace('.', '_').replace('-', '_'))
					.append(" = ")
					.append(availableVersion.packed())
					.append(";\n");
		}

		builder.append("\n");
		builder.append("\tprivate ").append(simpleName).append("() {\n\t}\n\n");
		builder.append("\tpublic static boolean is(int version) {\n\t\treturn MINECRAFT_VERSION == version;\n\t}\n\n");
		builder.append("\tpublic static boolean isAtLeast(int version) {\n\t\treturn MINECRAFT_VERSION >= version;\n\t}\n\n");
		builder.append("\tpublic static boolean isAtMost(int version) {\n\t\treturn MINECRAFT_VERSION <= version;\n\t}\n");
		builder.append("}\n");
		return builder.toString();
	}

	record VersionParts(String raw, int major, int minor, int patch, int packed) {
		static VersionParts parse(String version) {
			final String[] split = version.split("\\.");
			final int major = split.length > 0 ? parsePart(split[0]) : 0;
			final int minor = split.length > 1 ? parsePart(split[1]) : 0;
			final int patch = split.length > 2 ? parsePart(split[2]) : 0;
			return new VersionParts(version, major, minor, patch, major * 1000 + minor * 10 + patch);
		}

		private static int parsePart(String raw) {
			final String digits = raw.replaceAll("[^0-9].*$", "");
			return digits.isEmpty() ? 0 : Integer.parseInt(digits);
		}
	}
}
