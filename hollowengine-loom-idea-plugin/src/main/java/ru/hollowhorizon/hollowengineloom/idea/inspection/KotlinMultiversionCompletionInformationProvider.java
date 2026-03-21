package ru.hollowhorizon.hollowengineloom.idea.inspection;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.GlobalSearchScope;

import org.jetbrains.annotations.Nullable;
import org.jetbrains.kotlin.descriptors.CallableDescriptor;
import org.jetbrains.kotlin.descriptors.ClassDescriptor;
import org.jetbrains.kotlin.descriptors.ClassifierDescriptor;
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor;
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor;
import org.jetbrains.kotlin.descriptors.PropertyDescriptor;
import org.jetbrains.kotlin.idea.completion.CompletionInformationProvider;
import org.jetbrains.kotlin.name.FqName;
import org.jetbrains.kotlin.references.fe10.util.DescriptorToSourceUtilsIde;
import org.jetbrains.kotlin.resolve.DescriptorUtils;

public final class KotlinMultiversionCompletionInformationProvider implements CompletionInformationProvider {
	static final AtomicBoolean TEST_CALLED = new AtomicBoolean(false);
	static final AtomicReference<String> TEST_LAST_RESULT = new AtomicReference<>(null);

	@Override
	public @Nullable String getContainerAndReceiverInformation(DeclarationDescriptor descriptor) {
		TEST_CALLED.set(true);
		final PsiElement declaration = resolveDeclaration(descriptor);

		if (declaration == null) {
			TEST_LAST_RESULT.set(null);
			return null;
		}

		final Set<Integer> requiredVersions = RequiresApiInspectionSupport.resolveSymbolVersions(declaration);

		if (requiredVersions.isEmpty()) {
			TEST_LAST_RESULT.set(null);
			return null;
		}

		final Set<Integer> projectVersions = RequiresApiInspectionSupport.resolveProjectVersions(declaration);

		if (RequiresApiInspectionSupport.isAvailableEverywhere(requiredVersions, projectVersions)) {
			TEST_LAST_RESULT.set(null);
			return null;
		}

		final String result = "in " + RequiresApiInspectionSupport.buildCompletionHint(requiredVersions, projectVersions);
		TEST_LAST_RESULT.set(result);
		return result;
	}

	private static @Nullable PsiElement resolveDeclaration(DeclarationDescriptor descriptor) {
		for (Project project : ProjectManager.getInstance().getOpenProjects()) {
			final PsiElement declaration = DescriptorToSourceUtilsIde.INSTANCE.getAnyDeclaration(project, descriptor);

			if (declaration != null) {
				return declaration;
			}

			final PsiElement javaDeclaration = resolveJavaDeclaration(project, descriptor);

			if (javaDeclaration != null) {
				return javaDeclaration;
			}
		}

		return null;
	}

	private static @Nullable PsiElement resolveJavaDeclaration(Project project, DeclarationDescriptor descriptor) {
		if (descriptor instanceof ClassDescriptor || descriptor instanceof ClassifierDescriptor) {
			final String fqName = safeFqName(descriptor);
			return fqName != null ? JavaPsiFacade.getInstance(project).findClass(fqName, GlobalSearchScope.allScope(project)) : null;
		}

		if (!(descriptor instanceof CallableDescriptor callableDescriptor)) {
			return null;
		}

		final DeclarationDescriptor container = callableDescriptor.getContainingDeclaration();
		final String ownerFqName = safeFqName(container);

		if (ownerFqName == null) {
			return null;
		}

		final var psiClass = JavaPsiFacade.getInstance(project).findClass(ownerFqName, GlobalSearchScope.allScope(project));

		if (psiClass == null) {
			return null;
		}

		if (callableDescriptor instanceof ConstructorDescriptor) {
			for (PsiMethod constructor : psiClass.getConstructors()) {
				if (constructor.getParameterList().getParametersCount() == callableDescriptor.getValueParameters().size()) {
					return constructor;
				}
			}

			return psiClass.getConstructors().length > 0 ? psiClass.getConstructors()[0] : null;
		}

		final String name = callableDescriptor.getName().asString();

		if (callableDescriptor instanceof PropertyDescriptor) {
			final PsiField field = psiClass.findFieldByName(name, true);

			if (field != null) {
				return field;
			}
		}

		for (PsiMethod method : psiClass.findMethodsByName(name, true)) {
			if (method.getParameterList().getParametersCount() == callableDescriptor.getValueParameters().size()) {
				return method;
			}
		}

		final PsiMethod[] methods = psiClass.findMethodsByName(name, true);
		return methods.length > 0 ? methods[0] : null;
	}

	private static @Nullable String safeFqName(@Nullable DeclarationDescriptor descriptor) {
		if (descriptor == null) {
			return null;
		}

		final FqName fqName = DescriptorUtils.getFqNameSafe(descriptor);
		return fqName.isRoot() ? null : fqName.asString();
	}
}
