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

import java.util.LinkedHashSet;
import java.util.List;
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
			task.getSourcesJar().set(project.getLayout().file(project.provider(() -> project.getLayout().getBuildDirectory().file("loom-cache/multiversion/artifacts/%s/multiversion-stub-sources.jar".formatted(task.getStubVersion().get())).get().getAsFile())));
			task.getPublishedStubJar().set(project.getLayout().file(project.provider(() -> MultiversionSupport.createStubMavenHelper(project, task.getStubVersion().get()).getOutputFile(null).toFile())));
			task.getPublishedSourcesJar().set(project.getLayout().file(project.provider(() -> MultiversionSupport.createStubMavenHelper(project, task.getStubVersion().get()).getOutputFile("sources").toFile())));
		});

		final var generateConstants = getTasks().register("generateMultiversionConstants", GenerateMultiversionConstantsTask.class, task -> {
			task.getMinecraftVersion().set(project.provider(() -> ((MultiversionTarget) extension.getMultiversionTarget()).getMinecraftVersion().getOrElse("")));
			task.getConstantsClass().set(project.provider(() -> ((MultiversionTarget) extension.getMultiversionTarget()).getConstantsClass().get()));
			task.getOutputDirectory().set(project.getLayout().getBuildDirectory().dir("generated/sources/loomMultiversion/constants"));
		});

		final var validateApiUsage = getTasks().register("validateMultiversionApiUsage", ValidateMultiversionApiUsageTask.class, task -> {
			task.getConstantsClass().set(project.provider(() -> MultiversionSupport.getConfiguredExtension(project).getConstantsClass().get()));
			task.getClassesDirectories().from(mainSourceSet.getOutput().getClassesDirs());
			task.getStubJar().set(generateStub.flatMap(GenerateMultiversionStubTask::getStubJar));
			task.getMarkerFile().set(project.getLayout().getBuildDirectory().file("loom-cache/validateMultiversionApiUsage.ok"));
		});

		GradleUtils.afterSuccessfulEvaluation(project, () -> {
			if (!MultiversionSupport.isMultiversionProject(project)) {
				return;
			}

			final MultiversionTarget target = (MultiversionTarget) extension.getMultiversionTarget();
			final var stubJar = generateStub.flatMap(GenerateMultiversionStubTask::getStubJar);

			configureCompileTasks(mainSourceSet, generateStub, generateConstants, validateApiUsage);
			configureIdeaSync(generateStub, generateConstants);

			if (target.isConfigured()) {
				configureGeneratedConstantsSourceSet(generateConstants);
				configureSharedProjectConsumption();
			} else {
				project.getDependencies().add(JavaPlugin.COMPILE_ONLY_CONFIGURATION_NAME, project.files(stubJar));
			}
		});
	}

	private void configureGeneratedConstantsSourceSet(org.gradle.api.tasks.TaskProvider<GenerateMultiversionConstantsTask> generateConstants) {
		final SourceSet main = SourceSetHelper.getMainSourceSet(getProject());
		main.getJava().srcDir(generateConstants.flatMap(GenerateMultiversionConstantsTask::getOutputDirectory));
	}

	private void configureCompileTasks(SourceSet mainSourceSet, org.gradle.api.tasks.TaskProvider<GenerateMultiversionStubTask> generateStub, org.gradle.api.tasks.TaskProvider<GenerateMultiversionConstantsTask> generateConstants, org.gradle.api.tasks.TaskProvider<ValidateMultiversionApiUsageTask> validateApiUsage) {
		final MultiversionTarget target = (MultiversionTarget) LoomGradleExtension.get(getProject()).getMultiversionTarget();

		getTasks().configureEach(task -> {
			final String name = task.getName();

			if (name.startsWith("compile") && (name.endsWith("Java") || name.endsWith("Kotlin"))) {
				if (!target.isConfigured()) {
					task.dependsOn(generateStub);
				}

				if (target.isConfigured()) {
					task.dependsOn(generateConstants);
				}
			}
		});

		if (!target.isConfigured()) {
			final String mainJavaCompileTaskName = mainSourceSet.getCompileJavaTaskName();
			validateApiUsage.configure(task -> task.dependsOn(getTasks().matching(candidate -> {
				final String name = candidate.getName();
				return mainJavaCompileTaskName.equals(name) || "compileKotlin".equals(name);
			})));
			getTasks().named(JavaPlugin.CLASSES_TASK_NAME).configure(task -> task.dependsOn(validateApiUsage));
		}
	}

	private void configureIdeaSync(org.gradle.api.tasks.TaskProvider<GenerateMultiversionStubTask> generateStub, org.gradle.api.tasks.TaskProvider<GenerateMultiversionConstantsTask> generateConstants) {
		final MultiversionTarget target = (MultiversionTarget) LoomGradleExtension.get(getProject()).getMultiversionTarget();

		getTasks().configureEach(task -> {
			if (!"ideaSyncTask".equals(task.getName())) {
				return;
			}

			if (!target.isConfigured()) {
				task.dependsOn(generateStub);
			}

			if (target.isConfigured()) {
				task.dependsOn(generateConstants);
			}
		});
	}

	private void configureSharedProjectConsumption() {
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

		getTasks().named(JavaPlugin.JAR_TASK_NAME, Jar.class).configure(task -> {
			for (Project sharedProject : sharedProjects) {
				task.dependsOn(sharedProject.getTasks().named(JavaPlugin.CLASSES_TASK_NAME));
				task.from(SourceSetHelper.getMainSourceSet(sharedProject).getOutput());
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
				task.dependsOn(sharedProject.getTasks().named(JavaPlugin.JAR_TASK_NAME));
				task.classpath(sharedProject.getTasks().named(JavaPlugin.JAR_TASK_NAME, Jar.class).flatMap(Jar::getArchiveFile));
			}
		});

		getTasks().matching(task -> task.getName().startsWith("generateDLIConfig")
				|| task.getName().startsWith("configureClientLaunch")
				|| task.getName().startsWith("configureLaunch"))
				.configureEach(task -> {
					for (Project sharedProject : sharedProjects) {
						task.dependsOn(sharedProject.getTasks().named(JavaPlugin.JAR_TASK_NAME));
					}
				});
	}
}
