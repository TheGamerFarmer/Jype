package fr.thegamerfarmer.jype;

import javax.tools.JavaFileObject;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Decodes the class-file metadata needed before javac enters symbols.
 *
 * <p>Reads CLASS-retained package annotations and generic static method signatures
 * without loading user classes or running initializers. Unknown attributes are skipped
 * by their unsigned lengths; truncated or unsupported encodings fail with IOException.
 * This is a targeted reader, not a bytecode verifier or a general signature model.
 */
final class TypeSystemClassFileReader {

    private static final String TYPE_SYSTEM_DESCRIPTOR =
            "Lfr/thegamerfarmer/jype/annotations/TypeSystem;";
    private static final String TYPE_DEF_DESCRIPTOR =
            "Lfr/thegamerfarmer/jype/annotations/TypeDef;";

    /** Ordered managed names and canonical bounds; an empty parent denotes a definition. */
    record TypeSystemData(Set<String> names, String parentPackage, java.util.Map<String, String> bounds) {}
    /** Static overloads with incompatible generic orders are represented by an empty order. */
    record ClassData(List<String> parameters, java.util.Map<String, List<String>> staticMethods,
                     Set<String> fields, String parent, PublicView publicView, List<MemberView> memberViews) {}

    /** Validated version-one origin metadata; null on legacy classes. */
    record PublicView(List<String> managedParameters, List<String> declaredParameters, String typeSystem) {}
    /** Source identity is paired with the unchanged JVM descriptor to distinguish overloads. */
    record MemberView(String name, String descriptor, String sourceType,
                      List<String> declaredParameters, boolean generated) {}
    private record Attributes(String signature, List<AnnotationData> annotations) {}

    private record AnnotationData(String descriptor, java.util.Map<String, Object> values) {}

    private TypeSystemClassFileReader() {}

    /**
     * Reads class/field names and static method type parameter orders.
     * Binary nested names are normalized to canonical source spelling.
     *
     * @throws IOException for malformed or unreadable class data
     */
    static ClassData readClass(JavaFileObject classFile) throws IOException {
        try (DataInputStream input = new DataInputStream(classFile.openInputStream())) {
            if (input.readInt() != 0xCAFEBABE) throw new IOException("Invalid class-file magic");
            input.readUnsignedShort();
            input.readUnsignedShort();
            int count = input.readUnsignedShort();
            int[] classes = new int[count];
            String[] utf8 = readConstantPool(input, count, classes);
            input.readUnsignedShort();
            input.readUnsignedShort();
            int parentIndex = input.readUnsignedShort();
            String parent = parentIndex == 0 ? "" : utf8[classes[parentIndex]].replace('/', '.').replace('$', '.');
            skipInterfaces(input);
            Set<String> fields = new LinkedHashSet<>();
            List<MemberView> memberViews = new ArrayList<>();
            int fieldCount = input.readUnsignedShort();
            for (int i = 0; i < fieldCount; i++) {
                input.readUnsignedShort();
                String name = utf8[input.readUnsignedShort()];
                fields.add(name);
                String descriptor = utf8[input.readUnsignedShort()];
                Attributes attributes = readAttributes(input, utf8);
                collectMemberView(name, descriptor, attributes, memberViews);
            }
            java.util.Map<String, List<String>> methods = new java.util.LinkedHashMap<>();
            int methodCount = input.readUnsignedShort();
            for (int i = 0; i < methodCount; i++) {
                int flags = input.readUnsignedShort();
                String name = utf8[input.readUnsignedShort()];
                String descriptor = utf8[input.readUnsignedShort()];
                Attributes attributes = readAttributes(input, utf8);
                collectMemberView(name, descriptor, attributes, memberViews);
                String signature = attributes.signature();
                if ((flags & 0x0008) == 0) continue;
                List<String> parameters = signatureParameters(signature);
                if (methods.containsKey(name) && !methods.get(name).equals(parameters)) parameters = List.of();
                methods.put(name, parameters);
            }
            Attributes attributes = readAttributes(input, utf8);
            PublicView publicView = null;
            for (AnnotationData annotation : attributes.annotations()) {
                if (annotation.descriptor().equals("Lfr/thegamerfarmer/jype/annotations/JypeClass;")) {
                    publicView = publicView(annotation);
                }
            }
            List<String> parameters = signatureParameters(attributes.signature());
            if (publicView != null && (!parameters.containsAll(publicView.declaredParameters())
                    || !parameters.stream().filter(publicView.managedParameters()::contains).toList()
                    .equals(publicView.managedParameters()))) {
                throw new IOException("JypeClass parameter origins disagree with JVM signature");
            }
            return new ClassData(parameters, methods, fields, parent, publicView, List.copyOf(memberViews));
        }
    }

