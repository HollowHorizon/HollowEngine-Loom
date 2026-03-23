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

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import net.fabricmc.loom.LoomGradlePlugin;
import net.fabricmc.loom.util.ZipUtils;

public final class MultiversionStubGenerator {
	public static final String REQUIRES_API_INTERNAL_NAME = "multiversion/api/RequiresApi";

	public GeneratedArtifacts generate(MultiversionCollector collector, Path classesJar, Path sourcesJar) throws IOException {
		return generate(collector, classesJar, sourcesJar, "multiversion.Constants");
	}

	public MultiversionApiMetadata generateClassesOnly(MultiversionCollector collector, Path classesJar, String constantsClassName) throws IOException {
		final Path classesDir = Files.createTempDirectory("loom-multiversion-classes");

		try {
			final MultiversionApiMetadata metadata = writeClasses(collector, classesDir, constantsClassName);
			ZipUtils.pack(classesDir, classesJar);
			ZipUtils.add(classesJar, MultiversionApiMetadata.PATH, LoomGradlePlugin.GSON.toJson(metadata));
			return metadata;
		} finally {
			deleteDirectory(classesDir);
		}
	}

	public GeneratedArtifacts generate(MultiversionCollector collector, Path classesJar, Path sourcesJar, String constantsClassName) throws IOException {
		final Path classesDir = Files.createTempDirectory("loom-multiversion-classes");
		final Path sourcesDir = Files.createTempDirectory("loom-multiversion-sources");

		try {
			final MultiversionApiMetadata metadata = writeClasses(collector, classesDir, constantsClassName);
			writeSources(collector, sourcesDir, constantsClassName);
			ZipUtils.pack(classesDir, classesJar);
			ZipUtils.pack(sourcesDir, sourcesJar);
			ZipUtils.add(classesJar, MultiversionApiMetadata.PATH, LoomGradlePlugin.GSON.toJson(metadata));
			return new GeneratedArtifacts(metadata, classesJar, sourcesJar);
		} finally {
			deleteDirectory(classesDir);
			deleteDirectory(sourcesDir);
		}
	}

	private MultiversionApiMetadata writeClasses(MultiversionCollector collector, Path outputDir, String constantsClassName) throws IOException {
		writeRequiresApi(outputDir);
		writeConstantsClass(outputDir, constantsClassName, collector.getAllVersions());
		final MultiversionApiMetadata.Builder metadata = MultiversionApiMetadata.builder(collector.getAllVersions());
		final List<GeneratedClassMetadata> generatedClasses = collector.getClasses().values().parallelStream()
				.map(classInfo -> writeClassWithMetadata(outputDir, collector.getClasses(), classInfo))
				.sorted(Comparator.comparing(generated -> generated.classInfo().getName()))
				.toList();

		for (GeneratedClassMetadata generated : generatedClasses) {
			final MultiversionClassInfo classInfo = generated.classInfo();
			final MultiversionSyntheticMemberNames memberNames = MultiversionSyntheticMemberNames.of(classInfo);
			metadata.addClass(classInfo.getName(), classInfo.getVersions());

			for (MultiversionClassInfo.MultiversionFieldInfo field : classInfo.getFields().values()) {
				metadata.addField(classInfo.getName(), field.getName(), field.getDescriptor(), field.getVersions());
				final String syntheticName = memberNames.fieldName(field);

				if (!syntheticName.equals(field.getName())) {
					metadata.addSyntheticField(classInfo.getName(), field.getName(), field.getDescriptor(), syntheticName);
				}
			}

			for (MultiversionClassInfo.MultiversionMethodInfo method : classInfo.getMethods().values()) {
				metadata.addMethod(classInfo.getName(), method.getName(), method.getDescriptor(), method.getVersions());
				final String syntheticName = memberNames.methodName(method);

				if (!syntheticName.equals(method.getName())) {
					metadata.addSyntheticMethod(classInfo.getName(), method.getName(), method.getDescriptor(), syntheticName);
				}
			}
		}

		return metadata.build();
	}

	private void writeRequiresApi(Path outputDir) throws IOException {
		final ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE | Opcodes.ACC_ANNOTATION, REQUIRES_API_INTERNAL_NAME, null, "java/lang/Object", new String[] { "java/lang/annotation/Annotation" });

