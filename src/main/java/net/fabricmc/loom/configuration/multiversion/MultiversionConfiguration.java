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

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskContainer;
import org.gradle.jvm.tasks.Jar;

import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.task.AbstractRunTask;
import net.fabricmc.loom.task.RemapTaskConfiguration;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.ModPlatform;
import net.fabricmc.loom.util.gradle.GradleUtils;
import net.fabricmc.loom.util.gradle.SourceSetHelper;

public abstract class MultiversionConfiguration implements Runnable {
	@Inject
	protected abstract Project getProject();

	@Inject
	protected abstract TaskContainer getTasks();

	@Override
	public void run() {
		final Project project = getProject();
		final LoomGradleExtension extension = LoomGradleExtension.get(project);
		final SourceSet mainSourceSet = SourceSetHelper.getMainSourceSet(project);

		final var generateStub = getTasks().register("generateMultiversionStub", GenerateMultiversionStubTask.class, task -> {
			task.getVersionMappings().set(project.provider(() -> MultiversionSupport.resolveVersionMappings(project)));
			task.getBaseVersion().set(project.provider(() -> MultiversionSupport.resolveBaseVersion(project)));
			task.getConstantsClass().set(project.provider(() -> MultiversionSupport.getConfiguredExtension(project).getConstantsClass().get()));
			task.getStubVersion().set(project.provider(() -> MultiversionSupport.computeStubVersion(task.getVersionMappings().get(), task.getBaseVersion().get())));
			task.getStubJar().set(project.getLayout().file(project.provider(() -> project.getLayout().getBuildDirectory().file("loom-cache/multiversion/artifacts/%s/multiversion-stub.jar".formatted(task.getStubVersion().get())).get().getAsFile())));
			task.getSourcesJar().set(project.getLayout().file(project.provider(() -> project.getLayout().getBuildDirectory().file("loom-cache/multiversion/source-archives/%s/multiversion-stub-sources.jar".formatted(task.getStubVersion().get())).get().getAsFile())));
			task.getPublishedStubJar().set(project.getLayout().file(project.provider(() -> MultiversionSupport.createStubMavenHelper(project, task.getStubVersion().get()).getOutputFile(null).toFile())));
			task.getPublishedSourcesJar().set(project.getLayout().file(project.provider(() -> MultiversionSupport.createStubMavenHelper(project, task.getStubVersion().get()).getOutputFile("sources").toFile())));
		});

		final var generateConstants = getTasks().register("generateMultiversionConstants", GenerateMultiversionConstantsTask.class, task -> {
			task.getMinecraftVersion().set(project.provider(() -> ((MultiversionTarget) extension.getMultiversionTarget()).getMinecraftVersion().getOrElse("")));
			task.getConstantsClass().set(project.provider(() -> MultiversionSupport.getConfiguredExtension(project).getConstantsClass().get()));
			task.getAvailableVersions().set(project.provider(() -> MultiversionSupport.resolveVersionMappings(project).keySet().stream().sorted().toList()));
			task.getOutputDirectory().set(project.getLayout().getBuildDirectory().dir("generated/sources/loomMultiversion/constants"));
		});

		final var validateApiUsage = getTasks().register("validateMultiversionApiUsage", ValidateMultiversionApiUsageTask.class, task -> {
			task.getConstantsClass().set(project.provider(() -> {
				return MultiversionSupport.getConfiguredExtension(project).getConstantsClass().get();
			}));
			task.getAvailableVersions().set(project.provider(() -> MultiversionSupport.resolveVersionMappings(project).keySet().stream().sorted().toList()));
			task.getTargetVersion().set(project.provider(() -> {
				final MultiversionTarget target = (MultiversionTarget) extension.getMultiversionTarget();
				return target.isConfigured() ? target.getMinecraftVersion().getOrNull() : null;
			}));
			task.getClassesDirectories().from(mainSourceSet.getOutput().getClassesDirs());
			task.getMarkerFile().set(project.getLayout().getBuildDirectory().file("loom-cache/validateMultiversionApiUsage.ok"));
		});

		final var optimizeConstants = getTasks().register("optimizeMultiversionConstants", OptimizeMultiversionConstantsTask.class, task -> {
			task.getTargetVersion().set(project.provider(() -> ((MultiversionTarget) extension.getMultiversionTarget()).getMinecraftVersion().getOrElse("")));
			task.getConstantsClass().set(project.provider(() -> MultiversionSupport.getConfiguredExtension(project).getConstantsClass().get()));
			task.getAvailableVersions().set(project.provider(() -> MultiversionSupport.resolveVersionMappings(project).keySet().stream().sorted().toList()));
			task.getClassesDirectories().from(mainSourceSet.getOutput().getClassesDirs());
		});

		final var generateIdeaMetadata = getTasks().register("generateMultiversionIdeaMetadata", GenerateMultiversionIdeaMetadataTask.class, task -> {
			task.getMode().set(project.provider(() -> {
				final MultiversionTarget target = (MultiversionTarget) extension.getMultiversionTarget();
				return target.isConfigured() ? MultiversionIdeaMetadata.Mode.TARGET.name() : MultiversionIdeaMetadata.Mode.COMMON.name();
			}));
			task.getTargetVersion().set(project.provider(() -> {
				final MultiversionTarget target = (MultiversionTarget) extension.getMultiversionTarget();
				return target.isConfigured() ? target.getMinecraftVersion().getOrNull() : null;
			}));
			task.getConstantsClass().set(project.provider(() -> {
				return MultiversionSupport.getConfiguredExtension(project).getConstantsClass().get();
			}));
			task.getVersionMappings().set(project.provider(() -> MultiversionSupport.resolveVersionMappings(project)));
			task.getOutputFile().set(project.getLayout().getBuildDirectory().file(MultiversionIdeaMetadata.RELATIVE_PATH));
		});

		GradleUtils.afterSuccessfulEvaluation(project, () -> {
			if (!MultiversionSupport.isMultiversionProject(project)) {
				return;
			}

			final MultiversionTarget target = (MultiversionTarget) extension.getMultiversionTarget();
			final var stubJar = generateStub.flatMap(GenerateMultiversionStubTask::getStubJar);
			final String globalConstantsClass = MultiversionSupport.getConfiguredExtension(project).getConstantsClass().get();

			if (target.getConstantsClass().isPresent() && !globalConstantsClass.equals(target.getConstantsClass().get())) {
				throw new IllegalStateException("multiversionTarget.constantsClass must match loom.multiversion.constantsClass. Configure it only in loom.multiversion.");
			}

			if (!target.isConfigured()) {
				validateApiUsage.configure(task -> task.getStubJar().set(generateStub.flatMap(GenerateMultiversionStubTask::getStubJar)));
			}

			configureCompileTasks(mainSourceSet, generateStub, generateConstants, validateApiUsage, optimizeConstants);
			configureIdeaSync(generateStub, generateConstants, generateIdeaMetadata);

			if (target.isConfigured()) {
				configureGeneratedConstantsSourceSet(generateConstants);
				configureSharedProjectConsumption(validateApiUsage);
				configureTargetLaunchAliases(target);
			} else {
				disableCommonRemapTasks();
				project.getDependencies().add(JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME, project.files(stubJar));
				configureCommonLibraryClasspath();
			}
		});
	}

