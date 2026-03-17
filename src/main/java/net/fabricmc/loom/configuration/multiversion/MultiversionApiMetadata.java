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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public record MultiversionApiMetadata(
		int version,
		Set<String> availableVersions,
		Map<String, Set<String>> classes,
		Map<String, Set<String>> methods,
		Map<String, Set<String>> fields) {
	public static final int CURRENT_VERSION = 1;
	public static final String PATH = "META-INF/loom/multiversion-api.json";

	public static Builder builder(Set<String> availableVersions) {
		return new Builder(availableVersions);
	}

	public static final class Builder {
		private final Set<String> availableVersions;
		private final Map<String, Set<String>> classes = new LinkedHashMap<>();
		private final Map<String, Set<String>> methods = new LinkedHashMap<>();
		private final Map<String, Set<String>> fields = new LinkedHashMap<>();

		private Builder(Set<String> availableVersions) {
			this.availableVersions = availableVersions;
		}

		public void addClass(String internalName, Set<String> versions) {
			classes.put(internalName, Set.copyOf(versions));
		}

		public void addMethod(String owner, String name, String descriptor, Set<String> versions) {
			methods.put(methodKey(owner, name, descriptor), Set.copyOf(versions));
		}

		public void addField(String owner, String name, String descriptor, Set<String> versions) {
			fields.put(fieldKey(owner, name, descriptor), Set.copyOf(versions));
		}

		public MultiversionApiMetadata build() {
			return new MultiversionApiMetadata(CURRENT_VERSION, Set.copyOf(availableVersions), classes, methods, fields);
		}
	}

	public static String methodKey(String owner, String name, String descriptor) {
		return owner + "#" + name + descriptor;
	}

	public static String fieldKey(String owner, String name, String descriptor) {
		return owner + "#" + name + ":" + descriptor;
	}
}