    private static PublicView publicView(AnnotationData annotation) throws IOException {
        Object version = annotation.values().getOrDefault("version", 1);
        if (!Integer.valueOf(1).equals(version)) throw new IOException("Unsupported JypeClass metadata version: " + version);
        List<String> managed = parameterNames(annotation.values().get("managedParameters"));
        List<String> declared = parameterNames(annotation.values().getOrDefault("declaredParameters", List.of()));
        Object system = annotation.values().get("typeSystem");
        if (!(system instanceof String name) || name.isEmpty()) throw new IOException("Invalid JypeClass typeSystem");
        for (String part : name.split("\\.", -1)) {
            if (!javax.lang.model.SourceVersion.isIdentifier(part) || javax.lang.model.SourceVersion.isKeyword(part)) {
                throw new IOException("Invalid JypeClass typeSystem: " + name);
            }
        }
        return new PublicView(managed, declared, name);
    }

    private static List<String> parameterNames(Object value) throws IOException {
        if (!(value instanceof List<?> values)) throw new IOException("Missing JypeClass parameter list");
        List<String> names = new ArrayList<>();
        for (Object item : values) {
            if (!(item instanceof String name) || !javax.lang.model.SourceVersion.isIdentifier(name)
                    || javax.lang.model.SourceVersion.isKeyword(name) || names.contains(name)) {
                throw new IOException("Invalid JypeClass parameter name: " + item);
            }
            names.add(name);
        }
        return List.copyOf(names);
    }

    private static void collectMemberView(String name, String descriptor, Attributes attributes,
                                          List<MemberView> members) throws IOException {
        for (AnnotationData annotation : attributes.annotations()) {
            if (!annotation.descriptor().equals("Lfr/thegamerfarmer/jype/annotations/JypeMember;")) continue;
            Object version = annotation.values().getOrDefault("version", 1);
            if (!Integer.valueOf(1).equals(version)) throw new IOException("Unsupported JypeMember metadata version: " + version);
            Object source = annotation.values().getOrDefault("sourceType", "");
            Object generated = annotation.values().getOrDefault("generated", 0);
            if (!(source instanceof String type) || !(generated instanceof Integer flag) || flag < 0 || flag > 1) {
                throw new IOException("Invalid JypeMember metadata");
            }
            members.add(new MemberView(name, descriptor, type,
                    parameterNames(annotation.values().getOrDefault("declaredParameters", List.of())), flag == 1));
        }
    }

    private static Attributes readAttributes(DataInputStream input, String[] utf8) throws IOException {
        String signature = "";
        List<AnnotationData> annotations = new ArrayList<>();
        int attributes = input.readUnsignedShort();
        for (int i = 0; i < attributes; i++) {
            String name = utf8[input.readUnsignedShort()];
            long length = Integer.toUnsignedLong(input.readInt());
            if (name.equals("Signature") && length == 2) signature = utf8[input.readUnsignedShort()];
            else if (name.equals("RuntimeInvisibleAnnotations") || name.equals("RuntimeVisibleAnnotations")) {
                byte[] data = input.readNBytes(Math.toIntExact(length));
                if (data.length != length) throw new IOException("Truncated annotation attribute");
                try (var values = new DataInputStream(new ByteArrayInputStream(data))) {
                    int count = values.readUnsignedShort();
                    for (int a = 0; a < count; a++) annotations.add(readAnnotation(values, utf8));
                }
            } else skipFully(input, length);
        }
        return new Attributes(signature, annotations);
    }

