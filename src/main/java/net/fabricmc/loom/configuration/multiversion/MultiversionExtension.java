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

import javax.inject.Inject;

import org.gradle.api.Action;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Project;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;

import net.fabricmc.loom.api.MultiversionExtensionAPI;
import net.fabricmc.loom.api.MultiversionVersionEntryAPI;

public abstract class MultiversionExtension implements MultiversionExtensionAPI {
	private final NamedDomainObjectContainer<MultiversionVersionEntry> versions;

	@Inject
	public MultiversionExtension(Project project) {
		final ObjectFactory objects = project.getObjects();
		this.versions = project.container(MultiversionVersionEntry.class, name -> objects.newInstance(MultiversionVersionEntry.class, name));
		getConstantsClass().convention("multiversion.Constants").finalizeValueOnRead();
		getBaseVersion().finalizeValueOnRead();
	}

	@Override
	public abstract Property<String> getBaseVersion();

	@Override
	public abstract Property<String> getConstantsClass();

	@Override
	@SuppressWarnings("unchecked")
	public NamedDomainObjectContainer<MultiversionVersionEntryAPI> getVersions() {
		return (NamedDomainObjectContainer<MultiversionVersionEntryAPI>) (NamedDomainObjectContainer<?>) versions;
	}

	public void version(String minecraftVersion, String mappings) {
		version(minecraftVersion, spec -> spec.getMappings().set(mappings));
	}

	public void version(String minecraftVersion, Action<MultiversionVersionEntry> action) {
		action.execute(versions.maybeCreate(minecraftVersion));
	}

	public boolean isConfigured() {
		return !versions.isEmpty();
	}

	public NamedDomainObjectContainer<MultiversionVersionEntry> getVersionEntries() {
		return versions;
	}
}
