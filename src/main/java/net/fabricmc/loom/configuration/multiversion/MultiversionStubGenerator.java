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
import java.util.List;
import java.util.Map;

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
		writeConstantsClass(outputDir, constantsClassName);
		final MultiversionApiMetadata.Builder metadata = MultiversionApiMetadata.builder(collector.getAllVersions());

		for (MultiversionClassInfo classInfo : collector.getClasses().values()) {
			writeClass(outputDir, collector.getClasses(), classInfo);
			metadata.addClass(classInfo.getName(), classInfo.getVersions());

			for (MultiversionClassInfo.MultiversionFieldInfo field : classInfo.getFields().values()) {
				metadata.addField(classInfo.getName(), field.getName(), field.getDescriptor(), field.getVersions());
			}

			for (MultiversionClassInfo.MultiversionMethodInfo method : classInfo.getMethods().values()) {
				metadata.addMethod(classInfo.getName(), method.getName(), method.getDescriptor(), method.getVersions());
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

	private void writeConstantsClass(Path outputDir, String constantsClassName) throws IOException {
		final String internalName = constantsClassName.replace('.', '/');
		final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, internalName, null, "java/lang/Object", null);

		writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "MINECRAFT_VERSION_NAME", "Ljava/lang/String;", null, null).visitEnd();
		writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "MINECRAFT_VERSION", "I", null, null).visitEnd();
		writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "MC_MAJOR", "I", null, null).visitEnd();
		writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "MC_MINOR", "I", null, null).visitEnd();
		writer.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "MC_PATCH", "I", null, null).visitEnd();

		MethodVisitor visitor = writer.visitMethod(Opcodes.ACC_PRIVATE, "<init>", "()V", null, null);
		visitor.visitCode();
		visitor.visitVarInsn(Opcodes.ALOAD, 0);
		visitor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		visitor.visitInsn(Opcodes.RETURN);
		visitor.visitMaxs(0, 0);
		visitor.visitEnd();

		writeConstantsMethod(writer, internalName, "is", Opcodes.IF_ICMPNE);
		writeConstantsMethod(writer, internalName, "isAtLeast", Opcodes.IF_ICMPLT);
		writeConstantsMethod(writer, internalName, "isAtMost", Opcodes.IF_ICMPGT);

		writer.visitEnd();

		final Path output = outputDir.resolve(internalName + ".class");
		Files.createDirectories(output.getParent());
		Files.write(output, writer.toByteArray());
	}

	private static void writeConstantsMethod(ClassWriter writer, String owner, String name, int falseJumpOpcode) {
		final MethodVisitor visitor = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, name, "(I)Z", null, null);
		visitor.visitCode();
		visitor.visitFieldInsn(Opcodes.GETSTATIC, owner, "MINECRAFT_VERSION", "I");
		visitor.visitVarInsn(Opcodes.ILOAD, 0);
		final Label falseLabel = new Label();
		final Label endLabel = new Label();
		visitor.visitJumpInsn(falseJumpOpcode, falseLabel);
		visitor.visitInsn(Opcodes.ICONST_1);
		visitor.visitJumpInsn(Opcodes.GOTO, endLabel);
		visitor.visitLabel(falseLabel);
		visitor.visitInsn(Opcodes.ICONST_0);
		visitor.visitLabel(endLabel);
		visitor.visitInsn(Opcodes.IRETURN);
		visitor.visitMaxs(0, 0);
		visitor.visitEnd();
	}

	private void writeClass(Path outputDir, Map<String, MultiversionClassInfo> classes, MultiversionClassInfo classInfo) throws IOException {
		final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
		writer.visit(Opcodes.V17, classInfo.getAccess(), classInfo.getName(), null, classInfo.getSuperName() == null ? "java/lang/Object" : classInfo.getSuperName(), classInfo.getInterfaces().toArray(String[]::new));
		writeRequiresApiAnnotation(writer.visitAnnotation("L" + REQUIRES_API_INTERNAL_NAME + ";", true), classInfo.getVersions());

		for (MultiversionClassInfo.MultiversionFieldInfo field : classInfo.getFields().values()) {
			final var visitor = writer.visitField(field.getAccess(), field.getName(), field.getDescriptor(), null, field.getValue());
			writeRequiresApiAnnotation(visitor.visitAnnotation("L" + REQUIRES_API_INTERNAL_NAME + ";", true), field.getVersions());
			visitor.visitEnd();
		}

		for (MultiversionClassInfo.MultiversionMethodInfo method : classInfo.getMethods().values()) {
			final MethodVisitor visitor = writer.visitMethod(method.getAccess(), method.getName(), method.getDescriptor(), null, method.getExceptions().toArray(String[]::new));
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
		writeConstantsSource(outputDir, constantsClassName);

		for (MultiversionClassInfo classInfo : collector.getClasses().values()) {
			final Path output = outputDir.resolve(classInfo.getName() + ".java");
			Files.createDirectories(output.getParent());
			Files.writeString(output, toJavaSource(classInfo), StandardCharsets.UTF_8);
		}
	}

	private void writeConstantsSource(Path outputDir, String constantsClassName) throws IOException {
		final Path output = outputDir.resolve(constantsClassName.replace('.', '/') + ".java");
		Files.createDirectories(output.getParent());
		Files.writeString(output, toConstantsSource(constantsClassName), StandardCharsets.UTF_8);
	}

	private String toConstantsSource(String constantsClassName) {
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
		writer.append("\tpublic static int MC_MAJOR;\n");
		writer.append("\tpublic static int MC_MINOR;\n");
		writer.append("\tpublic static int MC_PATCH;\n\n");
		writer.append("\tprivate ").append(simpleName).append("() {\n\t}\n\n");
		writer.append("\tpublic static boolean is(int version) {\n\t\treturn MINECRAFT_VERSION == version;\n\t}\n\n");
		writer.append("\tpublic static boolean isAtLeast(int version) {\n\t\treturn MINECRAFT_VERSION >= version;\n\t}\n\n");
		writer.append("\tpublic static boolean isAtMost(int version) {\n\t\treturn MINECRAFT_VERSION <= version;\n\t}\n");
		writer.append("}\n");
		return writer.toString();
	}

	private String toJavaSource(MultiversionClassInfo classInfo) {
		final StringWriter writer = new StringWriter();
		final int packageSeparator = classInfo.getName().lastIndexOf('/');
		final String simpleName = packageSeparator >= 0 ? classInfo.getName().substring(packageSeparator + 1) : classInfo.getName();

		if (packageSeparator >= 0) {
			writer.append("package ").append(classInfo.getName().substring(0, packageSeparator).replace('/', '.')).append(";\n\n");
		}

		writer.append("import multiversion.api.RequiresApi;\n\n");
		writer.append("@RequiresApi({");
		appendVersions(writer, classInfo.getVersions());
		writer.append("})\n");
		writer.append("public class ").append(simpleName).append(" {\n");

		for (MultiversionClassInfo.MultiversionFieldInfo field : classInfo.getFields().values()) {
			writer.append("\t@RequiresApi({");
			appendVersions(writer, field.getVersions());
			writer.append("})\n");
			writer.append("\tpublic ").append(toJavaType(Type.getType(field.getDescriptor()))).append(" ").append(field.getName()).append(";\n");
		}

		for (MultiversionClassInfo.MultiversionMethodInfo method : classInfo.getMethods().values()) {
			writer.append("\n\t@RequiresApi({");
			appendVersions(writer, method.getVersions());
			writer.append("})\n");

			if ("<init>".equals(method.getName())) {
				writer.append("\tpublic ").append(simpleName).append("(");
			} else {
				writer.append("\tpublic ").append(toJavaType(Type.getReturnType(method.getDescriptor()))).append(" ").append(method.getName()).append("(");
			}

			final List<String> parameters = new ArrayList<>();
			int index = 0;

			for (Type argumentType : Type.getArgumentTypes(method.getDescriptor())) {
				parameters.add(toJavaType(argumentType) + " arg" + index++);
			}

			writer.append(String.join(", ", parameters)).append(") {\n\t\tthrow new UnsupportedOperationException(\"Multiversion stub\");\n\t}\n");
		}

		writer.append("}\n");
		return writer.toString();
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
		case Type.OBJECT -> type.getClassName();
		default -> "java.lang.Object";
		};
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

	public record GeneratedArtifacts(MultiversionApiMetadata metadata, Path classesJar, Path sourcesJar) { }
}
