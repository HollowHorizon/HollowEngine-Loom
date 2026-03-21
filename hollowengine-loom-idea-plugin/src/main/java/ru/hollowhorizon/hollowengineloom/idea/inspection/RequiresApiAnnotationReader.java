package ru.hollowhorizon.hollowengineloom.idea.inspection;

import java.util.LinkedHashSet;
import java.util.Set;

import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiAnnotationMemberValue;
import com.intellij.psi.PsiArrayInitializerMemberValue;
import com.intellij.psi.PsiLiteralExpression;

import org.jetbrains.kotlin.psi.KtAnnotationEntry;
import org.jetbrains.kotlin.psi.KtCallExpression;
import org.jetbrains.kotlin.psi.KtCollectionLiteralExpression;
import org.jetbrains.kotlin.psi.KtExpression;
import org.jetbrains.kotlin.psi.KtStringTemplateExpression;

final class RequiresApiAnnotationReader {
	private RequiresApiAnnotationReader() {
	}

	static Set<Integer> readJavaAnnotationVersions(PsiAnnotation annotation) {
		final PsiAnnotationMemberValue value = annotation.findDeclaredAttributeValue("value");
		final Set<Integer> versions = new LinkedHashSet<>();

		if (value instanceof PsiArrayInitializerMemberValue arrayValue) {
			for (PsiAnnotationMemberValue initializer : arrayValue.getInitializers()) {
				addPsiVersion(initializer, versions);
			}
		} else if (value != null) {
			addPsiVersion(value, versions);
		}

		return versions;
	}

	static Set<Integer> readKotlinAnnotationVersions(Iterable<KtAnnotationEntry> entries) {
		final Set<Integer> versions = new LinkedHashSet<>();

		for (KtAnnotationEntry entry : entries) {
			final String shortName = entry.getShortName() == null ? null : entry.getShortName().asString();

			if (!"RequiresApi".equals(shortName)) {
				continue;
			}

			for (org.jetbrains.kotlin.psi.ValueArgument argument : entry.getValueArguments()) {
				addKotlinVersions(argument.getArgumentExpression(), versions);
			}
		}

		return versions;
	}

	private static void addKotlinVersions(KtExpression expression, Set<Integer> versions) {
		if (expression == null) {
			return;
		}

		if (expression instanceof KtStringTemplateExpression stringTemplateExpression) {
			final String text = stringTemplateExpression.getText().replace("\"", "").replace("'", "");

			if (text.indexOf('.') > 0) {
				versions.add(RequiresApiInspectionSupport.packVersion(text));
			}

			return;
		}

		if (expression instanceof KtCollectionLiteralExpression collectionLiteralExpression) {
			collectionLiteralExpression.getInnerExpressions().forEach(innerExpression -> addKotlinVersions(innerExpression, versions));
			return;
		}

		if (expression instanceof KtCallExpression callExpression) {
			callExpression.getValueArguments().forEach(argument -> addKotlinVersions(argument.getArgumentExpression(), versions));
			return;
		}

		final String text = expression.getText().replace("\"", "").replace("'", "");

		if (text.indexOf('.') > 0) {
			versions.add(RequiresApiInspectionSupport.packVersion(text));
		}
	}

	private static void addPsiVersion(PsiAnnotationMemberValue annotationValue, Set<Integer> versions) {
		if (annotationValue instanceof PsiLiteralExpression literalExpression && literalExpression.getValue() instanceof String stringValue) {
			versions.add(RequiresApiInspectionSupport.packVersion(stringValue));
		}
	}
}
