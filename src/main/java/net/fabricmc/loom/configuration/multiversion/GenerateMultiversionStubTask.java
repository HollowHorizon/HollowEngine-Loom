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
import java.io.StringWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.DependencyArtifact;
import org.gradle.api.artifacts.ModuleDependency;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.LoomGradleExtension;
import net.fabricmc.loom.api.mappings.layered.MappingsNamespace;
import net.fabricmc.loom.configuration.DependencyInfo;
import net.fabricmc.loom.configuration.mods.dependency.LocalMavenHelper;
import net.fabricmc.loom.configuration.providers.BundleMetadata;
import net.fabricmc.loom.configuration.providers.mappings.LayeredMappingSpec;
import net.fabricmc.loom.configuration.providers.mappings.LayeredMappingsProcessor;
import net.fabricmc.loom.configuration.providers.mappings.MappingConfiguration;
import net.fabricmc.loom.configuration.providers.mappings.intermediary.IntermediaryMappingLayer;
import net.fabricmc.loom.configuration.providers.mappings.mojmap.MojangMappingLayer;
import net.fabricmc.loom.configuration.providers.mappings.mojmap.MojangMappingsSpec;
import net.fabricmc.loom.configuration.providers.mappings.parchment.ParchmentMappingLayer;
import net.fabricmc.loom.configuration.providers.mappings.utils.AddConstructorMappingVisitor;
import net.fabricmc.loom.configuration.providers.minecraft.MergedMinecraftProvider;
import net.fabricmc.loom.configuration.providers.minecraft.MinecraftVersionMeta;
import net.fabricmc.loom.configuration.providers.minecraft.VersionsManifest;
import net.fabricmc.loom.task.AbstractLoomTask;
import net.fabricmc.loom.util.Constants;
import net.fabricmc.loom.util.FileSystemUtil;
import net.fabricmc.loom.util.TinyRemapperHelper;
import net.fabricmc.loom.util.ZipUtils;
import net.fabricmc.loom.util.download.Download;
import net.fabricmc.loom.util.gradle.GradleUtils;
import net.fabricmc.mappingio.MappingReader;
import net.fabricmc.mappingio.adapter.MappingDstNsReorder;
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch;
import net.fabricmc.mappingio.format.tiny.Tiny2FileWriter;
import net.fabricmc.mappingio.tree.MemoryMappingTree;
import net.fabricmc.tinyremapper.OutputConsumerPath;
import net.fabricmc.tinyremapper.TinyRemapper;

public abstract class GenerateMultiversionStubTask extends AbstractLoomTask {
	@Input
	public abstract MapProperty<String, String> getVersionMappings();

	@Input
	public abstract Property<String> getBaseVersion();

	@Input
	public abstract Property<String> getStubVersion();

	@Input
	public abstract Property<String> getConstantsClass();

	@OutputFile
	public abstract RegularFileProperty getStubJar();

	@OutputFile
	public abstract RegularFileProperty getSourcesJar();

	@Internal
	public abstract RegularFileProperty getPublishedStubJar();

	@Internal
	public abstract RegularFileProperty getPublishedSourcesJar();

	@TaskAction
	public void generate() throws Exception {
		final Path workingDirectory = MultiversionSupport.createWorkingDirectory(getProject(), getStubVersion().get());
		final MultiversionCollector collector = new MultiversionCollector();

		for (Map.Entry<String, String> entry : new LinkedHashMap<>(getVersionMappings().get()).entrySet()) {
			for (Path namedJar : resolveNamedJars(workingDirectory, entry.getKey(), entry.getValue())) {
				collector.addVersion(entry.getKey(), namedJar);
			}
		}

		final Path stubJar = getStubJar().get().getAsFile().toPath();
		final Path sourcesJar = getSourcesJar().get().getAsFile().toPath();
		Files.createDirectories(stubJar.getParent());
		Files.createDirectories(sourcesJar.getParent());
		new MultiversionStubGenerator().generate(collector, stubJar, sourcesJar, getConstantsClass().get());

		final LocalMavenHelper helper = MultiversionSupport.createStubMavenHelper(getProject(), getStubVersion().get());
		helper.copyToMaven(stubJar, null);
		helper.copyToMaven(sourcesJar, "sources");
	}

