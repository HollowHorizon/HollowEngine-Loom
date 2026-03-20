/*
 * This file is part of fabric-loom, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2021 FabricMC
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

package net.fabricmc.loom.configuration.ide.idea;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.gradle.api.Project;

import net.fabricmc.loom.util.gradle.SourceSetReference;

public class IdeaUtils {
	private static final Pattern MODULE_FILEPATH_PATTERN = Pattern.compile("filepath=\"(?<filepath>[^\"]+\\.iml)\"");

	public static boolean isIdeaSync() {
		return Boolean.parseBoolean(System.getProperty("idea.sync.active", "false"));
	}

	public static String resolveRunConfigModuleName(SourceSetReference reference) {
		final String fallbackModuleName = getIdeaModuleName(reference);
		final Path modulesXml = reference.project().getRootProject().file(".idea/modules.xml").toPath();

		if (Files.notExists(modulesXml)) {
			return fallbackModuleName;
		}

		final Path rootPath = reference.project().getRootProject().getProjectDir().toPath();
		final Path projectPath = reference.project().getProjectDir().toPath();
		final String normalizedProjectPath = rootPath.relativize(projectPath).toString().replace('\\', '/');
		final String sourceSetSuffix = "." + reference.sourceSet().getName();

		try {
			final String xml = Files.readString(modulesXml, StandardCharsets.UTF_8);
			final Matcher matcher = MODULE_FILEPATH_PATTERN.matcher(xml);
			final List<String> suffixMatches = new ArrayList<>();

			while (matcher.find()) {
				final String filepath = matcher.group("filepath").replace('\\', '/');
				final String marker = "/.idea/modules/";
				final int markerIndex = filepath.indexOf(marker);

				if (markerIndex < 0) {
					continue;
				}

				final String relativeModulePath = filepath.substring(markerIndex + marker.length(), filepath.length() - ".iml".length());
				final int lastSlashIndex = relativeModulePath.lastIndexOf('/');

				if (lastSlashIndex < 0) {
					continue;
				}

				final String modulePath = relativeModulePath.substring(0, lastSlashIndex);

				if (!normalizedProjectPath.equals(modulePath)) {
					continue;
				}

				final String moduleName = relativeModulePath.substring(lastSlashIndex + 1);

				if (moduleName.equals(fallbackModuleName) || moduleName.endsWith("." + fallbackModuleName)) {
					suffixMatches.add(moduleName);
				}

				if (moduleName.endsWith(sourceSetSuffix)) {
					if (normalizedProjectPath.equals(modulePath)) {
						return moduleName;
					}
				}
			}

			if (suffixMatches.size() == 1) {
				return suffixMatches.get(0);
			}
		} catch (IOException ignored) {
			return fallbackModuleName;
		}

		return fallbackModuleName;
	}

	public static String getIdeaModuleName(SourceSetReference reference) {
		Project project = reference.project();
		String module = project.getName() + "." + reference.sourceSet().getName();

		while ((project = project.getParent()) != null) {
			module = project.getName() + "." + module;
		}

		return module.replace(' ', '_');
	}
}
