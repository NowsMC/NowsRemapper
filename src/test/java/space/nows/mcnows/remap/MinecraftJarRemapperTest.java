package space.nows.mcnows.remap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MinecraftJarRemapperTest implements Opcodes {
    @Test
    void remapsInheritedStaticCallSitesWhenOwnerBecomesNamed(@TempDir Path temp) throws Exception {
        Path input = temp.resolve("input.jar");
        Path mappings = temp.resolve("client_mappings.txt");
        Path output = temp.resolve("output.jar");

        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(input))) {
            writeClass(jar, "runtime/superpkg/SuperClass", superClassBytes());
            writeClass(jar, "runtime/subpkg/SubClass", subClassBytes());
        }
        Files.write(mappings, (""
                + "named.superpkg.SuperClass -> runtime.superpkg.SuperClass:\n"
                + "    1:1:java.lang.String namespacedString() -> a\n"
                + "named.subpkg.SubClass -> runtime.subpkg.SubClass:\n"
                + "    1:1:java.lang.String call() -> b\n").getBytes(StandardCharsets.UTF_8));

        MinecraftJarRemapper.remapOfficial(input, mappings, output);

        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{output.toUri().toURL()}, null)) {
            Class<?> subClass = Class.forName("named.subpkg.SubClass", true, loader);

            assertEquals("ok", subClass.getMethod("call").invoke(null));
        }
    }

    private static void writeClass(JarOutputStream jar, String name, byte[] bytes) throws Exception {
        jar.putNextEntry(new JarEntry(name + ".class"));
        jar.write(bytes);
        jar.closeEntry();
    }

    private static byte[] superClassBytes() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V1_8, ACC_PUBLIC | ACC_SUPER, "runtime/superpkg/SuperClass", null, "java/lang/Object", null);

        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();

        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "a", "()Ljava/lang/String;", null, null);
        method.visitCode();
        method.visitLdcInsn("ok");
        method.visitInsn(ARETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] subClassBytes() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V1_8, ACC_PUBLIC | ACC_SUPER, "runtime/subpkg/SubClass", null, "runtime/superpkg/SuperClass", null);

        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, "runtime/superpkg/SuperClass", "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();

        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "b", "()Ljava/lang/String;", null, null);
        method.visitCode();
        method.visitMethodInsn(INVOKESTATIC, "runtime/subpkg/SubClass", "a", "()Ljava/lang/String;", false);
        method.visitInsn(ARETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }
}