	private Path resolveNamedJar(Path workingDirectory, String minecraftVersion, String mappingsNotation) throws Exception {
		final Path versionDirectory = workingDirectory.resolve(minecraftVersion.replace(':', '_'));
		Files.createDirectories(versionDirectory);
		final Path mergedJar = resolveMergedOfficialJar(versionDirectory, minecraftVersion);
		final Path intermediaryTiny = resolveIntermediaryTiny(versionDirectory, minecraftVersion);
		final Path intermediaryJar = versionDirectory.resolve("minecraft-intermediary.jar");
		final Path mappingsJar = resolveMappingsJar(versionDirectory, mappingsNotation);
		final Path mappingsTiny = versionDirectory.resolve("mappings.tiny");
		final Path namedJar = versionDirectory.resolve("minecraft-named.jar");

		if (Files.exists(namedJar) && !getExtension().refreshDeps()) {
			return namedJar;
		}

		remapJar(mergedJar, intermediaryJar, intermediaryTiny, "official", "intermediary");
		MappingConfiguration.extractMappings(mappingsJar, mappingsTiny);
		remapJar(intermediaryJar, namedJar, mappingsTiny, "intermediary", "named");

		return namedJar;
	}

	private List<Path> resolveNamedJars(Path workingDirectory, String minecraftVersion, String mappingsNotation) throws Exception {
		final List<Path> processedTargetJars = resolveProcessedNamedJarsFromTargets(minecraftVersion);

		if (!processedTargetJars.isEmpty()) {
			return processedTargetJars;
		}

		return List.of(resolveNamedJar(workingDirectory, minecraftVersion, mappingsNotation));
	}

	private List<Path> resolveProcessedNamedJarsFromTargets(String minecraftVersion) {
		final Set<Path> paths = new LinkedHashSet<>();

		for (Project candidate : getProject().getRootProject().getAllprojects()) {
			if (candidate == getProject() || !GradleUtils.isLoomProject(candidate)) {
				continue;
			}

			final LoomGradleExtension extension = LoomGradleExtension.get(candidate);
			final MultiversionTarget target = (MultiversionTarget) extension.getMultiversionTarget();

			if (!target.isConfigured() || !minecraftVersion.equals(target.getMinecraftVersion().getOrNull())) {
				continue;
			}

			paths.addAll(extension.getMinecraftJars(MappingsNamespace.NAMED));
		}

		return new ArrayList<>(paths);
	}

	private void remapJar(Path inputJar, Path outputJar, Path mappingsTiny, String fromNamespace, String toNamespace) throws IOException {
		final TinyRemapper remapper = TinyRemapper.newRemapper()
				.withMappings(TinyRemapperHelper.create(mappingsTiny, fromNamespace, toNamespace, false))
				.build();

		try (OutputConsumerPath outputConsumer = new OutputConsumerPath.Builder(outputJar).build()) {
			outputConsumer.addNonClassFiles(inputJar);
			remapper.readInputs(inputJar);
			remapper.apply(outputConsumer);
		} finally {
			remapper.finish();
		}
	}

	private Path resolveIntermediaryTiny(Path versionDirectory, String minecraftVersion) throws IOException {
		final Path intermediaryTiny = versionDirectory.resolve("intermediary.tiny");

		if (Files.exists(intermediaryTiny) && !getExtension().refreshDeps()) {
			return intermediaryTiny;
		}

		final String encodedMcVersion = URLEncoder.encode(minecraftVersion, StandardCharsets.UTF_8);
		final ModuleDependency intermediaryDependency = (ModuleDependency) getProject().getDependencies().create("net.fabricmc:intermediary:" + encodedMcVersion);
		intermediaryDependency.artifact(new org.gradle.api.Action<DependencyArtifact>() {
			@Override
			public void execute(DependencyArtifact dependencyArtifact) {
				dependencyArtifact.setClassifier("v2");
			}
		});

		final Configuration configuration = getProject().getConfigurations().detachedConfiguration(intermediaryDependency);
		configuration.setTransitive(false);

		final Path intermediaryJar = versionDirectory.resolve("intermediary-mappings.jar");
		Files.copy(configuration.getSingleFile().toPath(), intermediaryJar, StandardCopyOption.REPLACE_EXISTING);
		MappingConfiguration.extractMappings(intermediaryJar, intermediaryTiny);
		return intermediaryTiny;
	}