	private void configureGeneratedConstantsSourceSet(org.gradle.api.tasks.TaskProvider<GenerateMultiversionConstantsTask> generateConstants) {
		final SourceSet main = SourceSetHelper.getMainSourceSet(getProject());
		main.getJava().srcDir(generateConstants.flatMap(GenerateMultiversionConstantsTask::getOutputDirectory));
	}

	private void configureCompileTasks(SourceSet mainSourceSet, org.gradle.api.tasks.TaskProvider<GenerateMultiversionStubTask> generateStub, org.gradle.api.tasks.TaskProvider<GenerateMultiversionConstantsTask> generateConstants, org.gradle.api.tasks.TaskProvider<ValidateMultiversionApiUsageTask> validateApiUsage, org.gradle.api.tasks.TaskProvider<OptimizeMultiversionConstantsTask> optimizeConstants) {
		final MultiversionTarget target = (MultiversionTarget) LoomGradleExtension.get(getProject()).getMultiversionTarget();

		getTasks().configureEach(task -> {
			final String name = task.getName();

			if (name.startsWith("compile") && (name.endsWith("Java") || name.endsWith("Kotlin"))) {
				if (target.isConfigured()) {
					task.dependsOn(generateConstants);
				} else {
					task.dependsOn(generateStub);
				}
			}
		});

		final String mainJavaCompileTaskName = mainSourceSet.getCompileJavaTaskName();
		final var compileTasks = getTasks().matching(candidate -> {
			final String name = candidate.getName();
			return mainJavaCompileTaskName.equals(name) || "compileKotlin".equals(name);
		});
		optimizeConstants.configure(task -> task.dependsOn(compileTasks));
		validateApiUsage.configure(task -> task.dependsOn(target.isConfigured() ? optimizeConstants : compileTasks));
		getTasks().named(JavaPlugin.CLASSES_TASK_NAME).configure(task -> task.dependsOn(validateApiUsage));
	}

