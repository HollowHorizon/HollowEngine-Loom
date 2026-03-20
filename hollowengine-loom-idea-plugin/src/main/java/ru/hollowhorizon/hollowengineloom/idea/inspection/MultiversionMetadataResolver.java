package ru.hollowhorizon.hollowengineloom.idea.inspection;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.Gson;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.CommonClassNames;
import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiEllipsisType;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMember;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiType;
import com.intellij.psi.PsiTypes;
import com.intellij.psi.util.ClassUtil;

final class MultiversionMetadataResolver {
	private static final Gson GSON = new Gson();
	private static final String METADATA_PATH = "META-INF/loom/multiversion-api.json";
	private static final Map<String, CachedMetadata> CACHE = new ConcurrentHashMap<>();

	private MultiversionMetadataResolver() {
	}

	static Set<Integer> resolveVersions(PsiModifierListOwner owner, Project project) {
		final Set<String> versions = resolveRawVersions(owner, project);
		final Set<Integer> packed = new LinkedHashSet<>();

		for (String version : versions) {
			packed.add(RequiresApiInspectionSupport.packVersion(version));
		}

		return packed;
	}

	static Set<Integer> resolveAvailableVersions(Project project) {
		final Set<Integer> packed = new LinkedHashSet<>();

		for (String version : resolveMetadata(project, metadata -> metadata.availableVersions == null ? Set.of() : metadata.availableVersions)) {
			packed.add(RequiresApiInspectionSupport.packVersion(version));
		}

		return packed;
	}

	private static Set<String> resolveRawVersions(PsiModifierListOwner owner, Project project) {
		if (owner instanceof PsiMethod method) {
			return resolveMetadata(project, metadata -> {
				final PsiClass ownerClass = method.getContainingClass();

				if (ownerClass == null) {
					return Set.of();
				}

				return metadata.methods.getOrDefault(methodKey(ownerClass, method), Set.of());
			});
		}

		if (owner instanceof PsiField field) {
			return resolveMetadata(project, metadata -> {
				final PsiClass ownerClass = field.getContainingClass();

				if (ownerClass == null) {
					return Set.of();
				}

				return metadata.fields.getOrDefault(fieldKey(ownerClass, field), Set.of());
			});
		}

		if (owner instanceof PsiClass psiClass) {
			return resolveMetadata(project, metadata -> metadata.classes.getOrDefault(internalName(psiClass), Set.of()));
		}

		if (owner instanceof PsiMember member && member.getContainingClass() != null) {
			return resolveRawVersions(member.getContainingClass(), project);
		}

		return Set.of();
	}

	private static Set<String> resolveMetadata(Project project, java.util.function.Function<MultiversionApiMetadataModel, Set<String>> extractor) {
		final MultiversionApiMetadataModel metadata = resolveMergedMetadata(project);
		final Set<String> versions = extractor.apply(metadata);
		return versions == null ? Set.of() : versions;
	}

	private static MultiversionApiMetadataModel resolveMergedMetadata(Project project) {
		final long modificationCount = ProjectRootManager.getInstance(project).getModificationCount();
		final String key = project.getLocationHash();
		final CachedMetadata cached = CACHE.get(key);

		if (cached != null && cached.modificationCount == modificationCount) {
			return cached.metadata;
		}

		final MultiversionApiMetadataModel merged = new MultiversionApiMetadataModel();
		merged.availableVersions = new LinkedHashSet<>();
		merged.classes = new LinkedHashMap<>();
		merged.methods = new LinkedHashMap<>();
		merged.fields = new LinkedHashMap<>();

		for (VirtualFile root : ProjectRootManager.getInstance(project).orderEntries().classes().getRoots()) {
			final VirtualFile metadataFile = root.findFileByRelativePath(METADATA_PATH);

			if (metadataFile == null) {
				continue;
			}

			try (var stream = metadataFile.getInputStream(); var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
				final MultiversionApiMetadataModel metadata = GSON.fromJson(reader, MultiversionApiMetadataModel.class);

				if (metadata == null) {
					continue;
				}

				if (metadata.availableVersions != null) {
					merged.availableVersions.addAll(metadata.availableVersions);
				}

				if (metadata.classes != null) {
					merged.classes.putAll(metadata.classes);
				}

				if (metadata.methods != null) {
					merged.methods.putAll(metadata.methods);
				}

				if (metadata.fields != null) {
					merged.fields.putAll(metadata.fields);
				}
			} catch (IOException ignored) {
			}
		}

		CACHE.put(key, new CachedMetadata(modificationCount, merged));
		return merged;
	}

	private static String methodKey(PsiClass ownerClass, PsiMethod method) {
		return internalName(ownerClass) + "#" + method.getName() + descriptor(method);
	}

	private static String fieldKey(PsiClass ownerClass, PsiField field) {
		return internalName(ownerClass) + "#" + field.getName() + ":" + descriptor(field.getType());
	}

	private static String internalName(PsiClass psiClass) {
		final String jvmName = ClassUtil.getJVMClassName(psiClass);

		if (jvmName != null) {
			return jvmName.replace('.', '/');
		}

		final String qualifiedName = psiClass.getQualifiedName();
		return qualifiedName == null ? psiClass.getName() : qualifiedName.replace('.', '/');
	}

	private static String descriptor(PsiMethod method) {
		final StringBuilder descriptor = new StringBuilder();
		descriptor.append('(');

		for (var parameter : method.getParameterList().getParameters()) {
			descriptor.append(descriptor(parameter.getType()));
		}

		descriptor.append(')');
		descriptor.append(descriptor(method.getReturnType() == null ? PsiTypes.voidType() : method.getReturnType()));
		return descriptor.toString();
	}

	private static String descriptor(PsiType type) {
		if (type instanceof PsiPrimitiveType primitiveType) {
			if (PsiTypes.voidType().equals(primitiveType)) return "V";
			if (PsiTypes.booleanType().equals(primitiveType)) return "Z";
			if (PsiTypes.charType().equals(primitiveType)) return "C";
			if (PsiTypes.byteType().equals(primitiveType)) return "B";
			if (PsiTypes.shortType().equals(primitiveType)) return "S";
			if (PsiTypes.intType().equals(primitiveType)) return "I";
			if (PsiTypes.floatType().equals(primitiveType)) return "F";
			if (PsiTypes.longType().equals(primitiveType)) return "J";
			if (PsiTypes.doubleType().equals(primitiveType)) return "D";
		}

		if (type instanceof PsiEllipsisType ellipsisType) {
			return "[" + descriptor(ellipsisType.getComponentType());
		}

		if (type instanceof PsiArrayType arrayType) {
			return "[" + descriptor(arrayType.getComponentType());
		}

		if (type instanceof PsiClassType classType) {
			final PsiClass resolved = classType.resolve();

			if (resolved != null) {
				return "L" + internalName(resolved) + ";";
			}

			final String canonical = classType.rawType().getCanonicalText();
			return "L" + canonical.replace('.', '/').replace('$', '/') + ";";
		}

		final String canonical = type.getCanonicalText();

		if (CommonClassNames.JAVA_LANG_OBJECT.equals(canonical)) {
			return "Ljava/lang/Object;";
		}

		return "L" + canonical.replace('.', '/').replace('$', '/') + ";";
	}

	private record CachedMetadata(long modificationCount, MultiversionApiMetadataModel metadata) { }
}
