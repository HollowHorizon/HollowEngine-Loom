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
	private static final Pattern TARGET_LITERAL_PATTERN = Pattern.compile("minecraftVersion\\s*=\\s*['\\\"](\\d+\\.\\d+\\.\\d+)['\\\"]");
	private static final Pattern TARGET_PROPERTY_PATTERN = Pattern.compile("minecraftVersion\\s*=\\s*(?:project\\.)?([A-Za-z_][A-Za-z0-9_]*)");
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
			final Path moduleDir = moduleDirectory(module);

			if (moduleDir != null) {
				collectFromDirectory(moduleDir, versions);
			}
		}

		return versions;
	}

	static Path moduleDirectory(Module module) {
		final VirtualFile moduleFile = module.getModuleFile();
		return moduleFile == null || moduleFile.getParent() == null ? null : moduleFile.getParent().toNioPath();
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

	static Properties loadProperties(Path directory) {
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

	static boolean containsCommonMultiversionBlock(Path buildFile) {
		if (!Files.isRegularFile(buildFile)) {
			return false;
		}

		try {
			final String content = Files.readString(buildFile, StandardCharsets.UTF_8);
			return content.contains("multiversion") && content.contains("versions") && !content.contains("multiversionTarget");
		} catch (IOException ignored) {
			return false;
		}
	}

	static String resolveTargetVersion(Path buildFile, Properties properties) {
		if (!Files.isRegularFile(buildFile)) {
			return null;
		}

		try {
			final String content = Files.readString(buildFile, StandardCharsets.UTF_8);
			final Matcher literalMatcher = TARGET_LITERAL_PATTERN.matcher(content);

			if (literalMatcher.find()) {
				return literalMatcher.group(1);
			}

			final Matcher propertyMatcher = TARGET_PROPERTY_PATTERN.matcher(content);

			if (propertyMatcher.find()) {
				final String propertyName = propertyMatcher.group(1);
				final String value = properties.getProperty(propertyName);
				return value != null && value.matches("\\d+\\.\\d+\\.\\d+") ? value : null;
			}
		} catch (IOException ignored) {
			return null;
		}

		return null;
	}
}
