package ru.hollowhorizon.hollowengineloom.idea.inspection;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

final class ProjectVersionResolver {
	private static final Pattern VERSION_ENTRY_PATTERN = Pattern.compile("(?:create|version)\\s*\\(\\s*['\\\"](\\d+\\.\\d+\\.\\d+)['\\\"]\\s*\\)");
	private static final String[] BUILD_FILE_NAMES = {"build.gradle", "build.gradle.kts"};

	private ProjectVersionResolver() {
	}

	static Set<String> resolve(Project project) {
		final Set<String> versions = new LinkedHashSet<>();
		final VirtualFile baseDir = project.getBaseDir();

		if (baseDir != null) {
			collectFromDirectory(baseDir.toNioPath(), versions);
		}

		for (Module module : ModuleManager.getInstance(project).getModules()) {
			final VirtualFile moduleFile = module.getModuleFile();

			if (moduleFile != null && moduleFile.getParent() != null) {
				collectFromDirectory(moduleFile.getParent().toNioPath(), versions);
			}
		}

		return versions;
	}

	private static void collectFromDirectory(Path directory, Set<String> versions) {
		for (String fileName : BUILD_FILE_NAMES) {
			final Path buildFile = directory.resolve(fileName);

			if (!Files.isRegularFile(buildFile)) {
				continue;
			}

			try {
				final String content = Files.readString(buildFile, StandardCharsets.UTF_8);
				final Matcher matcher = VERSION_ENTRY_PATTERN.matcher(content);

				while (matcher.find()) {
					versions.add(matcher.group(1));
				}
			} catch (IOException ignored) {
			}
		}
	}
}