		final AnnotationVisitor retention = writer.visitAnnotation("Ljava/lang/annotation/Retention;", true);
		retention.visitEnum("value", "Ljava/lang/annotation/RetentionPolicy;", "CLASS");
		retention.visitEnd();

		final AnnotationVisitor target = writer.visitAnnotation("Ljava/lang/annotation/Target;", true);
		final AnnotationVisitor targetArray = target.visitArray("value");
		targetArray.visitEnum(null, "Ljava/lang/annotation/ElementType;", "TYPE");
		targetArray.visitEnum(null, "Ljava/lang/annotation/ElementType;", "METHOD");
		targetArray.visitEnum(null, "Ljava/lang/annotation/ElementType;", "FIELD");
		targetArray.visitEnum(null, "Ljava/lang/annotation/ElementType;", "CONSTRUCTOR");
		targetArray.visitEnd();
		target.visitEnd();

		writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "value", "()[Ljava/lang/String;", null, null).visitEnd();
		writer.visitEnd();

		final Path output = outputDir.resolve(REQUIRES_API_INTERNAL_NAME + ".class");
		Files.createDirectories(output.getParent());
		Files.write(output, writer.toByteArray());
	}

	private void writeConstantsClass(Path outputDir, String constantsClassName, Iterable<String> versions) throws IOException {
		final String internalName = constantsClassName.replace('.', '/');
		final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, internalName, null, "java/lang/Object", null);

		writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "MINECRAFT_VERSION_NAME", "Ljava/lang/String;", null, null).visitEnd();
		writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "MINECRAFT_VERSION", "I", null, null).visitEnd();
		writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "MINECRAFT", "I", null, null).visitEnd();
		writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "MC_MAJOR", "I", null, null).visitEnd();
		writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "MC_MINOR", "I", null, null).visitEnd();
		writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "MC_PATCH", "I", null, null).visitEnd();

		for (String version : versions) {
			writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, versionFieldName(version), "I", null, null).visitEnd();
		}

		MethodVisitor visitor = writer.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null);
		visitor.visitCode();
		visitor.visitVarInsn(Opcodes.ALOAD, 0);
		visitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		visitor.visitInsn(Opcodes.RETURN);
		visitor.visitMaxs(0, 0);
		visitor.visitEnd();

		writer.visitEnd();

		final Path output = outputDir.resolve(internalName + ".class");
		Files.createDirectories(output.getParent());
		Files.write(output, writer.toByteArray());
	}

	private void writeClass(Path outputDir, Map<String, MultiversionClassInfo> classes, MultiversionClassInfo classInfo) throws IOException {
		final MultiversionSyntheticMemberNames memberNames = MultiversionSyntheticMemberNames.of(classInfo);
		final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
		final List<String> interfaces = compatibleInterfaces(classInfo);
		writer.visit(Opcodes.V17, classInfo.getAccess(), classInfo.getName(), classInfo.getSignature(), classInfo.getSuperName() == null ? "java/lang/Object" : classInfo.getSuperName(), interfaces.toArray(String[]::new));
		writeNestAttributes(writer, classes, classInfo.getName());
		writeInnerClassEntries(writer, classes, classInfo);
		writeRequiresApiAnnotation(writer.visitAnnotation("L" + REQUIRES_API_INTERNAL_NAME + ";", true), classInfo.getVersions());

		for (MultiversionClassInfo.MultiversionFieldInfo field : classInfo.getFields().values()) {
			final String fieldName = memberNames.fieldName(field);
			final var visitor = writer.visitField(field.getAccess(), fieldName, field.getDescriptor(), field.getSignature(), field.getValue());
			writeRequiresApiAnnotation(visitor.visitAnnotation("L" + REQUIRES_API_INTERNAL_NAME + ";", true), field.getVersions());
			visitor.visitEnd();
		}

		for (MultiversionClassInfo.MultiversionMethodInfo method : classInfo.getMethods().values()) {
			final String methodName = memberNames.methodName(method);
			final MethodVisitor visitor = writer.visitMethod(method.getAccess(), methodName, method.getDescriptor(), method.getSignature(), method.getExceptions().toArray(String[]::new));
			writeRequiresApiAnnotation(visitor.visitAnnotation("L" + REQUIRES_API_INTERNAL_NAME + ";", true), method.getVersions());

			if ((method.getAccess() & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0) {
				writeMethodBody(visitor, classes, classInfo, method);
			}

			visitor.visitEnd();
		}

		writer.visitEnd();
		final Path output = outputDir.resolve(classInfo.getName() + ".class");
		Files.createDirectories(output.getParent());
		Files.write(output, writer.toByteArray());
	}

	private GeneratedClassMetadata writeClassWithMetadata(Path outputDir, Map<String, MultiversionClassInfo> classes, MultiversionClassInfo classInfo) {
		try {
			writeClass(outputDir, classes, classInfo);
			return new GeneratedClassMetadata(classInfo);
		} catch (IOException e) {
			throw new RuntimeException("Failed to write multiversion stub class " + classInfo.getName(), e);
		}
	}

	private void writeMethodBody(MethodVisitor visitor, Map<String, MultiversionClassInfo> classes, MultiversionClassInfo classInfo, MultiversionClassInfo.MultiversionMethodInfo method) {
		visitor.visitCode();

		if ("<init>".equals(method.getName())) {
			final String superOwner = classInfo.getSuperName() == null ? "java/lang/Object" : classInfo.getSuperName();
			final String superDescriptor = pickSuperConstructor(classes.get(superOwner));
			visitor.visitVarInsn(Opcodes.ALOAD, 0);
			pushDefaultArguments(visitor, Type.getArgumentTypes(superDescriptor));
			visitor.visitMethodInsn(Opcodes.INVOKESPECIAL, superOwner, "<init>", superDescriptor, false);
		}

		visitor.visitTypeInsn(Opcodes.NEW, "java/lang/UnsupportedOperationException");
		visitor.visitInsn(Opcodes.DUP);
		visitor.visitLdcInsn("Multiversion stub");
		visitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/UnsupportedOperationException", "<init>", "(Ljava/lang/String;)V", false);
		visitor.visitInsn(Opcodes.ATHROW);
		visitor.visitMaxs(0, 0);
	}

	private static String pickSuperConstructor(MultiversionClassInfo superClass) {
		if (superClass == null) {
			return "()V";
		}

		return superClass.constructors().stream()
				.sorted(Comparator.comparingInt(method -> Type.getArgumentTypes(method.getDescriptor()).length))
				.map(MultiversionClassInfo.MultiversionMethodInfo::getDescriptor)
				.findFirst()
				.orElse("()V");
	}

	private static void pushDefaultArguments(MethodVisitor visitor, Type[] argumentTypes) {
		for (Type argumentType : argumentTypes) {
			switch (argumentType.getSort()) {
			case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> visitor.visitInsn(Opcodes.ICONST_0);
			case Type.FLOAT -> visitor.visitInsn(Opcodes.FCONST_0);
			case Type.LONG -> visitor.visitInsn(Opcodes.LCONST_0);
			case Type.DOUBLE -> visitor.visitInsn(Opcodes.DCONST_0);
			default -> visitor.visitInsn(Opcodes.ACONST_NULL);
			}
		}
	}

	private void writeSources(MultiversionCollector collector, Path outputDir, String constantsClassName) throws IOException {
		writeConstantsSource(outputDir, constantsClassName, collector.getAllVersions());

		collector.getClasses().values().parallelStream()
				.filter(classInfo -> !classInfo.getName().contains("$"))
				.forEach(classInfo -> writeSource(outputDir, collector.getClasses(), classInfo));
	}

	private void writeSource(Path outputDir, Map<String, MultiversionClassInfo> classes, MultiversionClassInfo classInfo) {
		try {
			final Path output = outputDir.resolve(classInfo.getName() + ".java");
			Files.createDirectories(output.getParent());
			Files.writeString(output, toJavaSource(classes, classInfo), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new RuntimeException("Failed to write multiversion stub source " + classInfo.getName(), e);
		}
	}

	private void writeConstantsSource(Path outputDir, String constantsClassName, Iterable<String> versions) throws IOException {
		final Path output = outputDir.resolve(constantsClassName.replace('.', '/') + ".java");
		Files.createDirectories(output.getParent());
		Files.writeString(output, toConstantsSource(constantsClassName, versions), StandardCharsets.UTF_8);
	}

	private String toConstantsSource(String constantsClassName, Iterable<String> versions) {
		final StringWriter writer = new StringWriter();
		final int packageSeparator = constantsClassName.lastIndexOf('.');
		final String packageName = packageSeparator >= 0 ? constantsClassName.substring(0, packageSeparator) : "";
		final String simpleName = packageSeparator >= 0 ? constantsClassName.substring(packageSeparator + 1) : constantsClassName;

		if (!packageName.isEmpty()) {
			writer.append("package ").append(packageName).append(";\n\n");
		}

		writer.append("public final class ").append(simpleName).append(" {\n");
		writer.append("\tpublic static String MINECRAFT_VERSION_NAME;\n");
		writer.append("\tpublic static int MINECRAFT_VERSION;\n");
		writer.append("\tpublic static int MINECRAFT;\n");
		writer.append("\tpublic static int MC_MAJOR;\n");
		writer.append("\tpublic static int MC_MINOR;\n");
		writer.append("\tpublic static int MC_PATCH;\n\n");

		for (String version : versions) {
			writer.append("\tpublic static int ").append(versionFieldName(version)).append(";\n");
		}

		writer.append("\n");
		writer.append("\tprivate ").append(simpleName).append("() {\n\t}\n");
		writer.append("}\n");
		return writer.toString();
	}

	private String toJavaSource(Map<String, MultiversionClassInfo> classes, MultiversionClassInfo classInfo) {
		final StringWriter writer = new StringWriter();
		final int packageSeparator = classInfo.getName().lastIndexOf('/');
		final String simpleName = simpleName(classInfo.getName());

		if (packageSeparator >= 0) {
			writer.append("package ").append(classInfo.getName().substring(0, packageSeparator).replace('/', '.')).append(";\n\n");
		}

		writer.append("import multiversion.api.RequiresApi;\n\n");
		appendJavaClassSource(writer, classes, classInfo, simpleName, 0);
		return writer.toString();
	}

	private void appendJavaClassSource(StringWriter writer, Map<String, MultiversionClassInfo> classes, MultiversionClassInfo classInfo, String simpleName, int depth) {
		final String indent = "\t".repeat(depth);
		final int declarationAccess = depth > 0 ? classInfo.getInnerAccess() : classInfo.getAccess();
		final MultiversionSyntheticMemberNames memberNames = MultiversionSyntheticMemberNames.of(classInfo);
		writer.append(indent).append("@RequiresApi({");
		appendVersions(writer, classInfo.getVersions());
		writer.append("})\n");
		writer.append(indent);
		appendClassModifiers(writer, declarationAccess);
		appendClassKeyword(writer, declarationAccess);
		writer.append(simpleName);
		appendInheritance(writer, classInfo, declarationAccess);
		writer.append(" {\n");

		for (MultiversionClassInfo.MultiversionFieldInfo field : classInfo.getFields().values()) {
			writer.append(indent).append("\t@RequiresApi({");
			appendVersions(writer, field.getVersions());
			writer.append("})\n");
			writer.append(indent).append("\t");
			appendMemberModifiers(writer, field.getAccess(), true);
			writer.append(toJavaType(Type.getType(field.getDescriptor()))).append(" ").append(memberNames.fieldName(field)).append(";\n");
		}

		for (MultiversionClassInfo.MultiversionMethodInfo method : classInfo.getMethods().values()) {
			writer.append("\n").append(indent).append("\t@RequiresApi({");
			appendVersions(writer, method.getVersions());
			writer.append("})\n");

			if ("<init>".equals(method.getName())) {
				writer.append(indent).append("\t");
				appendMemberModifiers(writer, method.getAccess(), false);
				writer.append(simpleName).append("(");
			} else {
				writer.append(indent).append("\t");
				appendMemberModifiers(writer, method.getAccess(), false);
				writer.append(toJavaType(Type.getReturnType(method.getDescriptor()))).append(" ").append(memberNames.methodName(method)).append("(");
			}

			final List<String> parameters = new ArrayList<>();
			int index = 0;

			for (Type argumentType : Type.getArgumentTypes(method.getDescriptor())) {
				parameters.add(toJavaType(argumentType) + " arg" + index++);
			}

			writer.append(String.join(", ", parameters)).append(") {\n")
					.append(indent).append("\t\tthrow new UnsupportedOperationException(\"Multiversion stub\");\n")
					.append(indent).append("\t}\n");
		}

		for (MultiversionClassInfo innerClass : directInnerClasses(classes, classInfo.getName())) {
			writer.append("\n");
			appendJavaClassSource(writer, classes, innerClass, simpleName(innerClass.getName()), depth + 1);
		}

		writer.append(indent).append("}\n");
	}

	private static void appendVersions(StringWriter writer, Iterable<String> versions) {
		boolean first = true;

		for (String version : versions) {
			if (!first) {
				writer.append(", ");
			}

			first = false;
			writer.append("\"").append(version).append("\"");
		}
	}

	private static String toJavaType(Type type) {
		return switch (type.getSort()) {
		case Type.VOID -> "void";
		case Type.BOOLEAN -> "boolean";
		case Type.CHAR -> "char";
		case Type.BYTE -> "byte";
		case Type.SHORT -> "short";
		case Type.INT -> "int";
		case Type.FLOAT -> "float";
		case Type.LONG -> "long";
		case Type.DOUBLE -> "double";
		case Type.ARRAY -> toJavaType(type.getElementType()) + "[]".repeat(type.getDimensions());
		case Type.OBJECT -> type.getClassName().replace('$', '.');
		default -> "java.lang.Object";
		};
	}

	private static String versionFieldName(String version) {
		return "V" + version.replaceAll("[^A-Za-z0-9]", "_");
	}

	private static List<MultiversionClassInfo> directInnerClasses(Map<String, MultiversionClassInfo> classes, String ownerInternalName) {
		final List<MultiversionClassInfo> result = new ArrayList<>();
		final String prefix = ownerInternalName + "$";

		for (MultiversionClassInfo candidate : classes.values()) {
			if (!candidate.getName().startsWith(prefix)) {
				continue;
			}

			final String remainder = candidate.getName().substring(prefix.length());

			if (!remainder.isEmpty() && remainder.indexOf('$') < 0) {
				result.add(candidate);
			}
		}

		result.sort(Comparator.comparing(MultiversionClassInfo::getName));
		return result;
	}

	private static void writeInnerClassEntries(ClassWriter writer, Map<String, MultiversionClassInfo> classes, MultiversionClassInfo classInfo) {
		final String ownerName = classInfo.getName();
		String currentInternalName = ownerName;
		final Set<String> emitted = new LinkedHashSet<>();
		int separator = currentInternalName.lastIndexOf('$');

		while (separator > 0) {
			final String currentName = currentInternalName;
			final String outerName = currentName.substring(0, separator);
			final String innerName = currentName.substring(separator + 1);
			final MultiversionClassInfo currentClass = classes.get(currentName);
			visitInnerClass(writer, emitted, currentName, outerName, innerName, innerClassAccess(currentClass));
			currentInternalName = outerName;
			separator = currentInternalName.lastIndexOf('$');
		}

		visitDescendantInnerClasses(writer, emitted, classes, ownerName);
		visitReferencedInnerClasses(writer, emitted, classes, classInfo);
	}

	private static void writeNestAttributes(ClassWriter writer, Map<String, MultiversionClassInfo> classes, String internalName) {
		final String nestHost = nestHostName(internalName);

		if (!internalName.equals(nestHost)) {
			writer.visitNestHost(nestHost);
			return;
		}

		for (String descendant : descendantInnerClassNames(classes, internalName)) {
			writer.visitNestMember(descendant);
		}
	}

	private static List<String> descendantInnerClassNames(Map<String, MultiversionClassInfo> classes, String ownerInternalName) {
		final List<String> result = new ArrayList<>();
		final String prefix = ownerInternalName + "$";

		for (MultiversionClassInfo candidate : classes.values()) {
			if (candidate.getName().startsWith(prefix)) {
				result.add(candidate.getName());
			}
		}

		result.sort(String::compareTo);
		return result;
	}

	private static String nestHostName(String internalName) {
		final int separator = internalName.indexOf('$');
		return separator < 0 ? internalName : internalName.substring(0, separator);
	}

	private static void visitDescendantInnerClasses(ClassWriter writer, Set<String> emitted, Map<String, MultiversionClassInfo> classes, String ownerName) {
		for (MultiversionClassInfo innerClass : directInnerClasses(classes, ownerName)) {
			visitInnerClass(writer, emitted, innerClass.getName(), ownerName, simpleName(innerClass.getName()), innerClassAccess(innerClass));
			visitDescendantInnerClasses(writer, emitted, classes, innerClass.getName());
		}
	}

	private static void visitInnerClass(ClassWriter writer, Set<String> emitted, String name, String outerName, String innerName, int access) {
		final String key = name + "|" + outerName + "|" + innerName;

		if (!emitted.add(key)) {
			return;
		}

		writer.visitInnerClass(name, outerName, innerName, access);
	}

	private static void visitReferencedInnerClasses(ClassWriter writer, Set<String> emitted, Map<String, MultiversionClassInfo> classes, MultiversionClassInfo classInfo) {
		final Set<String> referenced = new LinkedHashSet<>();
		addReferencedType(referenced, classInfo.getSuperName());
		compatibleInterfaces(classInfo).forEach(interfaceName -> addReferencedType(referenced, interfaceName));

		for (MultiversionClassInfo.MultiversionFieldInfo field : classInfo.getFields().values()) {
			addReferencedTypes(referenced, Type.getType(field.getDescriptor()));
		}

		for (MultiversionClassInfo.MultiversionMethodInfo method : classInfo.getMethods().values()) {
			addReferencedTypes(referenced, Type.getReturnType(method.getDescriptor()));

			for (Type argumentType : Type.getArgumentTypes(method.getDescriptor())) {
				addReferencedTypes(referenced, argumentType);
			}

			method.getExceptions().forEach(exceptionType -> addReferencedType(referenced, exceptionType));
		}

		for (String referencedInternalName : referenced) {
			visitInnerClassChain(writer, emitted, classes, referencedInternalName);
		}
	}

	private static void visitInnerClassChain(ClassWriter writer, Set<String> emitted, Map<String, MultiversionClassInfo> classes, String internalName) {
		String currentInternalName = internalName;
		int separator = currentInternalName.lastIndexOf('$');

		while (separator > 0) {
			final String currentName = currentInternalName;
			final String outerName = currentName.substring(0, separator);
			final String innerName = currentName.substring(separator + 1);
			final MultiversionClassInfo currentClass = classes.get(currentName);
			visitInnerClass(writer, emitted, currentName, outerName, innerName, innerClassAccess(currentClass));
			currentInternalName = outerName;
			separator = currentInternalName.lastIndexOf('$');
		}
	}

	private static void addReferencedTypes(Set<String> referenced, Type type) {
		if (type == null) {
			return;
		}

		if (type.getSort() == Type.ARRAY) {
			addReferencedTypes(referenced, type.getElementType());
			return;
		}

		if (type.getSort() == Type.OBJECT) {
			addReferencedType(referenced, type.getInternalName());
		}
	}

	private static void addReferencedType(Set<String> referenced, String internalName) {
		if (internalName == null || internalName.indexOf('$') < 0) {
			return;
		}

		referenced.add(internalName);
	}

	private static List<String> compatibleInterfaces(MultiversionClassInfo classInfo) {
		final Set<String> classVersions = classInfo.getVersions();

		if (classVersions.isEmpty()) {
			return classInfo.getInterfaces().keySet().stream()
					.filter(MultiversionStubGenerator::isSupportedInterface)
					.toList();
		}

		return classInfo.getInterfaces().entrySet().stream()
				.filter(entry -> entry.getValue().containsAll(classVersions))
				.map(Map.Entry::getKey)
				.filter(MultiversionStubGenerator::isSupportedInterface)
				.toList();
	}

	private static boolean isSupportedInterface(String internalName) {
		return internalName != null && (internalName.startsWith("java/")
				|| internalName.startsWith("javax/")
				|| internalName.startsWith("kotlin/")
				|| internalName.startsWith("net/minecraft/")
				|| internalName.startsWith("com/mojang/"));
	}

	private static int innerClassAccess(MultiversionClassInfo classInfo) {
		if (classInfo == null) {
			return Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
		}

		return classInfo.getInnerAccess();
	}

	private static String simpleName(String internalName) {
		final int packageSeparator = internalName.lastIndexOf('/');
		final String rawSimpleName = packageSeparator >= 0 ? internalName.substring(packageSeparator + 1) : internalName;
		return rawSimpleName.contains("$") ? rawSimpleName.substring(rawSimpleName.lastIndexOf('$') + 1) : rawSimpleName;
	}

	private static void appendClassModifiers(StringWriter writer, int access) {
		if ((access & Opcodes.ACC_PUBLIC) != 0) {
			writer.append("public ");
		} else if ((access & Opcodes.ACC_PROTECTED) != 0) {
			writer.append("protected ");
		} else if ((access & Opcodes.ACC_PRIVATE) != 0) {
			writer.append("private ");
		}

		if ((access & Opcodes.ACC_STATIC) != 0) {
			writer.append("static ");
		}

		if ((access & Opcodes.ACC_ABSTRACT) != 0 && (access & Opcodes.ACC_INTERFACE) == 0) {
			writer.append("abstract ");
		}

		if ((access & Opcodes.ACC_FINAL) != 0) {
			writer.append("final ");
		}
	}

	private static void appendMemberModifiers(StringWriter writer, int access, boolean field) {
		if ((access & Opcodes.ACC_PUBLIC) != 0) {
			writer.append("public ");
		} else if ((access & Opcodes.ACC_PROTECTED) != 0) {
			writer.append("protected ");
		} else if ((access & Opcodes.ACC_PRIVATE) != 0) {
			writer.append("private ");
		}

		if ((access & Opcodes.ACC_STATIC) != 0) {
			writer.append("static ");
		}

		if ((access & Opcodes.ACC_ABSTRACT) != 0 && !field) {
			writer.append("abstract ");
		}

		if ((access & Opcodes.ACC_FINAL) != 0) {
			writer.append("final ");
		}
	}

	private static void appendClassKeyword(StringWriter writer, int access) {
		if ((access & Opcodes.ACC_ANNOTATION) != 0) {
			writer.append("@interface ");
		} else if ((access & Opcodes.ACC_INTERFACE) != 0) {
			writer.append("interface ");
		} else if ((access & Opcodes.ACC_ENUM) != 0) {
			writer.append("enum ");
		} else {
			writer.append("class ");
		}
	}

	private static void appendInheritance(StringWriter writer, MultiversionClassInfo classInfo, int access) {
		final boolean isInterface = (access & Opcodes.ACC_INTERFACE) != 0;
		final boolean isAnnotation = (access & Opcodes.ACC_ANNOTATION) != 0;
		final boolean isEnum = (access & Opcodes.ACC_ENUM) != 0;
		final List<String> interfaces = compatibleInterfaces(classInfo);

		if (!isInterface && !isAnnotation && !isEnum && classInfo.getSuperName() != null && !"java/lang/Object".equals(classInfo.getSuperName())) {
			writer.append(" extends ").append(toJavaType(Type.getObjectType(classInfo.getSuperName())));
		}

		if (!interfaces.isEmpty()) {
			writer.append(isInterface ? " extends " : " implements ");
			writer.append(interfaces.stream()
					.map(interfaceName -> toJavaType(Type.getObjectType(interfaceName)))
					.collect(java.util.stream.Collectors.joining(", ")));
		}
	}

	private static void writeRequiresApiAnnotation(AnnotationVisitor visitor, Iterable<String> versions) {
		final AnnotationVisitor values = visitor.visitArray("value");

		for (String version : versions) {
			values.visit(null, version);
		}

		values.visitEnd();
		visitor.visitEnd();
	}

	private static void deleteDirectory(Path directory) throws IOException {
		if (Files.notExists(directory)) {
			return;
		}

		try (var stream = Files.walk(directory)) {
			stream.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			});
		}
	}

	private record GeneratedClassMetadata(MultiversionClassInfo classInfo) {
		private GeneratedClassMetadata {
			Objects.requireNonNull(classInfo, "classInfo");
		}
	}

	public record GeneratedArtifacts(MultiversionApiMetadata metadata, Path classesJar, Path sourcesJar) { }
}
