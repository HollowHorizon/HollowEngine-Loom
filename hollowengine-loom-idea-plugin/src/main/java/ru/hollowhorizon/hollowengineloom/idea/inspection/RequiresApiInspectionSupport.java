package ru.hollowhorizon.hollowengineloom.idea.inspection;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiImportStatementBase;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiModifierListOwner;
import com.intellij.psi.util.PsiTreeUtil;

import org.jetbrains.kotlin.psi.KtAnnotationEntry;
import org.jetbrains.kotlin.psi.KtClassOrObject;
import org.jetbrains.kotlin.psi.KtImportDirective;
import org.jetbrains.kotlin.psi.KtDeclaration;
import org.jetbrains.kotlin.psi.KtNamedFunction;

final class RequiresApiInspectionSupport {
	private static final String MESSAGE_PREFIX = "This API is version-gated by @RequiresApi";

	private RequiresApiInspectionSupport() {
	}

	static Set<Integer> resolveProjectVersions(PsiElement context) {
		if (context == null) {
			return Set.of();
		}

		final Project project = context.getProject();
		final Set<Integer> versions = ProjectVersionResolver.resolve(project).stream()
				.map(RequiresApiInspectionSupport::packVersion)
				.collect(Collectors.toCollection(LinkedHashSet::new));

		versions.addAll(MultiversionMetadataResolver.resolveAvailableVersions(project));
		return versions;
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

	static String buildTargetMismatchMessage(Set<Integer> requiredVersions, String targetVersion) {
		final String sorted = requiredVersions.stream()
				.sorted()
				.map(RequiresApiInspectionSupport::formatVersion)
				.collect(Collectors.joining(", "));
		return "This API is not available on target " + targetVersion + ". It is only available on " + sorted + ".";
	}

	static String buildImpossibleConditionMessage() {
		return "This condition is impossible for all configured Minecraft versions.";
	}

	static String buildConstantConditionMessage(boolean result) {
		return result
				? "This condition is always true for all configured Minecraft versions."
				: "This condition is always false for all configured Minecraft versions.";
	}

	static String buildCompletionHint(Set<Integer> requiredVersions, Set<Integer> projectVersions) {
		final Set<Integer> displayVersions = new LinkedHashSet<>(requiredVersions);

		if (!projectVersions.isEmpty()) {
			displayVersions.retainAll(projectVersions);
		}

		final Set<Integer> versions = displayVersions.isEmpty() ? requiredVersions : displayVersions;
		return versions.stream()
				.sorted()
				.map(RequiresApiInspectionSupport::formatVersion)
				.collect(Collectors.joining(", "));
	}

	static Set<Integer> resolveDirectRequiresApi(PsiModifierListOwner owner) {
		if (owner == null) {
			return Set.of();
		}

		final Set<Integer> versions = new LinkedHashSet<>();
		collectDirectRequiresApi(owner, versions, Collections.newSetFromMap(new IdentityHashMap<>()));

		return versions;
	}

	private static void collectDirectRequiresApi(PsiElement element, Set<Integer> versions, Set<PsiElement> visited) {
		if (element == null || !visited.add(element)) {
			return;
		}

		if (element instanceof PsiModifierListOwner owner) {
			addJavaAnnotationVersions(owner, versions);
		}

		if (element instanceof KtDeclaration declaration) {
			addKotlinAnnotationVersions(declaration, versions);
		}

		if (element instanceof PsiModifierListOwner owner) {
			final PsiElement original = owner.getOriginalElement();

			if (original != null && original != element) {
				collectDirectRequiresApi(original, versions, visited);
			}
		}

		final PsiElement navigation = element.getNavigationElement();

		if (navigation != null && navigation != element) {
			collectDirectRequiresApi(navigation, versions, visited);
		}
	}

	static boolean isProjectSource(PsiModifierListOwner owner) {
		if (owner == null) {
			return false;
		}

		if (isProjectSource(owner.getContainingFile(), owner.getProject())) {
			return true;
		}

		final PsiElement navigation = owner.getNavigationElement();
		return navigation != null && isProjectSource(navigation.getContainingFile(), owner.getProject());
	}

	private static boolean isProjectSource(PsiFile file, Project project) {
		if (file == null || file.getVirtualFile() == null) {
			return false;
		}

		return ProjectFileIndex.getInstance(project).isInSource(file.getVirtualFile());
	}

	private static void addJavaAnnotationVersions(PsiModifierListOwner owner, Set<Integer> versions) {
		final var annotation = owner.getAnnotation(HollowEngineConstants.REQUIRES_API_FQN);

		if (annotation == null) {
			return;
		}

		versions.addAll(RequiresApiAnnotationReader.readJavaAnnotationVersions(annotation));
	}

	static Set<Integer> resolveSymbolVersions(PsiModifierListOwner owner) {
		if (owner == null) {
			return Set.of();
		}

		final Set<Integer> sourceVersions = resolveDirectRequiresApi(owner);

		if (!sourceVersions.isEmpty() || isProjectSource(owner)) {
			return sourceVersions;
		}

		if (MultiversionMetadataResolver.isSyntheticAlias(owner, owner.getProject())) {
			return Set.of();
		}

		return MultiversionMetadataResolver.resolveVersions(owner, owner.getProject());
	}

	static Set<Integer> resolveSymbolVersions(PsiElement element) {
		if (element == null) {
			return Set.of();
		}

		if (isImportContext(element)) {
			return Set.of();
		}

		if (element instanceof PsiModifierListOwner owner) {
			return resolveSymbolVersions(owner);
		}

		if (element instanceof KtDeclaration declaration) {
			final Set<Integer> versions = new LinkedHashSet<>();
			addKotlinAnnotationVersions(declaration, versions);

			if (!versions.isEmpty()) {
				return versions;
			}
		}

		final PsiModifierListOwner ownerParent = PsiTreeUtil.getParentOfType(element, PsiModifierListOwner.class, false);

		if (ownerParent != null && ownerParent != element) {
			final Set<Integer> versions = resolveSymbolVersions(ownerParent);

			if (!versions.isEmpty()) {
				return versions;
			}
		}

		final KtDeclaration declarationParent = PsiTreeUtil.getParentOfType(element, KtDeclaration.class, false);

		if (declarationParent != null && declarationParent != element) {
			final Set<Integer> versions = resolveSymbolVersions(declarationParent);

			if (!versions.isEmpty()) {
				return versions;
			}
		}

		final PsiElement navigation = element.getNavigationElement();

		if (navigation != null && navigation != element) {
			return resolveSymbolVersions(navigation);
		}

		return Set.of();
	}

	static boolean isImportContext(PsiElement element) {
		return PsiTreeUtil.getParentOfType(element, PsiImportStatementBase.class, false) != null
				|| PsiTreeUtil.getParentOfType(element, KtImportDirective.class, false) != null;
	}

	static boolean hasSubscribeEvent(PsiModifierListOwner owner) {
		if (owner == null) {
			return false;
		}

		if (owner.getAnnotation(HollowEngineConstants.SUBSCRIBE_EVENT_FQN) != null) {
			return true;
		}

		final PsiElement navigation = owner.getNavigationElement();

		if (navigation instanceof PsiModifierListOwner navigationOwner && navigationOwner.getAnnotation(HollowEngineConstants.SUBSCRIBE_EVENT_FQN) != null) {
			return true;
		}

		if (navigation instanceof KtDeclaration declaration) {
			return declaration.getAnnotationEntries().stream()
					.anyMatch(entry -> entry.getShortName() != null && "SubscribeEvent".equals(entry.getShortName().asString()));
		}

		return false;
	}

	private static void addKotlinAnnotationVersions(KtDeclaration declaration, Set<Integer> versions) {
		if (declaration instanceof KtNamedFunction function) {
			versions.addAll(RequiresApiAnnotationReader.readKotlinAnnotationVersions(function.getAnnotationEntries()));
		} else if (declaration instanceof KtClassOrObject klass) {
			versions.addAll(RequiresApiAnnotationReader.readKotlinAnnotationVersions(klass.getAnnotationEntries()));
		}
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
