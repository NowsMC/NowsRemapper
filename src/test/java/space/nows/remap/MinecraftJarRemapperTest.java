/*
 * Copyright 2026 TamKungZ_ (Nows MC — https://nows.space)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package space.nows.remap;

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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MinecraftJarRemapperTest implements Opcodes {
    @Test
    void keepsExistingOutputWhenRemapCannotStart(@TempDir Path temp) throws Exception {
        Path input = temp.resolve("input.jar");
        Path missingMappings = temp.resolve("missing_mappings.txt");
        Path output = temp.resolve("output.jar");
        byte[] existing = "still-valid".getBytes(StandardCharsets.UTF_8);

        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(input))) {
            writeClass(jar, "runtime/Example", minimalClassBytes("runtime/Example", "java/lang/Object", null));
        }
        Files.write(output, existing);

        assertThrows(Exception.class, () -> MinecraftJarRemapper.remapOfficial(input, missingMappings, output));
        assertArrayEquals(existing, Files.readAllBytes(output));
    }

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
        ClassWriter writer = minimalClassBytesWriter("runtime/superpkg/SuperClass", "java/lang/Object", null);

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
        ClassWriter writer = minimalClassBytesWriter("runtime/subpkg/SubClass", "runtime/superpkg/SuperClass", null);

        MethodVisitor method = writer.visitMethod(ACC_PUBLIC | ACC_STATIC, "b", "()Ljava/lang/String;", null, null);
        method.visitCode();
        method.visitMethodInsn(INVOKESTATIC, "runtime/subpkg/SubClass", "a", "()Ljava/lang/String;", false);
        method.visitInsn(ARETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();

        writer.visitEnd();
        return writer.toByteArray();
    }

    private static byte[] minimalClassBytes(String name, String superName, String[] interfaces) {
        ClassWriter writer = minimalClassBytesWriter(name, superName, interfaces);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static ClassWriter minimalClassBytesWriter(String name, String superName, String[] interfaces) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(V1_8, ACC_PUBLIC | ACC_SUPER, name, null, superName, interfaces);

        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, superName, "<init>", "()V", false);
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        return writer;
    }
}
