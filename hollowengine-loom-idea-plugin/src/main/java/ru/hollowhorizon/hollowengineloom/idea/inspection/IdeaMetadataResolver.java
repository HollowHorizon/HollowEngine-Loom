package ru.hollowhorizon.hollowengineloom.idea.inspection;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.Gson;
import com.intellij.openapi.module.Module;

final class IdeaMetadataResolver {
	private static final Gson GSON = new Gson();
	private static final Map<String, IdeaMultiversionMetadata> CACHE = new ConcurrentHashMap<>();
	private static final String RELATIVE_PATH = "build/loom-multiversion/idea/module.json";

	private IdeaMetadataResolver() {
	}

	static IdeaMultiversionMetadata resolve(Module module) {
		if (module == null) {
			return null;
		}

		return CACHE.computeIfAbsent(module.getProject().getLocationHash() + ":" + module.getName(), key -> load(module));
	}

	private static IdeaMultiversionMetadata load(Module module) {
		final Path moduleDir = ProjectVersionResolver.moduleDirectory(module);

		if (moduleDir == null) {
			return null;
		}

		final Path metadataPath = moduleDir.resolve(RELATIVE_PATH);

		if (!Files.isRegularFile(metadataPath)) {
			return null;
		}

		try (var reader = new InputStreamReader(Files.newInputStream(metadataPath), StandardCharsets.UTF_8)) {
			return GSON.fromJson(reader, IdeaMultiversionMetadata.class);
		} catch (IOException ignored) {
			return null;
		}
	}
}