    private static List<String> signatureParameters(String signature) throws IOException {
        List<String> parameters = new ArrayList<>();
        if (!signature.startsWith("<")) return parameters;
        int position = 1;
        while (position < signature.length() && signature.charAt(position) != '>') {
            int colon = signature.indexOf(':', position);
            if (colon < 0) throw new IOException("Invalid generic signature: " + signature);
            parameters.add(signature.substring(position, colon));
            position = colon + 1;
            if (position < signature.length() && signature.charAt(position) != ':') {
                position = skipSignatureType(signature, position);
            }
            while (position < signature.length() && signature.charAt(position) == ':') {
                position = skipSignatureType(signature, position + 1);
            }
        }
        if (position >= signature.length()) throw new IOException("Truncated generic signature: " + signature);
        return parameters;
    }

    private static int skipSignatureType(String signature, int position) throws IOException {
        while (position < signature.length() && signature.charAt(position) == '[') position++;
        if (position >= signature.length()) throw new IOException("Truncated generic bound");
        char type = signature.charAt(position++);
        if (type != 'L' && type != 'T') return position;
        int depth = 0;
        while (position < signature.length()) {
            char character = signature.charAt(position++);
            if (character == '<') depth++;
            else if (character == '>') depth--;
            else if (character == ';' && depth == 0) return position;
        }
        throw new IOException("Truncated generic bound: " + signature);
    }

