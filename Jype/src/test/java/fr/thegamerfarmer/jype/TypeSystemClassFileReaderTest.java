package fr.thegamerfarmer.jype;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TypeSystemClassFileReaderTest {
    @Test
    void readsRealIntersectionBoundsOverloadsAndNestedBinaryNames(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("Library.java");
        Files.writeString(source, """
                package compiled;
                @Deprecated
                public class Library<T extends Number & Comparable<T>> {
                    public static final long LONG = 9L;
                    public static final double DOUBLE = 2.5;
                    public static final float FLOAT = 1.5f;
                    public static final String TEXT = "value";
                    public static <A extends Comparable<? super A>, B extends A> B select(B value) { return value; }
                    public static <X> X overloaded(X value) { return value; }
                    public static <Y, Z> Y overloaded(Y value, Z other) { return value; }
                    public static Runnable lambda() { return () -> System.out.println(TEXT); }
                    public static class Nested<U> extends Library<Integer> {}
                }
                """);
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            assertTrue(compiler.getTask(null, manager, null, List.of("-proc:none", "-d", directory.toString()),
                    null, manager.getJavaFileObjects(source)).call());
            JavaFileObject library = manager.getJavaFileObjects(directory.resolve("compiled").resolve("Library.class"))
                    .iterator().next();
            var data = TypeSystemClassFileReader.readClass(library);
            assertEquals(List.of("T"), data.parameters());
            assertEquals("java.lang.Object", data.parent());
            assertEquals(Set.of("LONG", "DOUBLE", "FLOAT", "TEXT"), data.fields());
            assertEquals(List.of("A", "B"), data.staticMethods().get("select"));
            assertEquals(List.of(), data.staticMethods().get("overloaded"),
                    "Different overload orders cannot provide a reliable inferred static invocation order");
            assertEquals(List.of(), data.staticMethods().get("lambda"));
            assertNull(TypeSystemClassFileReader.read(library), "Unrelated annotations are not type systems");
            var nested = TypeSystemClassFileReader.readClass(manager.getJavaFileObjects(
                    directory.resolve("compiled").resolve("Library$Nested.class")).iterator().next());
            assertEquals(List.of("U"), nested.parameters());
            assertEquals("compiled.Library", nested.parent());
        }
    }

    @Test
    void rejectsCorruptHeadersPoolsAndTruncatedAttributes() throws Exception {
        for (byte[] bytes : List.of(new byte[0], new byte[]{0, 0, 0, 0}, corruptPool(), truncatedAttribute())) {
            assertThrows(IOException.class, () -> TypeSystemClassFileReader.readClass(binary(bytes)));
            assertThrows(IOException.class, () -> TypeSystemClassFileReader.read(binary(bytes)));
        }
    }

    @Test
    void rejectsMalformedGenericSignaturesRatherThanReturningPartialOrders() throws Exception {
        for (String signature : List.of("<MissingColon", "<T:", "<T:Lmissing", "<T:[", "<T:Ljava/lang/Object;")) {
            var exception = assertThrows(IOException.class,
                    () -> TypeSystemClassFileReader.readClass(binary(classWithSignature(signature))));
            assertTrue(exception.getMessage().contains("generic"), exception::getMessage);
        }
    }

    private static JavaFileObject binary(byte[] contents) {
        return new SimpleJavaFileObject(URI.create("memory:///Fixture.class"), JavaFileObject.Kind.CLASS) {
            @Override
            public ByteArrayInputStream openInputStream() {
                return new ByteArrayInputStream(contents);
            }
        };
    }

    private static byte[] corruptPool() throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            header(output, 2);
            output.writeByte(99);
        }
        return bytes.toByteArray();
    }

    private static byte[] classWithSignature(String signature) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            header(output, 3);
            output.writeByte(1);
            output.writeUTF("Signature");
            output.writeByte(1);
            output.writeUTF(signature);
            for (int i = 0; i < 6; i++) output.writeShort(0);
            output.writeShort(1);
            output.writeShort(1);
            output.writeInt(2);
            output.writeShort(2);
        }
        return bytes.toByteArray();
    }

    private static byte[] truncatedAttribute() throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            header(output, 2);
            output.writeByte(1);
            output.writeUTF("RuntimeVisibleAnnotations");
            for (int i = 0; i < 6; i++) output.writeShort(0);
            output.writeShort(1);
            output.writeShort(1);
            output.writeInt(20);
            output.writeByte(0);
        }
        return bytes.toByteArray();
    }

    private static void header(DataOutputStream output, int poolCount) throws IOException {
        output.writeInt(0xCAFEBABE);
        output.writeShort(0);
        output.writeShort(65);
        output.writeShort(poolCount);
    }
}