	private Path resolveMappingsJar(Path versionDirectory, String mappingsNotation) {
		final Configuration configuration = getProject().getConfigurations().detachedConfiguration(getProject().getDependencies().create(mappingsNotation));
		configuration.setTransitive(false);
		final Path resolvedPath = DependencyInfo.create(getProject(), configuration).resolveFile()
				.orElseThrow(() -> new IllegalStateException("Failed to resolve mappings " + mappingsNotation))
				.toPath();

		if (isTinyMappingsJar(resolvedPath)) {
			return resolvedPath;
		}

		if (isParchmentZip(resolvedPath)) {
			try {
				return synthesizeLayeredMappingsJar(versionDirectory, mappingsNotation, resolvedPath);
			} catch (Exception e) {
				throw new RuntimeException("Failed to synthesize layered mappings from " + mappingsNotation, e);
			}
		}

		throw new IllegalStateException("Mappings " + mappingsNotation + " do not contain mappings/mappings.tiny and are not a parchment zip");
	}

	private boolean isTinyMappingsJar(Path path) {
		try (FileSystemUtil.Delegate delegate = FileSystemUtil.getJarFileSystem(path, false)) {
			return Files.exists(delegate.get().getPath("mappings", "mappings.tiny"));
		} catch (IOException e) {
			return false;
		}
	}

	private boolean isParchmentZip(Path path) {
		try (FileSystemUtil.Delegate delegate = FileSystemUtil.getJarFileSystem(path, false)) {
			return Files.exists(delegate.get().getPath("parchment.json"));
		} catch (IOException e) {
			return false;
		}
	}

	private Path synthesizeLayeredMappingsJar(Path versionDirectory, String mappingsNotation, Path parchmentZip) throws Exception {
		final Path layeredMappingsJar = versionDirectory.resolve("layered-%s.jar".formatted(sanitizeFileComponent(mappingsNotation)));

		if (Files.exists(layeredMappingsJar) && !getExtension().refreshDeps()) {
			return layeredMappingsJar;
		}

		final Path intermediaryTiny = resolveIntermediaryTiny(versionDirectory, versionDirectory.getFileName().toString().replace('_', ':'));
		final MinecraftVersionMeta versionMeta = resolveVersionMeta(versionDirectory.getFileName().toString().replace('_', ':'));
		final Path clientMappings = download(versionMeta.download("client_mappings"), versionDirectory.resolve("client-mappings.txt"));
		final Path serverMappings = download(versionMeta.download("server_mappings"), versionDirectory.resolve("server-mappings.txt"));
		final Supplier<MemoryMappingTree> intermediarySupplier = () -> {
			try {
				final MemoryMappingTree tree = new MemoryMappingTree();
				MappingReader.read(intermediaryTiny, tree);
				return tree;
			} catch (IOException e) {
				throw new RuntimeException("Failed to read intermediary mappings from " + intermediaryTiny, e);
			}
		};

		final List<net.fabricmc.loom.api.mappings.layered.MappingLayer> layers = List.of(
				new IntermediaryMappingLayer(intermediarySupplier),
				new MojangMappingLayer(
						versionDirectory.getFileName().toString().replace('_', ':'),
						clientMappings,
						serverMappings,
						false,
						false,
						intermediarySupplier,
						getProject().getLogger(),
						new MojangMappingsSpec.SilenceLicenseOption(() -> true)
				),
				new ParchmentMappingLayer(parchmentZip, true)
		);

		final MemoryMappingTree mappings = new LayeredMappingsProcessor(new LayeredMappingSpec(List.of()), false).getMappings(layers);

		try (StringWriter writer = new StringWriter()) {
			final var tinyWriter = new Tiny2FileWriter(writer, false);
			final var dstReorder = new MappingDstNsReorder(tinyWriter, List.of("named", "official"));
			final var srcSwitch = new MappingSourceNsSwitch(dstReorder, "intermediary", true);
			final var addConstructors = new AddConstructorMappingVisitor(srcSwitch);
			mappings.accept(addConstructors);
			Files.deleteIfExists(layeredMappingsJar);
			ZipUtils.add(layeredMappingsJar, "mappings/mappings.tiny", writer.toString().getBytes(StandardCharsets.UTF_8));
		}

		return layeredMappingsJar;
	}

