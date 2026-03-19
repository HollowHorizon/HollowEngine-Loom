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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.gradle.api.Project;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.configuration.mods.dependency.LocalMavenHelper;
import net.fabricmc.loom.util.Checksum;

public final class MultiversionSupport {
	public static final String STUB_ARTIFACT_NAME = "multiversion-stub";

	private MultiversionSupport() {
	}

	public static MultiversionExtension getConfiguredExtension(Project project) {
		final var local = (MultiversionExtension) LoomGradleExtension.get(project).getMultiversion();

		if (local.isConfigured()) {
			return local;
		}

		if (project.equals(project.getRootProject()) || !project.getRootProject().getPluginManager().hasPlugin("ru.hollowhorizon.hollowengine-loom")) {
			return local;
		}

		return (MultiversionExtension) LoomGradleExtension.get(project.getRootProject()).getMultiversion();
	}

	public static boolean isMultiversionProject(Project project) {
		return getConfiguredExtension(project).isConfigured();
	}

	public static Map<String, String> resolveVersionMappings(Project project) {
		final MultiversionExtension extension = getConfiguredExtension(project);
		final Map<String, String> result = new LinkedHashMap<>();
		extension.getVersionEntries().stream()
				.sorted(Comparator.comparing(MultiversionVersionEntry::getName))
				.forEach(entry -> result.put(entry.getName(), entry.getMappings().get()));
		return result;
	}

	public static String resolveBaseVersion(Project project) {
		final MultiversionExtension extension = getConfiguredExtension(project);

		if (extension.getBaseVersion().isPresent()) {
			return extension.getBaseVersion().get();
		}

		return extension.getVersionEntries().stream()
				.findFirst()
				.map(MultiversionVersionEntry::getName)
				.orElseThrow(() -> new IllegalStateException("No multiversion Minecraft versions have been configured"));
	}

	public static String computeStubVersion(Map<String, String> versions, String baseVersion) {
		final StringBuilder input = new StringBuilder(baseVersion);
		versions.forEach((version, mappings) -> input.append('|').append(version).append('=').append(mappings));
		return Checksum.of(input.toString()).sha1().hex(12);
	}

	public static LocalMavenHelper createStubMavenHelper(Project project, String version) {
		return new LocalMavenHelper("net.minecraft", STUB_ARTIFACT_NAME, version, null, LoomGradleExtension.get(project).getFiles().getLocalMinecraftRepo().toPath());
	}

	public static Path createWorkingDirectory(Project project, String version) throws IOException {
		final Path output = LoomGradleExtension.get(project).getFiles().getProjectBuildCache().toPath().resolve("multiversion").resolve(version);
		Files.createDirectories(output);
		return output;
	}

	public static String getStubNotation(Project project, String version) {
		return createStubMavenHelper(project, version).getNotation();
	}

	public static String requireTargetVersion(Project project) {
		final MultiversionTarget target = (MultiversionTarget) LoomGradleExtension.get(project).getMultiversionTarget();
		return Objects.requireNonNull(target.getMinecraftVersion().getOrNull(), "multiversionTarget.minecraftVersion must be configured");
	}
}