	private void configureIdeaSync(org.gradle.api.tasks.TaskProvider<GenerateMultiversionStubTask> generateStub, org.gradle.api.tasks.TaskProvider<GenerateMultiversionConstantsTask> generateConstants, org.gradle.api.tasks.TaskProvider<GenerateMultiversionIdeaMetadataTask> generateIdeaMetadata) {
		final MultiversionTarget target = (MultiversionTarget) LoomGradleExtension.get(getProject()).getMultiversionTarget();

		getTasks().configureEach(task -> {
			if (!"ideaSyncTask".equals(task.getName())) {
				return;
			}

			task.dependsOn(generateIdeaMetadata);

			if (!target.isConfigured()) {
				task.dependsOn(generateStub);
			}

			if (target.isConfigured()) {
				task.dependsOn(generateConstants);
			}
		});
	}

	private void configureSharedProjectConsumption(org.gradle.api.tasks.TaskProvider<ValidateMultiversionApiUsageTask> validateApiUsage) {
		final Set<Project> sharedProjects = new LinkedHashSet<>();

		for (String configurationName : List.of(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME, JavaPlugin.API_CONFIGURATION_NAME)) {
			final Configuration configuration = getProject().getConfigurations().findByName(configurationName);

			if (configuration == null) {
				continue;
			}

			for (ProjectDependency dependency : configuration.getAllDependencies().withType(ProjectDependency.class)) {
				final Project dependencyProject = dependency.getDependencyProject();
				final MultiversionTarget dependencyTarget = (MultiversionTarget) LoomGradleExtension.get(dependencyProject).getMultiversionTarget();

				if (MultiversionSupport.isMultiversionProject(dependencyProject) && !dependencyTarget.isConfigured()) {
					sharedProjects.add(dependencyProject);
				}
			}
		}

		if (sharedProjects.isEmpty()) {
			return;
		}

		final String targetVersion = MultiversionSupport.requireTargetVersion(getProject());
		final Map<Project, org.gradle.api.tasks.TaskProvider<PrepareMultiversionSharedOutputTask>> preparedOutputs = new LinkedHashMap<>();

		for (Project sharedProject : sharedProjects) {
			preparedOutputs.put(sharedProject, registerPreparedSharedOutput(sharedProject, targetVersion));
			validateApiUsage.configure(task -> task.getAdditionalMetadataDirectories().from(SourceSetHelper.getMainSourceSet(sharedProject).getOutput().getClassesDirs()));
		}

		getTasks().named(JavaPlugin.JAR_TASK_NAME, Jar.class).configure(task -> {
			for (Project sharedProject : sharedProjects) {
				final var preparedOutput = preparedOutputs.get(sharedProject);
				task.dependsOn(preparedOutput);
				task.from(preparedOutput.flatMap(PrepareMultiversionSharedOutputTask::getOutputDirectory));
			}
		});

		getTasks().withType(Jar.class).configureEach(task -> {
			if (!"sourcesJar".equals(task.getName())) {
				return;
			}

			for (Project sharedProject : sharedProjects) {
				task.from(SourceSetHelper.getMainSourceSet(sharedProject).getAllSource());
			}
		});

		getTasks().withType(AbstractRunTask.class).configureEach(task -> {
			for (Project sharedProject : sharedProjects) {
				final var preparedOutput = preparedOutputs.get(sharedProject);
				task.dependsOn(preparedOutput);
				task.classpath(preparedOutput.flatMap(PrepareMultiversionSharedOutputTask::getOutputDirectory));
			}
		});

		getTasks().matching(task -> task.getName().startsWith("generateDLIConfig")
				|| task.getName().startsWith("configureClientLaunch")
				|| task.getName().startsWith("configureLaunch"))
				.configureEach(task -> {
					for (Project sharedProject : sharedProjects) {
						task.dependsOn(preparedOutputs.get(sharedProject));
					}
				});
	}