	private String sanitizeFileComponent(String input) {
		return input.replace(':', '_').replace('@', '_').replace('/', '_').replace('\\', '_');
	}

	private MinecraftVersionMeta resolveVersionMeta(String minecraftVersion) throws Exception {
		final Path metadataDirectory = MultiversionSupport.createWorkingDirectory(getProject(), getStubVersion().get()).resolve("metadata");
		Files.createDirectories(metadataDirectory);
		final Path manifestPath = metadataDirectory.resolve("version_manifest.json");
		final String manifestJson = Download.create(Constants.VERSION_MANIFESTS).defaultCache().downloadString(manifestPath);
		final VersionsManifest manifest = LoomGradlePlugin.GSON.fromJson(manifestJson, VersionsManifest.class);
		final VersionsManifest.Version version = manifest.getVersion(minecraftVersion);

		if (version == null) {
			throw new IllegalStateException("Failed to find Minecraft version " + minecraftVersion);
		}

		final Path versionMetaPath = metadataDirectory.resolve("version_meta_%s.json".formatted(minecraftVersion));
		final String versionMetaJson = Download.create(version.url).defaultCache().downloadString(versionMetaPath);
		return LoomGradlePlugin.GSON.fromJson(versionMetaJson, MinecraftVersionMeta.class);
	}

	private Path resolveMergedOfficialJar(Path versionDirectory, String minecraftVersion) throws Exception {
		final String manifestJson = Download.create(Constants.VERSION_MANIFESTS).defaultCache().downloadString(versionDirectory.resolve("version_manifest.json"));
		final VersionsManifest manifest = LoomGradlePlugin.GSON.fromJson(manifestJson, VersionsManifest.class);
		final VersionsManifest.Version version = manifest.getVersion(minecraftVersion);

		if (version == null) {
			throw new IllegalStateException("Failed to find Minecraft version " + minecraftVersion);
		}

		final String versionMetaJson = Download.create(version.url).defaultCache().downloadString(versionDirectory.resolve("version_meta.json"));
		final MinecraftVersionMeta meta = LoomGradlePlugin.GSON.fromJson(versionMetaJson, MinecraftVersionMeta.class);
		final Path clientJar = download(meta.download("client"), versionDirectory.resolve("minecraft-client.jar"));
		Path serverJar = download(meta.download("server"), versionDirectory.resolve("minecraft-server.jar"));
		final BundleMetadata bundleMetadata = BundleMetadata.fromJar(serverJar);

		if (bundleMetadata != null && !bundleMetadata.versions().isEmpty()) {
			final Path extractedServer = versionDirectory.resolve("minecraft-extracted-server.jar");
			bundleMetadata.versions().get(0).unpackEntry(serverJar, extractedServer, getProject());
			serverJar = extractedServer;
		}

		final Path mergedJar = versionDirectory.resolve("minecraft-merged.jar");

		if (Files.notExists(mergedJar) || getExtension().refreshDeps()) {
			MergedMinecraftProvider.mergeJars(clientJar.toFile(), serverJar.toFile(), mergedJar.toFile());
		}

		return mergedJar;
	}

	private Path download(MinecraftVersionMeta.Download download, Path target) throws Exception {
		Download.create(download.url())
				.sha1(download.sha1())
				.defaultCache()
				.downloadPath(target);
		return target;
	}
}
