package ru.hollowhorizon.hollowengineloom.idea.inspection;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

final class VersionConditionSupport {
	private VersionConditionSupport() {
	}

	static Evaluation evaluation(Set<Integer> universe, IntVersionPredicate predicate) {
		final Set<Integer> trueVersions = new LinkedHashSet<>();
		final Set<Integer> falseVersions = new LinkedHashSet<>();

		for (Integer version : universe) {
			final Boolean result = predicate.test(version);

			if (result == null) {
				return null;
			}

			if (result) {
				trueVersions.add(version);
			} else {
				falseVersions.add(version);
			}
		}

		return new Evaluation(trueVersions, falseVersions);
	}

	static Set<Integer> copy(Set<Integer> versions) {
		return versions == null ? new LinkedHashSet<>() : new LinkedHashSet<>(versions);
	}

	static Set<Integer> intersect(Set<Integer> left, Collection<Integer> right) {
		if (left == null) {
			return right == null ? new LinkedHashSet<>() : new LinkedHashSet<>(right);
		}

		if (right == null || right.isEmpty()) {
			return new LinkedHashSet<>(left);
		}

		final Set<Integer> intersection = new LinkedHashSet<>(left);
		intersection.retainAll(right);
		return intersection;
	}

	static Integer resolveConstantsFieldValue(String name, int packedVersion) {
		return switch (name) {
		case "MINECRAFT_VERSION", "MINECRAFT" -> packedVersion;
		case "MC_MAJOR" -> packedVersion / 1000;
		case "MC_MINOR" -> (packedVersion % 1000) / 10;
		case "MC_PATCH" -> packedVersion % 10;
		default -> name != null && name.startsWith("V")
				? RequiresApiInspectionSupport.packVersion(name.substring(1).replace('_', '.'))
				: null;
		};
	}

	record Evaluation(Set<Integer> trueVersions, Set<Integer> falseVersions) {
	}

	@FunctionalInterface
	interface IntVersionPredicate {
		Boolean test(int packedVersion);
	}
}
