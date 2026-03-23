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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class MultiversionSyntheticMemberNames {
	private final Map<MultiversionClassInfo.MethodSignature, String> methods;
	private final Map<MultiversionClassInfo.FieldSignature, String> fields;

	private MultiversionSyntheticMemberNames(Map<MultiversionClassInfo.MethodSignature, String> methods, Map<MultiversionClassInfo.FieldSignature, String> fields) {
		this.methods = methods;
		this.fields = fields;
	}

	static MultiversionSyntheticMemberNames of(MultiversionClassInfo classInfo) {
		return new MultiversionSyntheticMemberNames(resolveMethodNames(classInfo), resolveFieldNames(classInfo));
	}

	String methodName(MultiversionClassInfo.MultiversionMethodInfo method) {
		return methods.getOrDefault(new MultiversionClassInfo.MethodSignature(method.getName(), method.getDescriptor()), method.getName());
	}

	String fieldName(MultiversionClassInfo.MultiversionFieldInfo field) {
		return fields.getOrDefault(new MultiversionClassInfo.FieldSignature(field.getName(), field.getDescriptor()), field.getName());
	}

	private static Map<MultiversionClassInfo.MethodSignature, String> resolveMethodNames(MultiversionClassInfo classInfo) {
		final Map<String, List<MultiversionClassInfo.MultiversionMethodInfo>> grouped = groupMethodsBySourceSignature(classInfo.getMethods().values());
		final Set<String> occupied = new LinkedHashSet<>();
		final Map<MultiversionClassInfo.MethodSignature, String> resolved = new LinkedHashMap<>();

		for (Map.Entry<String, List<MultiversionClassInfo.MultiversionMethodInfo>> entry : grouped.entrySet()) {
			final List<MultiversionClassInfo.MultiversionMethodInfo> methods = entry.getValue();
			final Set<String> returnTypes = methods.stream()
					.map(method -> methodReturnDescriptor(method.getDescriptor()))
					.collect(Collectors.toCollection(LinkedHashSet::new));
			final boolean needsSynthesis = returnTypes.size() > 1;

			for (MultiversionClassInfo.MultiversionMethodInfo method : methods) {
				final String originalName = method.getName();
				if (!needsSynthesis) {
					resolved.put(new MultiversionClassInfo.MethodSignature(originalName, method.getDescriptor()), originalName);
					continue;
				}

				final String candidate = synthesizeName(originalName, method.getVersions());
				final String resolvedName = candidate.equals(originalName) && !occupied.contains(originalName) ? originalName : uniqueName(candidate, occupied, method.getDescriptor());
				occupied.add(resolvedName);
				resolved.put(new MultiversionClassInfo.MethodSignature(originalName, method.getDescriptor()), resolvedName);
			}
		}

		return resolved;
	}

	private static Map<MultiversionClassInfo.FieldSignature, String> resolveFieldNames(MultiversionClassInfo classInfo) {
		final Map<String, List<MultiversionClassInfo.MultiversionFieldInfo>> grouped = groupByName(classInfo.getFields().values());
		final Set<String> occupied = new LinkedHashSet<>();
		final Map<MultiversionClassInfo.FieldSignature, String> resolved = new LinkedHashMap<>();

		for (Map.Entry<String, List<MultiversionClassInfo.MultiversionFieldInfo>> entry : grouped.entrySet()) {
			final List<MultiversionClassInfo.MultiversionFieldInfo> fields = entry.getValue();
			for (MultiversionClassInfo.MultiversionFieldInfo field : fields) {
				final String originalName = field.getName();
				final String candidate = fields.size() == 1 ? originalName : synthesizeName(originalName, field.getVersions());
				final String resolvedName = candidate.equals(originalName) && !occupied.contains(originalName) ? originalName : uniqueName(candidate, occupied, field.getDescriptor());
				occupied.add(resolvedName);
				resolved.put(new MultiversionClassInfo.FieldSignature(originalName, field.getDescriptor()), resolvedName);
			}
		}

		return resolved;
	}

	private static <T> Map<String, List<T>> groupByName(Iterable<T> members) {
		final Map<String, List<T>> grouped = new LinkedHashMap<>();

		for (T member : members) {
			final String name = memberName(member);
			grouped.computeIfAbsent(name, unused -> new ArrayList<>()).add(member);
		}

		for (List<T> group : grouped.values()) {
			group.sort(Comparator.comparing(member -> memberDescriptor(member)));
		}

		return grouped;
	}

	private static Map<String, List<MultiversionClassInfo.MultiversionMethodInfo>> groupMethodsBySourceSignature(Iterable<MultiversionClassInfo.MultiversionMethodInfo> methods) {
		final Map<String, List<MultiversionClassInfo.MultiversionMethodInfo>> grouped = new LinkedHashMap<>();

		for (MultiversionClassInfo.MultiversionMethodInfo method : methods) {
			final String key = method.getName() + methodSourceDescriptor(method.getDescriptor());
			grouped.computeIfAbsent(key, unused -> new ArrayList<>()).add(method);
		}

		for (List<MultiversionClassInfo.MultiversionMethodInfo> group : grouped.values()) {
			group.sort(Comparator.comparing(MultiversionClassInfo.MultiversionMethodInfo::getDescriptor));
		}

		return grouped;
	}

	private static <T> String memberName(T member) {
		if (member instanceof MultiversionClassInfo.MultiversionMethodInfo method) {
			return method.getName();
		}

		if (member instanceof MultiversionClassInfo.MultiversionFieldInfo field) {
			return field.getName();
		}

		throw new IllegalArgumentException("Unsupported member type: " + member);
	}

	private static <T> String memberDescriptor(T member) {
		if (member instanceof MultiversionClassInfo.MultiversionMethodInfo method) {
			return method.getDescriptor();
		}

		if (member instanceof MultiversionClassInfo.MultiversionFieldInfo field) {
			return field.getDescriptor();
		}

		throw new IllegalArgumentException("Unsupported member type: " + member);
	}

	private static String synthesizeName(String originalName, Set<String> versions) {
		final String suffix = versions.stream()
				.sorted()
				.map(MultiversionSyntheticMemberNames::sanitizeVersion)
				.collect(Collectors.joining("_"));
		return originalName + "$" + suffix;
	}

	private static String methodSourceDescriptor(String descriptor) {
		final int end = descriptor.indexOf(')');
		return end < 0 ? descriptor : descriptor.substring(0, end + 1);
	}

	private static String methodReturnDescriptor(String descriptor) {
		final int end = descriptor.indexOf(')');
		return end < 0 || end + 1 >= descriptor.length() ? descriptor : descriptor.substring(end + 1);
	}

	private static String sanitizeVersion(String version) {
		final StringBuilder builder = new StringBuilder();

		for (int index = 0; index < version.length(); index++) {
			final char character = version.charAt(index);
			builder.append(Character.isLetterOrDigit(character) ? character : '_');
		}

		return builder.length() == 0 ? "v" : builder.toString();
	}

	private static String uniqueName(String candidate, Set<String> occupied, String descriptor) {
		if (!occupied.contains(candidate)) {
			return candidate;
		}

		final String next = candidate + "_" + sanitizeDescriptorToken(descriptor);

		if (!occupied.contains(next)) {
			return next;
		}

		for (int index = 2; ; index++) {
			final String fallback = next + "_" + index;

			if (!occupied.contains(fallback)) {
				return fallback;
			}
		}
	}

	private static String sanitizeDescriptorToken(String descriptor) {
		final StringBuilder builder = new StringBuilder();

		for (int index = 0; index < descriptor.length(); index++) {
			final char character = descriptor.charAt(index);
			if (Character.isLetterOrDigit(character)) {
				builder.append(character);
			} else {
				builder.append('_');
			}
		}

		return builder.toString();
	}
}
