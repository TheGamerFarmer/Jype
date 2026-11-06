package fr.thegamerfarmer.jype;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JavadocSourceGeneratorTest {

    @Test
    void validatesEveryParameterOrderBeforeEditingSource() {
        String source = "class Value {}";
        var absent = new java.util.HashMap<String, List<String[]>>();
        absent.put("Value", null);
        assertThrows(IllegalArgumentException.class,
                () -> JavadocSourceGenerator.patchContent(source, absent, Map.of()));
        for (String[] invalid : new String[][]{
                null, {}, {"TYPE"}, {null, "true"}, {"TYPE", null},
                {"not-a-name", "true"}, {"class", "true"}, {"TYPE", "yes"}}) {
            var order = java.util.Collections.singletonList(invalid);
            assertThrows(IllegalArgumentException.class,
                    () -> JavadocSourceGenerator.patchContent(source, Map.of("Value", order), Map.of()));
        }
        var duplicate = List.of(new String[]{"TYPE", "true"}, new String[]{"TYPE", "false"});
        assertThrows(IllegalArgumentException.class,
                () -> JavadocSourceGenerator.patchContent(source, Map.of("Value", duplicate), Map.of()));
        assertEquals(source, assertDoesNotThrowPatching(source, Map.of("Value", List.of())));
    }

    private static String assertDoesNotThrowPatching(String source, Map<String, List<String[]>> orders) {
        return org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                () -> JavadocSourceGenerator.patchContent(source, Map.of(), orders));
    }

    @Test
    void rejectsUnrepresentableDeclarationAndUseSiteOrders() {
        var mixed = List.of(new String[]{"TYPE", "true"}, new String[]{"T", "false"});
        assertThrows(IllegalStateException.class, () -> JavadocSourceGenerator.patchContent(
                "class Value {} ", Map.of(), Map.of("Value", mixed)));
        assertThrows(IllegalStateException.class, () -> JavadocSourceGenerator.patchContent(
                "record Value() {} ", Map.of(), Map.of("Value", List.<String[]>of(new String[]{"TYPE", "true"}))));
        assertThrows(IllegalStateException.class, () -> JavadocSourceGenerator.patchContent(
                "enum Value { ONE }", Map.of(), Map.of("Value", List.<String[]>of(new String[]{"TYPE", "true"}))));
        assertThrows(IllegalStateException.class, () -> JavadocSourceGenerator.patchContent(
                "class Value<A, B> {}", Map.of(), Map.of("Value", List.<String[]>of(new String[]{"A", "true"}))));
        assertThrows(IllegalStateException.class, () -> JavadocSourceGenerator.patchContent(
                "class Use { Value<String, Integer, Long> field; }", Map.of("Value", mixed), Map.of()));
        assertThrows(IllegalStateException.class, () -> JavadocSourceGenerator.patchContent(
                "class Use { Value<String> field; }",
                Map.of("Value", List.<String[]>of(new String[]{"ORIGINAL", "false"})),
                Map.of("Value", List.of(new String[]{"TYPE", "true"}, new String[]{"OTHER", "false"}))));
    }

    @Test
    void staticAndImplicitlyStaticScopesDoNotCaptureEnclosingParameters() throws Exception {
        var orders = Map.of("Box", List.of(new String[]{"TYPE", "true"}, new String[]{"T", "false"}));
        for (String source : List.of(
                "class Use<TYPE> { static { Box<String> value = null; } }",
                "class Use<TYPE> { static Box<String> method() { return null; } }",
                "class Use<TYPE> { enum Nested { ONE; Box<String> value; } }",
                "class Use<TYPE> { record Nested(Box<String> value) {} }",
                "class Use<TYPE> { interface Nested { Box<String> method(); } }",
                "@interface Outer { class Nested { Box<String> value; } }",
                "class Outer<TYPE> { class Use { class TYPE {} Box<String> value; } }",
                "class Outer<TYPE> { class TYPE { Box<String> value; } }")) {
            String patched = JavadocSourceGenerator.patchContent(source, orders, Map.of());
            assertTrue(patched.contains("Box<?, String>"), () -> source + "\n" + patched);
        }
        String methodOwned = "class Use { static <TYPE> Box<String> method() { return null; } }";
        assertTrue(JavadocSourceGenerator.patchContent(methodOwned, orders, Map.of()).contains("Box<TYPE, String>"));
    }

    @Test
    void tokenizerPreservesEscapesTextBlocksCommentsAndUnicode(@TempDir Path directory) throws Exception {
        String source = """
                /* class Value<pretend> {} */
                class Value {}
                class Café<TYPE> {
                    Value value;
                    String escaped = "class Value\\\\\\" {}";
                    char quote = '\\'';
                    char slash = '\\\\';
                    String block = \"""
                            class Value {}
                            \\" quoted
                            \""";
                    // Value is a comment, not a reference
                }
                """;
        var orders = Map.of("Value", List.<String[]>of(new String[]{"TYPE", "true"}));
        String patched = JavadocSourceGenerator.patchContent(source, Map.of(), orders);
        assertTrue(patched.contains("class Value<TYPE> {}"));
        assertTrue(patched.contains("Value<TYPE> value;"));
        assertTrue(patched.contains("/* class Value<pretend> {} */"));
        assertTrue(patched.contains("class Value {}\\n") || patched.contains("class Value {}\n"));
        assertTrue(patched.contains("char quote = '\\'';"));
        Path file = directory.resolve("Types.java");
        Files.writeString(file, patched);
        assertJavadoc(directory, List.of(file));
    }

    @Test
    void completesTrailingRawBoundBeforeAppendingClassParameters(@TempDir Path directory) throws Exception {
        String source = """
                package app;
                class Master {}
                class Sub<M extends Master> {}
                """;
        var parameters = Map.of(
                "app.Master", List.<String[]>of(new String[]{"TYPE", "true"}),
                "app.Sub", List.of(new String[]{"M", "false"}, new String[]{"TYPE", "true"}));
        String patched = JavadocSourceGenerator.patchContent(source, Map.of(), parameters);
        assertTrue(patched.contains("class Sub<M extends Master<TYPE>, TYPE>"));
        Path file = directory.resolve("Types.java");
        Files.writeString(file, patched);
        assertJavadoc(directory, List.of(file));
    }

    @Test
    void preservesAndPatchesNestedRecursiveBounds(@TempDir Path directory) throws Exception {
        String source = """
                package app;
                abstract class Master<S extends Sub<S, ?>> {}
                abstract class Sub<S extends Sub<S, ?>, M extends Master<S>> {}
                class Event<S extends Sub<S, ?>, M extends Master<S>> {}
                """;
        Map<String, List<String[]>> parameters = Map.of(
                "app.Master", List.of(new String[]{"S", "false"}, new String[]{"TYPE", "true"}),
                "app.Sub", List.of(new String[]{"S", "false"}, new String[]{"M", "false"},
                        new String[]{"TYPE", "true"}),
                "app.Event", List.of(new String[]{"S", "false"}, new String[]{"M", "false"},
                        new String[]{"TYPE", "true"}));

        String patched = JavadocSourceGenerator.patchContent(source, Map.of(), parameters);

        assertTrue(patched.contains("Master<S extends Sub<S, ?, TYPE>, TYPE>"));
        assertTrue(patched.contains("Sub<S extends Sub<S, ?, TYPE>, M extends Master<S, TYPE>, TYPE>"));
        assertTrue(patched.contains("Event<S extends Sub<S, ?, TYPE>, M extends Master<S, TYPE>, TYPE>"));
        assertEquals(patched, JavadocSourceGenerator.patchContent(patched, Map.of(), parameters));
        Path file = directory.resolve("Types.java");
        Files.writeString(file, patched);
        var compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            boolean success = compiler.getTask(null, manager, diagnostics,
                    List.of("-proc:none", "-d", directory.toString()), null,
                    manager.getJavaFileObjects(file)).call();
            assertTrue(success, () -> patched + "\n" + diagnostics.getDiagnostics());
            assertTrue(diagnostics.getDiagnostics().stream().noneMatch(d -> d.getKind() == Diagnostic.Kind.ERROR));
        }
        assertJavadoc(directory, List.of(file));
    }

    @Test
    void patchesQualifiedAndNestedTypesWithoutTouchingCommentsOrStrings(@TempDir Path directory) throws Exception {
        String source = """
                package app;
                class Widget<T> {}
                class Outer<TYPE_INNER> {
                    static class Widget<T> {}
                    Widget<String> nested;
                }
                class Use<TYPE_TOP> {
                    app.Widget<java.util.List<String>> top;
                    String text = "Widget<String>";
                    // Widget<String>
                }
                """;
        Map<String, List<String[]>> parameters = Map.of(
                "app.Widget", List.of(new String[]{"TYPE_TOP", "true"}, new String[]{"T", "false"}),
                "app.Outer.Widget", List.of(new String[]{"TYPE_INNER", "true"}, new String[]{"T", "false"}));

        String patched = JavadocSourceGenerator.patchContent(source, Map.of(), parameters);

        assertTrue(patched.contains("class Widget<TYPE_TOP, T>"));
        assertTrue(patched.contains("static class Widget<TYPE_INNER, T>"));
        assertTrue(patched.contains("Widget<TYPE_INNER, String> nested;"));
        assertTrue(patched.contains("app.Widget<TYPE_TOP, java.util.List<String>> top;"));
        assertTrue(patched.contains("\"Widget<String>\""));
        assertTrue(patched.contains("// Widget<String>"));
        Path file = directory.resolve("Types.java");
        Files.writeString(file, patched);
        assertJavadoc(directory, List.of(file));
    }

    @Test
    void patchesUseSiteByExplicitImportInsteadOfMatchingSameSimpleName() throws Exception {
        String source = """
                package consumer;
                import first.Widget;
                class Use<TYPE_FIRST, TYPE_SECOND> {
                    Widget<String> first;
                    second.Widget<Integer> second;
                }
                """;
        Map<String, List<String[]>> parameters = Map.of(
                "first.Widget", List.of(new String[]{"TYPE_FIRST", "true"}, new String[]{"T", "false"}),
                "second.Widget", List.of(new String[]{"TYPE_SECOND", "true"}, new String[]{"T", "false"}));

        String patched = JavadocSourceGenerator.patchContent(source, parameters, Map.of());

        assertTrue(patched.contains("Widget<TYPE_FIRST, String> first;"));
        assertTrue(patched.contains("second.Widget<TYPE_SECOND, Integer> second;"));
    }

    @Test
    void respectsTypeParameterMemberLocalAndImportShadowing() throws Exception {
        String source = """
                package app;
                import other.Widget;
                class Use<TYPE, Widget> {
                    Widget field;
                    static class Local<T> {}
                    Local<String> member;
                    <Widget> void method() { Widget local = null; }
                    void block() { class Widget<T> {} Widget<String> local = null; }
                }
                class Another<TYPE> { Widget<String> imported; }
                """;
        Map<String, List<String[]>> parameters = Map.of(
                "app.Widget", List.of(new String[]{"TYPE", "true"}, new String[]{"T", "false"}),
                "app.Local", List.of(new String[]{"TYPE", "true"}, new String[]{"T", "false"}));
        assertEquals(source, JavadocSourceGenerator.patchContent(source, parameters, Map.of()));
    }

    @Test
    void rejectsAmbiguousImportsMalformedSourcesAndInconsistentOrders() {
        var params = Map.of("first.Widget",
                List.of(new String[]{"TYPE", "true"}, new String[]{"T", "false"}));
        assertThrows(IllegalStateException.class, () -> JavadocSourceGenerator.patchContent("""
                import first.*;
                import second.*;
                class Use<TYPE> { Widget<String> field; }
                """, Map.of("first.Widget", params.get("first.Widget"),
                        "second.Widget", params.get("first.Widget")), Map.of()));
        var malformed = assertThrows(IllegalArgumentException.class,
                () -> JavadocSourceGenerator.patchContent("class Broken {", params, Map.of()));
        assertTrue(malformed.getMessage().contains("line 1"));
        assertThrows(IllegalStateException.class, () -> JavadocSourceGenerator.patchContent(
                "package first; class Widget<X> {}", Map.of(), params));
    }

    @Test
    void missingContextUsesWildcardsLikeTheMemberCompletionPass() throws Exception {
        var params = Map.of("first.Widget",
                List.of(new String[]{"TYPE", "true"}, new String[]{"T", "false"}));
        for (String source : List.of(
                "import first.Widget; class Use { Widget<String> field; }",
                "import first.Widget; class Use<TYPE> { static Widget<String> field; }",
                "interface Use<TYPE> { class Nested { first.Widget<String> field; } }",
                "class Use<TYPE> { void method() { class TYPE {} first.Widget<String> field; } }")) {
            assertTrue(JavadocSourceGenerator.patchContent(source, params, Map.of()).contains("Widget<?, String>"));
        }
    }

    @Test
    void preservesDiamondsAndInsertsMultipleParametersWithoutLosingBounds(@TempDir Path directory)
            throws Exception {
        String source = """
                package app;
                class Box<T extends Comparable<T>> {}
                class Use<A, B, C> {
                    Box<String> value = new Box<>();
                }
                """;
        var params = Map.of("app.Box", List.of(new String[]{"A", "true"}, new String[]{"B", "true"},
                new String[]{"T", "false"}, new String[]{"C", "true"}));
        String patched = JavadocSourceGenerator.patchContent(source, Map.of(), params);
        assertTrue(patched.contains("Box<A, B, T extends Comparable<T>, C>"));
        assertTrue(patched.contains("Box<A, B, String, C> value = new Box<>()"));
        assertEquals(patched, JavadocSourceGenerator.patchContent(patched, Map.of(), params));
        Path file = directory.resolve("Types.java");
        Files.writeString(file, patched);
        assertJavadoc(directory, List.of(file));
    }

    @Test
    void preservesOriginalManagedArgumentsAndCompletesAllManagedPrefixes(@TempDir Path directory)
            throws Exception {
        String source = """
                package app;
                abstract class APIPower {}
                abstract class Container<T extends APIPower<T>> {}
                abstract class PartialBase<TYPE_A, E> {}
                abstract class SubAbstract<TYPE_A> extends PartialBase<TYPE_A, String> {}
                """;
        Map<String, List<String[]>> originals = Map.of(
                "app.APIPower", List.of(),
                "app.PartialBase", List.of(new String[]{"TYPE_A", "true"}, new String[]{"E", "false"}),
                "app.SubAbstract", List.<String[]>of(new String[]{"TYPE_A", "true"}));
        Map<String, List<String[]>> declarations = Map.of(
                "app.APIPower", List.of(new String[]{"J_A", "true"}, new String[]{"J_B", "true"}),
                "app.Container", List.of(new String[]{"T", "false"}, new String[]{"J_B", "true"}),
                "app.PartialBase", List.of(new String[]{"TYPE_A", "true"}, new String[]{"E", "false"},
                        new String[]{"TYPE_B", "true"}),
                "app.SubAbstract", List.of(new String[]{"TYPE_A", "true"}, new String[]{"TYPE_B", "true"}));
        String patched = JavadocSourceGenerator.patchContent(source, originals, declarations);
        assertTrue(patched.contains("Container<T extends APIPower<T, J_B>, J_B>"));
        assertTrue(patched.contains("SubAbstract<TYPE_A, TYPE_B> extends PartialBase<TYPE_A, String, TYPE_B>"));
        assertEquals(patched, JavadocSourceGenerator.patchContent(patched, originals, declarations));
        Path file = directory.resolve("Types.java");
        Files.writeString(file, patched);
        assertJavadoc(directory, List.of(file));
    }

    @Test
    void patchesManagedOnlyRawTypesInScopeButPreservesTrueRawUses(@TempDir Path directory) throws Exception {
        String source = """
                package app;
                class Managed {}
                class Generic<T> {}
                class Use<TYPE> {
                    Managed field;
                    Managed[] array;
                    java.util.List<Managed> nested;
                    Managed result() { return null; }
                    Generic raw;
                    static Managed staticRaw;
                    Class<?> literal = Managed.class;
                    Object create() { return new Managed(); }
                }
                """;
        Map<String, List<String[]>> params = Map.of(
                "app.Managed", List.<String[]>of(new String[]{"TYPE", "true"}),
                "app.Generic", List.of(new String[]{"TYPE", "true"}, new String[]{"T", "false"}));
        String patched = JavadocSourceGenerator.patchContent(source, Map.of(), params);
        assertTrue(patched.contains("Managed<TYPE> field"));
        assertTrue(patched.contains("Managed<TYPE>[] array"));
        assertTrue(patched.contains("java.util.List<Managed<TYPE>> nested"));
        assertTrue(patched.contains("Managed<TYPE> result()"));
        assertTrue(patched.contains("Generic raw"));
        assertTrue(patched.contains("static Managed staticRaw"));
        assertTrue(patched.contains("Managed.class"));
        assertTrue(patched.contains("new Managed()"));
        assertEquals(patched, JavadocSourceGenerator.patchContent(patched, Map.of(), params));
        Path file = directory.resolve("Types.java");
        Files.writeString(file, patched);
        assertJavadoc(directory, List.of(file));
    }

    @Test
    void generatesPackagePathsForNonstandardAndMemorySourcesAndReportsFailures(@TempDir Path directory)
            throws Exception {
        Path source = directory.resolve("custom-layout").resolve("Same.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package first; public class Same {}");
        JavaFileObject memory = memorySource("memory:///Same.java", "package second; public class Same {}");
        JavaFileObject broken = memorySource("memory:///Broken.java", "package broken; class Broken {");
        JavaFileObject unreadable = new SimpleJavaFileObject(URI.create("memory:///Unreadable.java"),
                JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
                throw new IOException("deliberate read failure");
            }
        };
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        var compiler = ToolProvider.getSystemJavaCompiler();
        Path classes = directory.resolve("classes");
        Files.createDirectories(classes);
        try (var manager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            JavaFileObject disk = manager.getJavaFileObjects(source).iterator().next();
            var task = compiler.getTask(null, manager, diagnostics,
                    List.of("-proc:only", "-Ajype.debug=true", "-d", classes.toString()), null,
                    List.of(memorySource("memory:///Trigger.java", "class Trigger {}")));
            task.setProcessors(List.of(processor(env -> {
                var generator = new JavadocSourceGenerator(env, Map.of());
                generator.addDeclPatch("second.Same", List.<String[]>of(new String[]{"TYPE", "true"}));
                generator.generate(List.of(disk, memory, broken, unreadable,
                        memorySource("memory:///duplicate/Same.java", "package first; public class Same {}")));
            })));
            assertTrue(task.call(), () -> diagnostics.getDiagnostics().toString());
        }
        Path output = directory.resolve("jype-javadoc-src");
        assertTrue(Files.exists(output.resolve("first").resolve("Same.java")));
        assertTrue(Files.exists(output.resolve("second").resolve("Same.java")));
        assertTrue(Files.readString(output.resolve("second").resolve("Same.java")).contains("class Same {}"));
        assertFalse(Files.exists(output.resolve("broken").resolve("Broken.java")));
        List<String> generationWarnings = diagnostics.getDiagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.WARNING)
                .map(d -> d.getMessage(java.util.Locale.ROOT))
                .filter(message -> message.startsWith("[Jype]")).toList();
        assertEquals(3, generationWarnings.size(), () -> diagnostics.getDiagnostics().toString());
        assertTrue(generationWarnings.stream().anyMatch(message ->
                message.contains("Broken.java") && message.contains("Cannot parse Javadoc source")));
        assertTrue(generationWarnings.stream().anyMatch(message ->
                message.contains("Unreadable.java") && message.contains("deliberate read failure")));
        assertTrue(generationWarnings.stream().anyMatch(message ->
                message.contains("Duplicate Javadoc output path")));
        assertTrue(diagnostics.getDiagnostics().stream()
                .anyMatch(d -> d.getMessage(null).contains("Generated 2 of 5 javadoc sources")));
        assertJavadoc(directory, List.of(output.resolve("first").resolve("Same.java"),
                output.resolve("second").resolve("Same.java")));
    }

    @Test
    void insertsRawArgumentsBeforeAnnotatedArrayDimensionsAndVarargs(@TempDir Path directory) throws Exception {
        String source = """
                package app;
                import java.lang.annotation.*;
                @Target(ElementType.TYPE_USE) @interface Marker {}
                class Power {}
                class Use<TYPE> {
                    Power @Marker [] one;
                    app.Power @Marker [] @Marker [] two;
                    void add(Power @Marker ... powers) {}
                    void qualified(app.Power @Marker ... powers) {}
                    void array(Power @Marker [] powers) {}
                }
                """;
        var params = Map.of("app.Power", List.<String[]>of(new String[]{"TYPE", "true"}));
        String patched = JavadocSourceGenerator.patchContent(source, Map.of(), params);
        assertTrue(patched.contains("Power<TYPE> @Marker [] one"));
        assertTrue(patched.contains("app.Power<TYPE> @Marker [] @Marker [] two"));
        assertTrue(patched.contains("Power<TYPE> @Marker ... powers"));
        assertTrue(patched.contains("app.Power<TYPE> @Marker ... powers"));
        assertTrue(patched.contains("Power<TYPE> @Marker [] powers"));
        assertEquals(patched, JavadocSourceGenerator.patchContent(patched, Map.of(), params));
        Path file = directory.resolve("Types.java");
        Files.writeString(file, patched);
        assertJavadoc(directory, List.of(file));
    }

    @Test
    void resolvesKnownWildcardMemberWithoutInventingCandidates(@TempDir Path directory) throws Exception {
        String source = """
                package consumer;
                import first.*;
                import java.util.*;
                import java.io.*;
                public class Use<TYPE> { public Widget<String> field; }
                """;
        var params = Map.of("first.Widget",
                List.of(new String[]{"TYPE", "true"}, new String[]{"T", "false"}));
        String patched = JavadocSourceGenerator.patchContent(source, params, Map.of());
        assertTrue(patched.contains("Widget<TYPE, String> field"));
        Path use = directory.resolve("Use.java");
        Path widget = directory.resolve("Widget.java");
        Files.writeString(use, patched);
        Files.writeString(widget, "package first; public class Widget<TYPE, T> {}");
        assertJavadoc(directory, List.of(use, widget));
    }

    @Test
    void generationChecksCompilerSymbolsForUnmanagedWildcardCompetitors(@TempDir Path directory) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        Path classes = directory.resolve("classes");
        Files.createDirectories(classes);
        try (var manager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            var task = compiler.getTask(null, manager, diagnostics,
                    List.of("-proc:only", "-d", classes.toString()), null,
                    List.of(memorySource("memory:///Trigger.java", "class Trigger {}")));
            task.setProcessors(List.of(processor(env -> {
                var generator = new JavadocSourceGenerator(env, Map.of("first.List",
                        List.of(new String[]{"TYPE", "true"}, new String[]{"T", "false"})));
                generator.generate(List.of(memorySource("memory:///Use.java", """
                        import first.*;
                        import java.util.*;
                        class Use<TYPE> { List<String> field; }
                        """)));
            })));
            assertTrue(task.call(), () -> diagnostics.getDiagnostics().toString());
        }
        assertTrue(diagnostics.getDiagnostics().stream().anyMatch(d ->
                d.getKind() == Diagnostic.Kind.WARNING
                        && d.getMessage(java.util.Locale.ROOT).contains("ambiguous wildcard imports: List")));
        assertFalse(Files.exists(directory.resolve("jype-javadoc-src").resolve("Use.java")));
    }

    private static JavaFileObject memorySource(String uri, String source) {
        return new SimpleJavaFileObject(URI.create(uri), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
    }

    private static AbstractProcessor processor(Consumer<ProcessingEnvironment> action) {
        return new AbstractProcessor() {
            @Override
            public Set<String> getSupportedAnnotationTypes() {
                return Set.of("*");
            }

            @Override
            public SourceVersion getSupportedSourceVersion() {
                return SourceVersion.latestSupported();
            }

            @Override
            public Set<String> getSupportedOptions() {
                return Set.of("jype.debug");
            }

            @Override
            public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
                if (roundEnv.processingOver()) action.accept(processingEnv);
                return false;
            }
        };
    }

    private static void assertJavadoc(Path directory, List<Path> files) throws IOException {
        var tool = ToolProvider.getSystemDocumentationTool();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        StringWriter log = new StringWriter();
        try (var manager = tool.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            boolean success = tool.getTask(log, manager, diagnostics, null,
                    List.of("-quiet", "-private", "-d", directory.resolve("docs").toString()),
                    manager.getJavaFileObjectsFromPaths(files)).call();
            assertTrue(success, () -> log + "\n" + diagnostics.getDiagnostics());
            assertTrue(Files.exists(directory.resolve("docs").resolve("index.html")));
        }
    }
}
