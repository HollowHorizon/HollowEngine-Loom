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

import java.util.Set;

import javax.inject.Inject;

import org.gradle.api.artifacts.Dependency;
import org.gradle.api.Project;
import org.gradle.api.provider.Property;

import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.api.MultiversionTargetAPI;

public abstract class MultiversionTarget implements MultiversionTargetAPI {
	@Inject
	public MultiversionTarget(Project project) {
		getConstantsClass().finalizeValueOnRead();

		project.afterEvaluate(ignored -> {
			if (getMinecraftVersion().isPresent()) {
				return;
			}

			final String minecraftVersion = inferMinecraftVersion(project);

			if (minecraftVersion != null) {
				getMinecraftVersion().set(minecraftVersion);
			}
		});
	}

	@Override
	public abstract Property<String> getMinecraftVersion();

	@Override
	@Deprecated(forRemoval = true)
	public abstract Property<String> getConstantsClass();

	public boolean isConfigured() {
		return getMinecraftVersion().isPresent();
	}

	static String inferMinecraftVersion(Project project) {
		final var minecraft = project.getConfigurations().findByName(Constants.Configurations.MINECRAFT);

		if (minecraft == null) {
			return null;
		}

		final Set<String> versions = minecraft.getDependencies().stream()
				.filter(Dependency.class::isInstance)
				.map(Dependency.class::cast)
				.filter(dependency -> "com.mojang".equals(dependency.getGroup()) && "minecraft".equals(dependency.getName()))
				.map(Dependency::getVersion)
				.filter(version -> version != null && !version.isBlank())
				.collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));

		return versions.size() == 1 ? versions.iterator().next() : null;
	}
}
