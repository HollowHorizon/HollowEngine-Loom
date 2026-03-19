package ru.hollowhorizon.hollowengineloom.idea.inspection;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;

final class RequiresApiInspectionSupport {
	static final String REQUIRES_API_FQN = "multiversion.api.RequiresApi";
	private static final String MESSAGE_PREFIX = "This API is version-gated by @RequiresApi";

	private RequiresApiInspectionSupport() {
	}

	static Set<Integer> resolveProjectVersions(PsiElement context) {
		if (context == null) {
			return Set.of();
		}

		final Project project = context.getProject();
		return ProjectVersionResolver.resolve(project).stream()
				.map(RequiresApiInspectionSupport::packVersion)
				.collect(Collectors.toCollection(LinkedHashSet::new));
	}

	static boolean isAvailableEverywhere(Set<Integer> requiredVersions, Set<Integer> projectVersions) {
		return !requiredVersions.isEmpty() && !projectVersions.isEmpty() && requiredVersions.containsAll(projectVersions);
	}

	static int packVersion(String version) {
		final String[] parts = version.split("\\.");
		final int major = parts.length > 0 ? parsePart(parts[0]) : 0;
		final int minor = parts.length > 1 ? parsePart(parts[1]) : 0;
		final int patch = parts.length > 2 ? parsePart(parts[2]) : 0;
		return major * 1000 + minor * 10 + patch;
	}

	static String formatVersion(int packedVersion) {
		final int major = packedVersion / 1000;
		final int minor = (packedVersion % 1000) / 10;
		final int patch = packedVersion % 10;
		return major + "." + minor + "." + patch;
	}

	static String buildMessage(Set<Integer> requiredVersions) {
		final String sorted = requiredVersions.stream()
				.sorted()
				.map(RequiresApiInspectionSupport::formatVersion)
				.collect(Collectors.joining(", "));
		return MESSAGE_PREFIX + " for versions " + sorted + ". Wrap the usage in a matching Constants guard or annotate the enclosing declaration with @RequiresApi.";
	}

	private static int parsePart(String part) {
		final StringBuilder digits = new StringBuilder();

		for (int index = 0; index < part.length(); index++) {
			final char character = part.charAt(index);

			if (!Character.isDigit(character)) {
				break;
			}

			digits.append(character);
		}

		return digits.isEmpty() ? 0 : Integer.parseInt(digits.toString());
	}
}
