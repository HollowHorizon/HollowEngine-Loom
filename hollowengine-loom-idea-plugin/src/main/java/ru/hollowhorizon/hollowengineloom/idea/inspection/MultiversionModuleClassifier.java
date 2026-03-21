package ru.hollowhorizon.hollowengineloom.idea.inspection;

import java.nio.file.Path;
import java.util.Properties;

import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil;
import com.intellij.openapi.module.Module;

final class MultiversionModuleClassifier {
	private MultiversionModuleClassifier() {
	}

	static ModuleMode classify(Module module) {
		if (module == null) {
			return ModuleMode.none();
		}

		final IdeaMultiversionMetadata metadata = IdeaMetadataResolver.resolve(module);

		if (metadata != null && metadata.mode != null) {
			return switch (metadata.mode) {
				case "COMMON" -> ModuleMode.common(metadata.availableVersions);
				case "TARGET" -> ModuleMode.target(metadata.targetVersion, metadata.availableVersions);
				default -> ModuleMode.none();
			};
		}

		return detectModeFallback(module);
	}

	private static ModuleMode detectModeFallback(Module module) {
		final String path = ExternalSystemApiUtil.getExternalProjectPath(module);
		final Path moduleDir;

		if (path != null) {
			moduleDir = Path.of(path);
		} else {
			moduleDir = ProjectVersionResolver.moduleDirectory(module);
		}

		final ModuleMode localMode = moduleDir == null ? ModuleMode.none() : detectModeFromDirectory(moduleDir);

		if (localMode.kind() != Kind.NONE) {
			return localMode;
		}

		final String projectBasePath = module.getProject().getBasePath();

		if (projectBasePath == null) {
			return ModuleMode.none();
		}

		return detectModeFromDirectory(Path.of(projectBasePath));
	}

	private static ModuleMode detectModeFromDirectory(Path directory) {
		final Path groovyBuildFile = directory.resolve("build.gradle");
		final Path kotlinBuildFile = directory.resolve("build.gradle.kts");
		final Properties properties = ProjectVersionResolver.loadProperties(directory);
		final String targetVersion = ProjectVersionResolver.resolveTargetVersion(groovyBuildFile, properties);

		if (targetVersion != null) {
			return ModuleMode.target(targetVersion, null);
		}

		final String kotlinTargetVersion = ProjectVersionResolver.resolveTargetVersion(kotlinBuildFile, properties);

		if (kotlinTargetVersion != null) {
			return ModuleMode.target(kotlinTargetVersion, null);
		}

		if (ProjectVersionResolver.containsCommonMultiversionBlock(groovyBuildFile)
				|| ProjectVersionResolver.containsCommonMultiversionBlock(kotlinBuildFile)) {
			return ModuleMode.common(null);
		}

		return ModuleMode.none();
	}

	enum Kind {
		NONE,
		COMMON,
		TARGET
	}

	record ModuleMode(Kind kind, String targetVersion, java.util.List<String> availableVersions) {
		static ModuleMode none() {
			return new ModuleMode(Kind.NONE, null, null);
		}

		static ModuleMode common(java.util.List<String> availableVersions) {
			return new ModuleMode(Kind.COMMON, null, availableVersions);
		}

		static ModuleMode target(String targetVersion, java.util.List<String> availableVersions) {
			return new ModuleMode(Kind.TARGET, targetVersion, availableVersions);
		}
	}
}
