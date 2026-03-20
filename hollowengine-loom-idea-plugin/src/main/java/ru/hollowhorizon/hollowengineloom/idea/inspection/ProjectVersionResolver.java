package ru.hollowhorizon.hollowengineloom.idea.inspection;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;

final class ProjectVersionResolver {
	private static final Pattern VERSION_LITERAL_PATTERN = Pattern.compile("(?:create|version)\\s*\\(\\s*['\\\"](\\d+\\.\\d+\\.\\d+)['\\\"]\\s*\\)");
	private static final Pattern VERSION_PROPERTY_PATTERN = Pattern.compile("(?:create|version)\\s*\\(\\s*(?:project\\.)?([A-Za-z_][A-Za-z0-9_]*)\\s*\\)");
	private static final String[] BUILD_FILE_NAMES = {"build.gradle", "build.gradle.kts"};
	private static final String[] PROPERTIES_FILE_NAMES = {"gradle.properties"};

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
		final Properties properties = loadProperties(directory);

		for (String fileName : BUILD_FILE_NAMES) {
			final Path buildFile = directory.resolve(fileName);

			if (!Files.isRegularFile(buildFile)) {
				continue;
			}

			try {
				final String content = Files.readString(buildFile, StandardCharsets.UTF_8);
				final Matcher literalMatcher = VERSION_LITERAL_PATTERN.matcher(content);

				while (literalMatcher.find()) {
					versions.add(literalMatcher.group(1));
				}

				final Matcher propertyMatcher = VERSION_PROPERTY_PATTERN.matcher(content);

				while (propertyMatcher.find()) {
					final String propertyName = propertyMatcher.group(1);
					final String value = properties.getProperty(propertyName);

					if (value != null && value.matches("\\d+\\.\\d+\\.\\d+")) {
						versions.add(value);
					}
				}
			} catch (IOException ignored) {
			}
		}
	}

	private static Properties loadProperties(Path directory) {
		final Properties properties = new Properties();

		for (String fileName : PROPERTIES_FILE_NAMES) {
			final Path propertiesFile = directory.resolve(fileName);

			if (!Files.isRegularFile(propertiesFile)) {
				continue;
			}

			try {
				properties.load(new StringReader(Files.readString(propertiesFile, StandardCharsets.UTF_8)));
			} catch (IOException ignored) {
			}
		}

		return properties;
	}
}