	private org.gradle.api.tasks.TaskProvider<PrepareMultiversionSharedOutputTask> registerPreparedSharedOutput(Project sharedProject, String targetVersion) {
		final String taskName = "prepare" + sharedTaskSuffix(sharedProject) + "For" + versionTaskSuffix(targetVersion);
		final SourceSet sharedMainSourceSet = SourceSetHelper.getMainSourceSet(sharedProject);

		return getTasks().register(taskName, PrepareMultiversionSharedOutputTask.class, task -> {
			task.dependsOn(sharedProject.getTasks().named(JavaPlugin.CLASSES_TASK_NAME));
			task.getTargetVersion().set(targetVersion);
			task.getConstantsClass().set(MultiversionSupport.getConfiguredExtension(getProject()).getConstantsClass());
			task.getAvailableVersions().set(getProject().provider(() -> MultiversionSupport.resolveVersionMappings(getProject()).keySet().stream().sorted().toList()));
			task.getInputDirectories().from(sharedMainSourceSet.getOutput().getClassesDirs());
			task.getInputDirectories().from(sharedProject.provider(() -> {
				final File resourcesDir = sharedMainSourceSet.getOutput().getResourcesDir();
				return resourcesDir == null ? List.of() : List.of(resourcesDir);
			}));
			task.getOutputDirectory().set(getProject().getLayout().getBuildDirectory().dir("loom-cache/prepared-shared/" + sharedTaskSuffix(sharedProject).toLowerCase(Locale.ROOT) + "/" + targetVersion));
		});
	}

	private void disableCommonRemapTasks() {
		for (String taskName : List.of(RemapTaskConfiguration.REMAP_JAR_TASK_NAME, RemapTaskConfiguration.REMAP_SOURCES_JAR_TASK_NAME)) {
			getTasks().matching(task -> taskName.equals(task.getName())).configureEach(task -> task.setEnabled(false));
		}
	}

