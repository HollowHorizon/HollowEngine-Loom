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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MultiversionClassInfo {
	private final String name;
	private final int access;
	private final String signature;
	private final String superName;
	private final Map<String, Set<String>> interfaces = new LinkedHashMap<>();
	private int innerAccess;
	private final Set<String> versions = new LinkedHashSet<>();
	private final Map<MethodSignature, MultiversionMethodInfo> methods = new LinkedHashMap<>();
	private final Map<FieldSignature, MultiversionFieldInfo> fields = new LinkedHashMap<>();

	public MultiversionClassInfo(String name, int access, String signature, String superName, List<String> interfaces) {
		this.name = name;
		this.access = access;
		this.signature = signature;
		this.superName = superName;
		this.innerAccess = access;

		if (interfaces != null) {
			for (String interfaceName : interfaces) {
				this.interfaces.put(interfaceName, new LinkedHashSet<>());
			}
		}
	}

	public String getName() {
		return name;
	}

	public int getAccess() {
		return access;
	}

	public String getSignature() {
		return signature;
	}

	public String getSuperName() {
		return superName;
	}

	public Map<String, Set<String>> getInterfaces() {
		return interfaces;
	}

	public int getInnerAccess() {
		return innerAccess;
	}

	public void setInnerAccess(int innerAccess) {
		this.innerAccess = innerAccess;
	}

	public Set<String> getVersions() {
		return versions;
	}

	public Map<MethodSignature, MultiversionMethodInfo> getMethods() {
		return methods;
	}

	public Map<FieldSignature, MultiversionFieldInfo> getFields() {
		return fields;
	}

	public record MethodSignature(String name, String descriptor) { }

	public record FieldSignature(String name, String descriptor) { }

	public static final class MultiversionMethodInfo {
		private final String name;
		private final String descriptor;
		private final String signature;
		private final List<String> exceptions;
		private final int access;
		private final Set<String> versions = new LinkedHashSet<>();

		public MultiversionMethodInfo(String name, String descriptor, String signature, List<String> exceptions, int access) {
			this.name = name;
			this.descriptor = descriptor;
			this.signature = signature;
			this.exceptions = exceptions == null ? List.of() : List.copyOf(exceptions);
			this.access = access;
		}

		public String getName() {
			return name;
		}

		public String getDescriptor() {
			return descriptor;
		}

		public String getSignature() {
			return signature;
		}

		public List<String> getExceptions() {
			return exceptions;
		}

		public int getAccess() {
			return access;
		}

		public Set<String> getVersions() {
			return versions;
		}
	}

	public static final class MultiversionFieldInfo {
		private final String name;
		private final String descriptor;
		private final String signature;
		private final Object value;
		private final int access;
		private final Set<String> versions = new LinkedHashSet<>();

		public MultiversionFieldInfo(String name, String descriptor, String signature, Object value, int access) {
			this.name = name;
			this.descriptor = descriptor;
			this.signature = signature;
			this.value = value;
			this.access = access;
		}

		public String getName() {
			return name;
		}

		public String getDescriptor() {
			return descriptor;
		}

		public String getSignature() {
			return signature;
		}

		public Object getValue() {
			return value;
		}

		public int getAccess() {
			return access;
		}

		public Set<String> getVersions() {
			return versions;
		}
	}

	public void verifyCompatible(MultiversionClassInfo other) {
		// Keep the base-version header when class kind drifts across versions.
		// This keeps superset stub generation usable for wider version jumps such as 1.20.1 -> 1.21.1.
	}

	public void addInterfaces(List<String> interfaces, String version) {
		if (interfaces == null) {
			return;
		}

		for (String interfaceName : interfaces) {
			this.interfaces.computeIfAbsent(interfaceName, unused -> new LinkedHashSet<>()).add(version);
		}
	}

	public List<MultiversionMethodInfo> constructors() {
		final List<MultiversionMethodInfo> constructors = new ArrayList<>();

		for (MultiversionMethodInfo method : methods.values()) {
			if ("<init>".equals(method.getName())) {
				constructors.add(method);
			}
		}

		return constructors;
	}
}
