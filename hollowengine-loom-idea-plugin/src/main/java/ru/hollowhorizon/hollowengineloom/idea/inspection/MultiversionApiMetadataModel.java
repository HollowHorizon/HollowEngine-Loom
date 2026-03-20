package ru.hollowhorizon.hollowengineloom.idea.inspection;

import java.util.Map;
import java.util.Set;

final class MultiversionApiMetadataModel {
	int version;
	Set<String> availableVersions;
	Map<String, Set<String>> classes;
	Map<String, Set<String>> methods;
	Map<String, Set<String>> fields;
}
