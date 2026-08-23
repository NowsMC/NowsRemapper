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

package space.nows.mcnows.remap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.Type;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

public final class MinecraftJarRemapper {
    private MinecraftJarRemapper() {}

    public static String implementationFingerprint() throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String className : Arrays.asList(
                    MinecraftJarRemapper.class.getName(),
                    MemberKey.class.getName(),
                    NowsMappings.class.getName(),
                    NowsRemapper.class.getName())) {
                String resource = className.replace('.', '/') + ".class";
                try (InputStream input = MinecraftJarRemapper.class.getClassLoader().getResourceAsStream(resource)) {
                    if (input == null) {
                        throw new IOException("Missing remapper class resource: " + resource);
                    }
                    digest.update(resource.getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0);
                    digest.update(readAllBytes(input));
                }
            }
            return toHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    public static boolean containsNamedMinecraft(Path jar) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            return file.getEntry("net/minecraft/client/Minecraft.class") != null
                    && file.getEntry("net/minecraft/client/main/Main.class") != null;
        }
    }

    public static void remapOfficial(Path input, Path proguardMappings, Path output) throws Exception {
        Files.deleteIfExists(output);
        Files.createDirectories(output.getParent());
        NowsMappings mappings = NowsMappings.read(proguardMappings);
        mappings.readInheritance(input);
        NowsRemapper remapper = new NowsRemapper(mappings);
        Path part = output.resolveSibling(output.getFileName() + ".part");
        Files.deleteIfExists(part);
        Set<String> written = new HashSet<>();
        try (JarFile inputJar = new JarFile(input.toFile());
             OutputStream fileOutput = Files.newOutputStream(part);
             JarOutputStream outputJar = new JarOutputStream(fileOutput)) {
            java.util.Enumeration<JarEntry> entries = inputJar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (isSignatureFile(name)) {
                    continue;
                }
                if (name.endsWith(".class")) {
                    byte[] remapped = remapClass(inputJar, entry, remapper);
                    String originalClassName = name.substring(0, name.length() - ".class".length());
                    writeEntry(outputJar, written, remapper.mapType(originalClassName) + ".class", remapped);
                    continue;
                }
                try (InputStream entryInput = inputJar.getInputStream(entry)) {
                    writeEntry(outputJar, written, name, readAllBytes(entryInput));
                }
            }
        } catch (Exception failure) {
            Files.deleteIfExists(part);
            throw failure;
        }
        Files.move(part, output, StandardCopyOption.REPLACE_EXISTING);
    }

    private static byte[] remapClass(JarFile jar, JarEntry entry, Remapper remapper) throws IOException {
        try (InputStream input = jar.getInputStream(entry)) {
            ClassReader reader = new ClassReader(readAllBytes(input));
            ClassWriter writer = new ClassWriter(0);
            reader.accept(new ClassRemapper(writer, remapper), 0);
            return writer.toByteArray();
        }
    }

    private static void writeEntry(JarOutputStream jar, Set<String> written, String name, byte[] bytes) throws IOException {
        if (!written.add(name)) {
            return;
        }
        JarEntry outputEntry = new JarEntry(name);
        outputEntry.setTime(0L);
        jar.putNextEntry(outputEntry);
        jar.write(bytes);
        jar.closeEntry();
    }

    private static boolean isSignatureFile(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.startsWith("META-INF/") && (upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA"));
    }

    private static byte[] readAllBytes(InputStream input) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        char[] hex = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            out[i * 2] = hex[value >>> 4];
            out[i * 2 + 1] = hex[value & 0x0f];
        }
        return new String(out);
    }

    private static final class MemberKey {
        private final String owner;
        private final String name;
        private final String descriptor;

        private MemberKey(String owner, String name, String descriptor) {
            this.owner = owner;
            this.name = name;
            this.descriptor = descriptor;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof MemberKey)) {
                return false;
            }
            MemberKey that = (MemberKey) other;
            return owner.equals(that.owner) && name.equals(that.name) && descriptor.equals(that.descriptor);
        }

        @Override
        public int hashCode() {
            int result = owner.hashCode();
            result = 31 * result + name.hashCode();
            result = 31 * result + descriptor.hashCode();
            return result;
        }
    }

    private static final class NowsMappings {
        private final Map<String, String> classes = new HashMap<>();
        private final Map<String, String> namedToRuntimeClasses = new HashMap<>();
        private final Map<String, String> superClasses = new HashMap<>();
        private final Map<String, List<String>> interfaces = new HashMap<>();
        private final Map<MemberKey, String> fields = new HashMap<>();
        private final Map<MemberKey, String> methods = new HashMap<>();

        static NowsMappings read(Path proguardMappings) throws IOException {
            NowsMappings mappings = new NowsMappings();
            List<String> lines = Files.readAllLines(proguardMappings, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.trim().isEmpty() || line.startsWith("#") || Character.isWhitespace(line.charAt(0))) {
                    continue;
                }
                String trimmed = line.trim();
                if (!trimmed.endsWith(":")) {
                    continue;
                }
                String body = trimmed.substring(0, trimmed.length() - 1);
                int arrow = body.indexOf(" -> ");
                if (arrow < 0) {
                    continue;
                }
                String namedOwner = javaNameToInternal(body.substring(0, arrow).trim());
                String runtimeOwner = javaNameToInternal(body.substring(arrow + 4).trim());
                mappings.classes.put(runtimeOwner, namedOwner);
                mappings.namedToRuntimeClasses.put(namedOwner, runtimeOwner);
            }

            String namedOwner = null;
            String runtimeOwner = null;
            for (String line : lines) {
                if (line.trim().isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (!Character.isWhitespace(line.charAt(0))) {
                    String trimmed = line.trim();
                    if (!trimmed.endsWith(":")) {
                        continue;
                    }
                    String body = trimmed.substring(0, trimmed.length() - 1);
                    int arrow = body.indexOf(" -> ");
                    if (arrow < 0) {
                        continue;
                    }
                    namedOwner = javaNameToInternal(body.substring(0, arrow).trim());
                    runtimeOwner = javaNameToInternal(body.substring(arrow + 4).trim());
                    continue;
                }
                if (namedOwner == null || runtimeOwner == null) {
                    continue;
                }
                mappings.readMember(runtimeOwner, line.trim());
            }
            return mappings;
        }

        private void readInheritance(Path inputJar) throws IOException {
            try (JarFile jar = new JarFile(inputJar.toFile())) {
                java.util.Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                        continue;
                    }
                    try (InputStream input = jar.getInputStream(entry)) {
                        ClassReader reader = new ClassReader(readAllBytes(input));
                        if (reader.getSuperName() != null) {
                            superClasses.put(reader.getClassName(), reader.getSuperName());
                        }
                        String[] interfaceNames = reader.getInterfaces();
                        if (interfaceNames.length > 0) {
                            List<String> names = new ArrayList<>();
                            for (String interfaceName : interfaceNames) {
                                names.add(interfaceName);
                            }
                            interfaces.put(reader.getClassName(), names);
                        }
                    }
                }
            }
        }

        private void readMember(String runtimeOwner, String line) {
            int arrow = line.indexOf(" -> ");
            if (arrow < 0) {
                return;
            }
            String left = stripLineNumbers(line.substring(0, arrow).trim());
            String runtimeName = line.substring(arrow + 4).trim();
            int argsStart = left.indexOf('(');
            if (argsStart >= 0) {
                int argsEnd = left.indexOf(')', argsStart);
                int nameStart = left.lastIndexOf(' ', argsStart);
                if (argsEnd < 0 || nameStart < 0) {
                    return;
                }
                String namedName = left.substring(nameStart + 1, argsStart).trim();
                if (namedName.startsWith("<")) {
                    return;
                }
                String returnType = left.substring(0, nameStart).trim();
                String arguments = left.substring(argsStart + 1, argsEnd).trim();
                String runtimeDescriptor = methodDescriptor(returnType, arguments, false);
                methods.put(new MemberKey(runtimeOwner, runtimeName, runtimeDescriptor), namedName);
                return;
            }

            int nameStart = left.lastIndexOf(' ');
            if (nameStart < 0) {
                return;
            }
            String type = left.substring(0, nameStart).trim();
            String namedName = left.substring(nameStart + 1).trim();
            String runtimeDescriptor = typeDescriptor(type, false);
            fields.put(new MemberKey(runtimeOwner, runtimeName, runtimeDescriptor), namedName);
        }

        private String mapClass(String runtimeInternalName) {
            String mapped = classes.get(runtimeInternalName);
            return mapped == null ? runtimeInternalName : mapped;
        }

        private String mapField(String owner, String name, String descriptor) {
            String mapped = findMemberName(runtimeClass(owner), name, runtimeDescriptor(descriptor), fields, new HashSet<String>());
            return mapped == null ? name : mapped;
        }

        private String mapMethod(String owner, String name, String descriptor) {
            String mapped = findMemberName(runtimeClass(owner), name, runtimeDescriptor(descriptor), methods, new HashSet<String>());
            return mapped == null ? name : mapped;
        }

        private String runtimeClass(String internalName) {
            String runtime = namedToRuntimeClasses.get(internalName);
            return runtime == null ? internalName : runtime;
        }

        private String runtimeDescriptor(String descriptor) {
            if (descriptor == null || descriptor.isEmpty()) {
                return descriptor;
            }
            if (descriptor.charAt(0) == '(') {
                Type method = Type.getMethodType(descriptor);
                Type[] arguments = method.getArgumentTypes();
                Type[] mappedArguments = new Type[arguments.length];
                for (int i = 0; i < arguments.length; i++) {
                    mappedArguments[i] = runtimeType(arguments[i]);
                }
                return Type.getMethodDescriptor(runtimeType(method.getReturnType()), mappedArguments);
            }
            return runtimeType(Type.getType(descriptor)).getDescriptor();
        }

        private Type runtimeType(Type type) {
            if (type.getSort() == Type.ARRAY) {
                Type element = runtimeType(type.getElementType());
                if (element.equals(type.getElementType())) {
                    return type;
                }
                StringBuilder descriptor = new StringBuilder();
                for (int i = 0; i < type.getDimensions(); i++) {
                    descriptor.append('[');
                }
                descriptor.append(element.getDescriptor());
                return Type.getType(descriptor.toString());
            }
            if (type.getSort() != Type.OBJECT) {
                return type;
            }
            String runtimeName = namedToRuntimeClasses.get(type.getInternalName());
            return runtimeName == null ? type : Type.getObjectType(runtimeName);
        }

        private String findMemberName(
                String owner,
                String name,
                String descriptor,
                Map<MemberKey, String> mappings,
                Set<String> visited
        ) {
            if (owner == null || !visited.add(owner)) {
                return null;
            }
            String mapped = mappings.get(new MemberKey(owner, name, descriptor));
            if (mapped != null) {
                return mapped;
            }
            mapped = findMemberName(superClasses.get(owner), name, descriptor, mappings, visited);
            if (mapped != null) {
                return mapped;
            }
            List<String> interfaceNames = interfaces.get(owner);
            if (interfaceNames != null) {
                for (String interfaceName : interfaceNames) {
                    mapped = findMemberName(interfaceName, name, descriptor, mappings, visited);
                    if (mapped != null) {
                        return mapped;
                    }
                }
            }
            return null;
        }

        private static String stripLineNumbers(String value) {
            value = value.replaceFirst("^\\d+:\\d+:", "");
            value = value.replaceFirst(":\\d+:\\d+$", "");
            return value;
        }

        private String methodDescriptor(String returnType, String arguments, boolean named) {
            StringBuilder descriptor = new StringBuilder("(");
            if (!arguments.trim().isEmpty()) {
                for (String argument : splitArguments(arguments)) {
                    descriptor.append(typeDescriptor(argument, named));
                }
            }
            descriptor.append(')').append(typeDescriptor(returnType, named));
            return descriptor.toString();
        }

        private static List<String> splitArguments(String arguments) {
            List<String> result = new ArrayList<>();
            int genericDepth = 0;
            int start = 0;
            for (int i = 0; i < arguments.length(); i++) {
                char c = arguments.charAt(i);
                if (c == '<') {
                    genericDepth++;
                } else if (c == '>') {
                    genericDepth--;
                } else if (c == ',' && genericDepth == 0) {
                    result.add(arguments.substring(start, i).trim());
                    start = i + 1;
                }
            }
            result.add(arguments.substring(start).trim());
            return result;
        }

        private String typeDescriptor(String javaType, boolean named) {
            String type = stripGenerics(javaType.trim()).replace("...", "[]");
            int dimensions = 0;
            while (type.endsWith("[]")) {
                dimensions++;
                type = type.substring(0, type.length() - 2).trim();
            }

            String descriptor;
            if ("void".equals(type)) {
                descriptor = "V";
            } else if ("boolean".equals(type)) {
                descriptor = "Z";
            } else if ("byte".equals(type)) {
                descriptor = "B";
            } else if ("char".equals(type)) {
                descriptor = "C";
            } else if ("short".equals(type)) {
                descriptor = "S";
            } else if ("int".equals(type)) {
                descriptor = "I";
            } else if ("float".equals(type)) {
                descriptor = "F";
            } else if ("long".equals(type)) {
                descriptor = "J";
            } else if ("double".equals(type)) {
                descriptor = "D";
            } else {
                String internalName = javaNameToInternal(type);
                String runtimeName = named ? internalName : namedToRuntimeClasses.get(internalName);
                descriptor = "L" + (runtimeName == null ? internalName : runtimeName) + ";";
            }

            if (dimensions == 0) {
                return descriptor;
            }
            return repeat('[', dimensions) + descriptor;
        }

        private static String stripGenerics(String type) {
            StringBuilder result = new StringBuilder();
            int depth = 0;
            for (int i = 0; i < type.length(); i++) {
                char c = type.charAt(i);
                if (c == '<') {
                    depth++;
                } else if (c == '>') {
                    depth--;
                } else if (depth == 0) {
                    result.append(c);
                }
            }
            String stripped = result.toString().trim();
            if (stripped.startsWith("? extends ")) {
                return stripped.substring("? extends ".length()).trim();
            }
            if (stripped.startsWith("? super ")) {
                return stripped.substring("? super ".length()).trim();
            }
            return stripped;
        }

        private static String javaNameToInternal(String name) {
            return name.replace('.', '/');
        }

        private static String repeat(char value, int count) {
            StringBuilder result = new StringBuilder(count);
            for (int i = 0; i < count; i++) {
                result.append(value);
            }
            return result.toString();
        }
    }

    private static final class NowsRemapper extends Remapper {
        private final NowsMappings mappings;

        private NowsRemapper(NowsMappings mappings) {
            this.mappings = mappings;
        }

        @Override
        public String map(String internalName) {
            return mappings.mapClass(internalName);
        }

        @Override
        public String mapFieldName(String owner, String name, String descriptor) {
            return mappings.mapField(owner, name, descriptor);
        }

        @Override
        public String mapMethodName(String owner, String name, String descriptor) {
            return mappings.mapMethod(owner, name, descriptor);
        }
    }
}