    /**
     * Reads a package's type-system annotation without resolving its parent.
     *
     * @return metadata, or {@code null} if neither annotation attribute contains TypeSystem
     * @throws IOException for malformed or unreadable class data
     */
    static TypeSystemData read(JavaFileObject classFile) throws IOException {
        try (InputStream stream = classFile.openInputStream();
             DataInputStream input = new DataInputStream(stream)) {
            if (input.readInt() != 0xCAFEBABE) throw new IOException("Invalid class-file magic");
            input.readUnsignedShort();
            input.readUnsignedShort();

            String[] utf8 = readConstantPool(input);
            skipFully(input, 6);
            skipInterfaces(input);
            skipMembers(input);
            skipMembers(input);

            int attributeCount = input.readUnsignedShort();
            for (int i = 0; i < attributeCount; i++) {
                String attributeName = utf8[input.readUnsignedShort()];
                long length = Integer.toUnsignedLong(input.readInt());
                if (!"RuntimeInvisibleAnnotations".equals(attributeName)
                        && !"RuntimeVisibleAnnotations".equals(attributeName)) {
                    skipFully(input, length);
                    continue;
                }
                byte[] data = input.readNBytes(Math.toIntExact(length));
                if (data.length != length) throw new IOException("Truncated annotation attribute");
                TypeSystemData result = readAnnotations(data, utf8);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static String[] readConstantPool(DataInputStream input) throws IOException {
        int count = input.readUnsignedShort();
        return readConstantPool(input, count, new int[count]);
    }

    private static String[] readConstantPool(DataInputStream input, int count, int[] classes) throws IOException {
        String[] utf8 = new String[count];
        for (int i = 1; i < count; i++) {
            int tag = input.readUnsignedByte();
            switch (tag) {
                case 1 -> utf8[i] = input.readUTF();
                case 3 -> utf8[i] = Integer.toString(input.readInt());
                case 4 -> skipFully(input, 4);
                case 5, 6 -> {
                    skipFully(input, 8);
                    i++;
                }
                case 7 -> classes[i] = input.readUnsignedShort();
                case 8, 16, 19, 20 -> skipFully(input, 2);
                case 9, 10, 11, 12, 17, 18 -> skipFully(input, 4);
                case 15 -> skipFully(input, 3);
                default -> throw new IOException("Unsupported constant-pool tag: " + tag);
            }
        }
        return utf8;
    }

    private static void skipInterfaces(DataInputStream input) throws IOException {
        int count = input.readUnsignedShort();
        skipFully(input, 2L * count);
    }

    private static void skipMembers(DataInputStream input) throws IOException {
        int count = input.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            skipFully(input, 6);
            int attributes = input.readUnsignedShort();
            for (int j = 0; j < attributes; j++) {
                input.readUnsignedShort();
                skipFully(input, Integer.toUnsignedLong(input.readInt()));
            }
        }
    }

    private static TypeSystemData readAnnotations(byte[] data, String[] utf8) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(data))) {
            int count = input.readUnsignedShort();
            for (int i = 0; i < count; i++) {
                AnnotationData annotation = readAnnotation(input, utf8);
                if (!TYPE_SYSTEM_DESCRIPTOR.equals(annotation.descriptor())) continue;

                Set<String> names = new LinkedHashSet<>();
                collectTypeDefNames(annotation.values().get("value"), names);
                collectTypeDefNames(annotation.values().get("overrides"), names);
                Object parent = annotation.values().get("inherit");
                java.util.Map<String, String> bounds = new java.util.LinkedHashMap<>();
                collectBounds(annotation.values().get("value"), bounds);
                collectBounds(annotation.values().get("overrides"), bounds);
                return new TypeSystemData(names, parent instanceof String s ? s : "", bounds);
            }
        }
        return null;
    }

    private static AnnotationData readAnnotation(DataInputStream input, String[] utf8)
            throws IOException {
        String descriptor = utf8[input.readUnsignedShort()];
        int pairCount = input.readUnsignedShort();
        java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairCount; i++) {
            String name = utf8[input.readUnsignedShort()];
            values.put(name, readElementValue(input, utf8));
        }
        return new AnnotationData(descriptor, values);
    }

    private static Object readElementValue(DataInputStream input, String[] utf8) throws IOException {
        char tag = (char) input.readUnsignedByte();
        return switch (tag) {
            case 's' -> utf8[input.readUnsignedShort()];
            case '[' -> {
                int count = input.readUnsignedShort();
                List<Object> values = new ArrayList<>(count);
                for (int i = 0; i < count; i++) values.add(readElementValue(input, utf8));
                yield values;
            }
            case '@' -> readAnnotation(input, utf8);
            case 'e' -> {
                input.readUnsignedShort();
                yield utf8[input.readUnsignedShort()];
            }
            case 'c' -> utf8[input.readUnsignedShort()];
            case 'I', 'Z' -> Integer.valueOf(utf8[input.readUnsignedShort()]);
            case 'B', 'C', 'D', 'F', 'J', 'S' -> {
                input.readUnsignedShort();
                yield null;
            }

            default -> throw new IOException("Unsupported annotation value tag: " + tag);
        };
    }

    private static void collectBounds(Object value, java.util.Map<String, String> bounds) {
        if (value instanceof List<?> values) {
            for (Object item : values) collectBounds(item, bounds);
        } else if (value instanceof AnnotationData annotation
                && TYPE_DEF_DESCRIPTOR.equals(annotation.descriptor())) {
            Object name = annotation.values().get("name");
            Object bound = annotation.values().get("bound");
            if (name instanceof String n && bound instanceof String descriptor
                    && descriptor.startsWith("L") && descriptor.endsWith(";")) {
                bounds.put(n, descriptor.substring(1, descriptor.length() - 1).replace('/', '.').replace('$', '.'));
            }
        }
    }

    private static void collectTypeDefNames(Object value, Set<String> names) {
        if (value instanceof List<?> values) {
            for (Object item : values) collectTypeDefNames(item, names);
        } else if (value instanceof AnnotationData annotation
                && TYPE_DEF_DESCRIPTOR.equals(annotation.descriptor())) {
            Object name = annotation.values().get("name");
            if (name instanceof String s && !s.isEmpty()) names.add(s);
        }
    }

    private static void skipFully(DataInputStream input, long byteCount) throws IOException {
        if (byteCount < 0) throw new IOException("Negative class-file length");
        long remaining = byteCount;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped <= 0) {
                if (input.read() < 0) throw new IOException("Truncated class file");
                skipped = 1;
            }
            remaining -= skipped;
        }
    }
}
