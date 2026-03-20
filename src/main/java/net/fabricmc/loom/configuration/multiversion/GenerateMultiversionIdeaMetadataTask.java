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
import java.util.Map;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.task.AbstractLoomTask;

public abstract class GenerateMultiversionIdeaMetadataTask extends AbstractLoomTask {
	@Input
	public abstract Property<String> getMode();

	@Input
	@Optional
	public abstract Property<String> getTargetVersion();

	@Input
	public abstract Property<String> getConstantsClass();

	@Input
	public abstract MapProperty<String, String> getVersionMappings();

	@OutputFile
	public abstract RegularFileProperty getOutputFile();

	@TaskAction
	public void generate() throws IOException {
		final List<String> availableVersions = getVersionMappings().get().keySet().stream().sorted().toList();
		final MultiversionIdeaMetadata metadata = new MultiversionIdeaMetadata(
				MultiversionIdeaMetadata.CURRENT_VERSION,
				getMode().get(),
				getTargetVersion().getOrNull(),
				getConstantsClass().get(),
				availableVersions
		);

		final var outputPath = getOutputFile().get().getAsFile().toPath();
		Files.createDirectories(outputPath.getParent());
		Files.writeString(outputPath, LoomGradlePlugin.GSON.toJson(metadata));
	}
}
