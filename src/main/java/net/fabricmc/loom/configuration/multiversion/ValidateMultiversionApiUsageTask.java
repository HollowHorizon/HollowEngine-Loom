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
import java.util.List;

import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.task.AbstractLoomTask;
import net.fabricmc.loom.util.ZipUtils;

public abstract class ValidateMultiversionApiUsageTask extends AbstractLoomTask {
	@Input
	public abstract Property<String> getConstantsClass();

	@Input
	@Optional
	public abstract Property<String> getTargetVersion();

	@InputFiles
	public abstract ConfigurableFileCollection getClassesDirectories();

	@InputFiles
	public abstract ConfigurableFileCollection getAdditionalMetadataDirectories();

	@InputFiles
	public abstract RegularFileProperty getStubJar();

	@OutputFile
	public abstract RegularFileProperty getMarkerFile();

	@TaskAction
	public void validate() throws IOException {
		final MultiversionApiMetadata metadata = LoomGradlePlugin.GSON.fromJson(new String(ZipUtils.unpack(getStubJar().get().getAsFile().toPath(), MultiversionApiMetadata.PATH)), MultiversionApiMetadata.class);
		final MultiversionApiMetadata mergedMetadata = new MultiversionRequiresApiMetadataCollector().merge(
				metadata,
				new MultiversionRequiresApiMetadataCollector().collect(
						getAdditionalMetadataDirectories().getFiles().stream().map(java.io.File::toPath).toList(),
						metadata.availableVersions()
				)
		);
		final List<String> mergedViolations = new MultiversionApiUsageValidator(mergedMetadata, getConstantsClass().get(), getTargetVersion().getOrNull())
				.validateDirectories(getClassesDirectories().getFiles().stream().map(java.io.File::toPath).toList());

		if (!mergedViolations.isEmpty()) {
			throw new GradleException("Invalid multiversion API usage:\n - " + String.join("\n - ", mergedViolations));
		}

		Files.createDirectories(getMarkerFile().get().getAsFile().toPath().getParent());
		Files.writeString(getMarkerFile().get().getAsFile().toPath(), "ok");
	}
}