	private void configureCommonLibraryClasspath() {
		final Map<String, String> versionMappings = MultiversionSupport.resolveVersionMappings(getProject());
		final List<Configuration> runtimeLibraryConfigurations = new ArrayList<>();
		final Map<String, Project> preferredTargets = new LinkedHashMap<>();

		for (Project candidate : getProject().getRootProject().getAllprojects()) {
			if (candidate == getProject() || !GradleUtils.isLoomProject(candidate)) {
				continue;
			}

			final MultiversionTarget candidateTarget = (MultiversionTarget) LoomGradleExtension.get(candidate).getMultiversionTarget();

			if (!candidateTarget.isConfigured()) {
				continue;
			}

			final String targetVersion = candidateTarget.getMinecraftVersion().getOrNull();

			if (targetVersion == null || !versionMappings.containsKey(targetVersion)) {
				continue;
			}

			final Project current = preferredTargets.get(targetVersion);

			if (current == null || preferredPlatformRank(candidate) < preferredPlatformRank(current)) {
				preferredTargets.put(targetVersion, candidate);
			}
		}

		for (Project candidate : preferredTargets.values()) {
			for (String configurationName : List.of(
					Constants.Configurations.MINECRAFT_COMPILE_LIBRARIES,
					Constants.Configurations.MINECRAFT_RUNTIME_LIBRARIES
			)) {
				final Configuration libraries = candidate.getConfigurations().findByName(configurationName);

				if (libraries != null) {
					runtimeLibraryConfigurations.add(libraries);
				}
			}
		}

		if (runtimeLibraryConfigurations.isEmpty()) {
			return;
		}

		final var libraryFiles = getProject().files(runtimeLibraryConfigurations.toArray());
		getProject().getDependencies().add(JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME, libraryFiles);
		final Configuration testCompileOnly = getProject().getConfigurations().findByName(JavaPlugin.TEST_COMPILE_ONLY_CONFIGURATION_NAME);

		if (testCompileOnly != null) {
			getProject().getDependencies().add(JavaPlugin.TEST_COMPILE_ONLY_CONFIGURATION_NAME, libraryFiles);
		}
	}

	private static int preferredPlatformRank(Project project) {
		final ModPlatform platform = LoomGradleExtension.get(project).getPlatform().get();

		return switch (platform) {
		case FABRIC -> 0;
		case QUILT -> 1;
		case NEOFORGE -> 2;
		case FORGE -> 3;
		default -> 10;
		};
	}

	private void configureTargetLaunchAliases(MultiversionTarget target) {
		final String suffix = versionTaskSuffix(target.getMinecraftVersion().get());
		final Project rootProject = getProject().getRootProject();
		registerRootAliasTask(rootProject, "configureClientLaunch" + suffix, getProject().getPath() + ":configureClientLaunch");
		registerRootAliasTask(rootProject, "runClient" + suffix, getProject().getPath() + ":runClient");

		if (rootProject.getTasks().findByName("configureAllClientLaunches") == null) {
			rootProject.getTasks().register("configureAllClientLaunches");
		}

		rootProject.getTasks().named("configureAllClientLaunches").configure(task -> task.dependsOn(getProject().getPath() + ":configureClientLaunch"));
	}

	private static void registerRootAliasTask(Project rootProject, String taskName, String dependencyPath) {
		if (rootProject.getTasks().findByName(taskName) == null) {
			rootProject.getTasks().register(taskName, task -> task.dependsOn(dependencyPath));
		}
	}

	private static String sharedTaskSuffix(Project sharedProject) {
		final String[] parts = sharedProject.getPath().split(":");
		final StringBuilder builder = new StringBuilder();

		for (String part : parts) {
			if (part.isBlank()) {
				continue;
			}

			builder.append(capitalize(part.replaceAll("[^A-Za-z0-9]", " ")));
		}

		return builder.length() == 0 ? "Shared" : builder.toString();
	}

	private static String versionTaskSuffix(String version) {
		return version.replaceAll("[^A-Za-z0-9]", "");
	}

	private static String capitalize(String value) {
		final String[] parts = value.trim().split("\\s+");
		final StringBuilder builder = new StringBuilder();

		for (String part : parts) {
			if (part.isEmpty()) {
				continue;
			}

			builder.append(Character.toUpperCase(part.charAt(0)));

			if (part.length() > 1) {
				builder.append(part.substring(1));
			}
		}

		return builder.toString();
	}
}
