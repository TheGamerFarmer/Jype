package fr.thegamerfarmer.jype;

import fr.thegamerfarmer.jype.util.JypeTestCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class JypePublicViewTest {
    private static final String[] STRICT = {"-Xplugin:Jype", "-processor",
            "fr.thegamerfarmer.jype.JypeProcessorShim", "-Xlint:rawtypes,unchecked", "-Werror"};

    @Test
    void documentaryAliasesDoNotCollideWithCaseOnlySourceNames(@TempDir Path root) throws Exception {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("commands/package-info.java", """
                @fr.thegamerfarmer.jype.annotations.TypeSystem(
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name="MARK", bound=String.class))
                package commands;
                """);
        sources.put("commands/Mark.java", """
                package commands;
                /** Ordinary command, not the alias. */
                public class Mark {
                    public MARK mark;
                    public MARK getMark() { return mark; }
                }
                """);
        for (int order = 0; order < 2; order++) {
            Path output = root.resolve("order" + order);
            JypeTestCompiler.compileTo(sources, output.resolve("classes"), List.of(), STRICT).assertSuccess();
            Path documents = output.resolve("jype-javadoc-src");
            try (var files = Files.walk(documents)) {
                List<Path> javaFiles = files.filter(path -> path.toString().endsWith(".java")).toList();
                assertEquals(javaFiles.size(), javaFiles.stream()
                        .map(path -> path.toString().toLowerCase(Locale.ROOT)).distinct().count());
                assertTrue(javaFiles.stream().anyMatch(path -> path.getFileName().toString().equals("Mark.java")));
            }
            render(documents, output.resolve("html"));
            String html = Files.readString(output.resolve("html").resolve("commands").resolve("Mark.html"));
            assertTrue(html.contains("Ordinary command, not the alias."), html);
            assertTrue(html.contains("jype_alias_4d41524b/MARK.html"), html);
            assertTrue(html.contains(">MARK</a>"), html);
            Map<String, String> reversed = new LinkedHashMap<>();
            sources.entrySet().stream().toList().reversed()
                    .forEach(entry -> reversed.put(entry.getKey(), entry.getValue()));
            sources = reversed;
        }
        Path consumer = root.resolve("consumer");
        JypeTestCompiler.compileTo(Map.of(
                "consumer/Command.java", "package consumer; public class Command extends commands.Mark {}"),
                consumer.resolve("classes"), List.of(root.resolve("order1").resolve("classes")), STRICT).assertSuccess();
        render(consumer.resolve("jype-javadoc-src"), consumer.resolve("html"));
        String inheritedHtml = Files.readString(consumer.resolve("html").resolve("commands").resolve("Mark.html"));
        assertTrue(inheritedHtml.contains("jype_alias_4d41524b/MARK.html"), inheritedHtml);
        assertTrue(inheritedHtml.contains(">MARK</a>"), inheritedHtml);
    }

    @Test
    void concreteOverridesPreserveRecursiveLibraryContractsOnTemplates(@TempDir Path root) throws Exception {
        Path library = root.resolve("library").resolve("classes");
        JypeTestCompiler.compileTo(Map.of(
                "api/package-info.java", """
                        @fr.thegamerfarmer.jype.annotations.TypeSystem({
                            @fr.thegamerfarmer.jype.annotations.TypeDef(name="J_A", bound=Entity.class),
                            @fr.thegamerfarmer.jype.annotations.TypeDef(name="J_B", bound=Kind.class)
                        }) package api;
                        """,
                "api/Entity.java", """
                        package api;
                        public abstract class Entity {
                            public abstract J_A linked();
                            public J_B kind;
                        }
                        """,
                "api/Kind.java", "package api; public abstract class Kind { public J_A entity; }",
                "api/Facade.java", """
                        package api;
                        public abstract class Facade {
                            public J_A entity;
                            public J_B kind;
                        }
                        """), library, List.of(), STRICT).assertSuccess();
        Path game = root.resolve("game").resolve("classes");
        JypeTestCompiler.compileTo(Map.of(
                "game/package-info.java", """
                        @fr.thegamerfarmer.jype.annotations.TypeSystem(inherit="api", overrides={
                            @fr.thegamerfarmer.jype.annotations.TypeDef(name="J_A", bound=Entity.class),
                            @fr.thegamerfarmer.jype.annotations.TypeDef(name="J_B", bound=Kind.class)
                        }) package game;
                        """,
                "game/Entity.java", """
                        package game;
                        public class Entity extends api.Entity {
                            public Entity linked() { return this; }
                        }
                        """,
                "game/Kind.java", "package game; public class Kind extends api.Kind {}",
                "game/Template.java", """
                        package game;
                        public abstract class Template extends api.Facade {
                            public J_A linked() { return entity.linked(); }
                        }
                        """,
                "game/Main.java", """
                        package game;
                        public class Main extends api.Facade {
                            public Entity linked() { return entity.linked(); }
                        }
                        """), game, List.of(library), STRICT).assertSuccess();
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{
                game.toUri().toURL(), library.toUri().toURL()})) {
            var template = loader.loadClass("game.Template");
            assertEquals(2, template.getTypeParameters().length);
            for (var parameter : template.getTypeParameters()) {
                var bound = (java.lang.reflect.ParameterizedType) parameter.getBounds()[0];
                assertEquals(parameter.getName().equals("J_A") ? "api.Entity" : "api.Kind",
                        ((Class<?>) bound.getRawType()).getName());
                assertArrayEquals(template.getTypeParameters(), bound.getActualTypeArguments());
            }
            assertEquals(0, loader.loadClass("game.Main").getTypeParameters().length);
        }
    }

    @Test
    void compatibilitySmokeKeepsSharedOrdinaryInheritanceArguments(@TempDir Path root) throws Exception {
        Path classes = root.resolve("classes");
        JypeTestCompiler.compileTo(Map.of(
                "smoke/package-info.java", """
                        @fr.thegamerfarmer.jype.annotations.TypeSystem({
                            @fr.thegamerfarmer.jype.annotations.TypeDef(name = "TYPE", bound = String.class)
                        })
                        package smoke;
                        """,
                "smoke/Consumer.java", """
                        package smoke;
                        class Base<TYPE> { TYPE value; }
                        class Consumer extends Base {}
                        class Generic<A, B> {}
                        class Partial<A> extends Generic<String> {}
                        """), classes, List.of(), STRICT).assertSuccess();
        String source = Files.readString(root.resolve("jype-javadoc-src").resolve("smoke").resolve("Consumer.java"));
        assertTrue(source.contains("class Generic<A, B>"), source);
        assertTrue(source.contains("extends Generic<A, String>"), source);
        render(root.resolve("jype-javadoc-src"), root.resolve("html"));
    }

    @Test
    void publicInheritanceRestoresSharedParametersAtEveryPosition(@TempDir Path root) throws Exception {
        Path classes = root.resolve("classes");
        JypeTestCompiler.compileTo(Map.of(
                "shared/package-info.java", """
                        @fr.thegamerfarmer.jype.annotations.TypeSystem(
                            @fr.thegamerfarmer.jype.annotations.TypeDef(name="TYPE", bound=String.class))
                        package shared;
                        """,
                "shared/Examples.java", """
                package shared;
                public class Examples {
                    public static class Generic<A, B, C> {}
                    public interface Contract<A, B, C> {}
                    public static class Leading<A, B> extends Generic<String> {}
                    public static class Middle<B> extends Generic<String, Integer> {}
                    public static class Trailing<B, C> extends Generic<String> {}
                    public static class Implementation<B> implements Contract<String, Integer> {}
                }
                """), classes, List.of(), STRICT).assertSuccess();
        String source = Files.readString(root.resolve("jype-javadoc-src").resolve("shared").resolve("Examples.java"));
        assertTrue(source.contains("extends Generic<A, B, String>"), source);
        assertTrue(source.contains("extends Generic<String, B, Integer>"), source);
        assertTrue(source.contains("extends Generic<String, B, C>"), source);
        assertTrue(source.contains("implements Contract<String, B, Integer>"), source);
        render(root.resolve("jype-javadoc-src"), root.resolve("html"));
        assertTrue(Files.readString(root.resolve("html").resolve("shared").resolve("Examples.Middle.html"))
                .replaceAll("<[^>]*>", "").contains("Generic&lt;String"));
    }

    @Test
    void transformedLibraryApiAndConsumerKeepJvmTypesAndRenderPublicAliases(@TempDir Path root) throws Exception {
        assertTrue(root.toAbsolutePath().startsWith(Path.of("target").toAbsolutePath()));
        Map<String, String> library = new LinkedHashMap<>();
        StringBuilder definitions = new StringBuilder(
                "@fr.thegamerfarmer.jype.annotations.TypeDef(name=\"ENTITY\",bound=BaseNode.class)");
        for (int i = 0; i < 19; i++) definitions.append(
                ",@fr.thegamerfarmer.jype.annotations.TypeDef(name=\"TYPE_" + i + "\",bound=String.class)");
        library.put("library/package-info.java", "@fr.thegamerfarmer.jype.annotations.TypeSystem({"
                + definitions + "}) package library;");
        library.put("library/BaseNode.java", """
                package library;
                /** Recursive entity contract. */
                public abstract class BaseNode {
                    /** Returns the original entity alias. */
                    public abstract ENTITY linked();
                }
                """);
        library.put("library/Facade.java", """
                package library;
                import java.util.List;
                /** Original facade comments survive. */
                public abstract class Facade {
                    /** Returns an entity. */
                    public abstract ENTITY getNode();
                    /** Returns entities. */
                    public abstract List<ENTITY> getNodes();
                    /** Ordinary method generic shadows the alias. */
                    public <ENTITY extends Number> ENTITY identity(ENTITY value) { return value; }
                    public ENTITY echo(ENTITY input) { return input; }
                    public java.util.List<ENTITY> echo(java.util.List<ENTITY> input) { return input; }
                    /** Public static alias. */
                    public static ENTITY current() { return null; }
                }
                """);
        library.put("library/Box.java", """
                package library;
                /** Ordinary generic stays visible. */
                public class Box<T extends CharSequence> {
                    public ENTITY node;
                    public T value;
                    public class Nested { public ENTITY node; }
                }
                """);
        library.put("library/Declared.java", """
                package library;
                /**
                 * Original declared generic comments.
                 * @param <ENTITY> technical entity slot
                 * @param <T> ordinary text parameter
                 */
                public class Declared<ENTITY, T extends CharSequence> {
                    public ENTITY node;
                }
                """);
        Path libraryClasses = root.resolve("library").resolve("classes");
        JypeTestCompiler.compileTo(library, libraryClasses, List.of(), STRICT).assertSuccess();
        Path libraryJar = JypeTestCompiler.jar(libraryClasses, root.resolve("library.jar"));
        var metadata = readClass(libraryClasses.resolve("library").resolve("Box.class"));
        assertNotNull(metadata.publicView());
        assertEquals(List.of("T"), metadata.publicView().declaredParameters());
        assertEquals(List.of("ENTITY", "T"), readClass(libraryClasses.resolve("library").resolve("Declared.class"))
                .publicView().declaredParameters(), "Capture must precede both Enter and processor injection");
        assertEquals("library", metadata.publicView().typeSystem());
        assertEquals(20, metadata.publicView().managedParameters().size());
        assertEquals(21, metadata.parameters().size(), "JVM generic Signature must remain intact");
        var facadeMetadata = readClass(libraryClasses.resolve("library").resolve("Facade.class"));
        assertEquals("ENTITY", facadeMetadata.memberViews().stream()
                .filter(member -> member.name().equals("current")).findFirst().orElseThrow().sourceType());
        assertTrue(facadeMetadata.memberViews().stream().filter(member -> member.name().equals("current"))
                .findFirst().orElseThrow().declaredParameters().isEmpty());
        assertEquals(List.of("ENTITY"), facadeMetadata.memberViews().stream()
                .filter(member -> member.name().equals("identity")).findFirst().orElseThrow().declaredParameters());
        assertEquals(Set.of("ENTITY", "java.util.List<ENTITY>"), facadeMetadata.memberViews().stream()
                .filter(member -> member.name().equals("echo")).map(TypeSystemClassFileReader.MemberView::sourceType)
                .collect(java.util.stream.Collectors.toSet()), "Overloads sharing parameter names need distinct origins");
        assertTrue(readClass(libraryClasses.resolve("library").resolve("Box$Nested.class"))
                .publicView().declaredParameters().isEmpty());

        Map<String, String> api = Map.of(
                "api/package-info.java", """
                        @fr.thegamerfarmer.jype.annotations.TypeSystem(inherit="library") package api;
                        """,
                "api/LibCore.java", """
                        package api;
                        /** Transitive compiled facade. */
                        public abstract class LibCore extends library.Facade {
                            /** Original API comment. */
                            public abstract ENTITY getNode();
                        }
                        """,
                "api/Holder.java", """
                        package api;
                        /** A template that only mentions a compiled facade, not an alias. */
                        public abstract class Holder {
                            public library.Facade facade;
                            public library.Box<String> box;
                            public library.Facade use(library.Facade input) {
                                library.Facade local = input;
                                return local;
                            }
                            public static library.Facade echo(library.Facade input) {
                                library.Facade local = input;
                                return local;
                            }
                        }
                        """);
        Path apiClasses = root.resolve("api").resolve("classes");
        JypeTestCompiler.compileTo(api, apiClasses, List.of(libraryJar), STRICT).assertSuccess();
        assertEquals(20, readClass(apiClasses.resolve("api").resolve("Holder.class")).parameters().size());
        Path apiJar = JypeTestCompiler.jar(apiClasses, root.resolve("api.jar"));
        Path inheritedClasses = root.resolve("inherited").resolve("classes");
        JypeTestCompiler.compileTo(Map.of("external/More.java",
                "package external; public abstract class More extends api.LibCore {}"),
                inheritedClasses, List.of(libraryJar, apiJar), STRICT).assertSuccess();
        assertEquals("api", readClass(inheritedClasses.resolve("external").resolve("More.class"))
                .publicView().typeSystem());
        Map<String, String> consumer = Map.of(
                "downstream/package-info.java", """
                        @fr.thegamerfarmer.jype.annotations.TypeSystem(inherit="api",overrides={
                          @fr.thegamerfarmer.jype.annotations.TypeDef(name="ENTITY",bound=ConcreteNode.class)
                        }) package downstream;
                        """,
                "downstream/ConcreteNode.java", """
                        package downstream;
                        public class ConcreteNode extends library.BaseNode {
                            public ConcreteNode linked() { return this; }
                        }
                        """,
                "downstream/Consumer.java", """
                        package downstream;
                        import api.LibCore;
                        import library.Box;
                        import java.util.List;
                        public class Consumer {
                            public LibCore core;
                            public Box<String> box;
                            public LibCore handle(LibCore value) {
                                LibCore local = value;
                                ConcreteNode node = local.getNode();
                                List<ConcreteNode> nodes = local.getNodes();
                                Box<String> localBox = new Box<String>();
                                localBox.node = node;
                                box = (Box<String>) localBox;
                                return local;
                            }
                            public static void main(String[] args) {
                                Box<String> value = new Box<String>();
                                value.node = new ConcreteNode();
                                if (value.node.linked() != value.node) throw new AssertionError();
                                if (library.Facade.current() != null) throw new AssertionError();
                                new Runnable() {
                                    public void run() {
                                        Box<String> local = new Box<String>();
                                        local.node = new ConcreteNode();
                                        if (local.node.linked() != local.node) throw new AssertionError();
                                    }
                                }.run();
                            }
                        }
                        """);
        Path consumerClasses = root.resolve("downstream").resolve("classes");
        JypeTestCompiler.compileTo(consumer, consumerClasses, List.of(libraryJar, apiJar), STRICT).assertSuccess();
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{
                consumerClasses.toUri().toURL(), apiJar.toUri().toURL(), libraryJar.toUri().toURL()},
                getClass().getClassLoader())) {
            loader.loadClass("downstream.Consumer").getMethod("main", String[].class).invoke(null, (Object) new String[0]);
        }
        Path docs = root.resolve("html");
        Path docSources = libraryClasses.getParent().resolve("jype-javadoc-src");
        try (var sources = Files.walk(docSources);
             var manager = ToolProvider.getSystemDocumentationTool().getStandardFileManager(null, null, null)) {
            StringWriter log = new StringWriter();
            var task = ToolProvider.getSystemDocumentationTool().getTask(log, manager, null, null,
                    List.of("-quiet", "-Xdoclint:all,-missing", "-d", docs.toString()),
                    manager.getJavaFileObjectsFromPaths(sources.filter(p -> p.toString().endsWith(".java")).toList()));
            assertTrue(task.call(), log::toString);
        }
        String html = Files.readString(docs.resolve("library").resolve("Facade.html"));
        assertTrue(html.contains("Original facade comments survive."));
        assertTrue(html.contains("ENTITY.html"), html);
        assertTrue(html.contains("getNodes()"));
        String renderedText = html.replaceAll("<[^>]*>", "").replaceAll("\\s+", " ");
        assertTrue(renderedText.contains("List&lt;ENTITY&gt;"), "Rendered collection must use the original alias");
        assertTrue(renderedText.contains("ENTITY getNode()"), "Rendered return must not expand the caller binding");
        assertFalse(html.contains("TYPE_0"));
        assertFalse(html.contains("BaseNode&lt;"));
        assertTrue(html.contains("Number"), "Ordinary method bound must remain documented");
        String boxHtml = Files.readString(docs.resolve("library").resolve("Box.html"));
        assertTrue(boxHtml.contains("CharSequence"));
        assertFalse(boxHtml.contains("TYPE_0"));
        String declaredHtml = Files.readString(docs.resolve("library").resolve("Declared.html"));
        assertTrue(declaredHtml.contains("ordinary text parameter"));
        assertFalse(declaredHtml.contains("technical entity slot"));
        assertFalse(Files.exists(libraryClasses.resolve("library").resolve("ENTITY.class")),
                "Alias declarations must never enter runtime output");
        Path apiDocs = root.resolve("api-html");
        render(apiClasses.getParent().resolve("jype-javadoc-src"), apiDocs);
        String apiHtml = Files.readString(apiDocs.resolve("api").resolve("LibCore.html"));
        assertTrue(apiHtml.contains("Original API comment."));
        assertTrue(apiHtml.contains("ENTITY.html"));
        assertFalse(apiHtml.contains("TYPE_0"), "Compiled ancestor signatures must also be hidden");
    }

    @Test
    void ordinaryRawUncheckedAndWrongArgumentsStillFail() throws Exception {
        for (String body : List.of(
                "java.util.List values;",
                "void run() { Object x = new java.util.ArrayList<String>(); java.util.List<String> y = (java.util.List<String>) x; }",
                "java.util.List<String, Integer> values;")) {
            JypeTestCompiler.compileWithOptions(Map.of("Ordinary.java", "class Ordinary { " + body + " }"), STRICT)
                    .assertFailure();
        }
    }

    @Test
    void classOnlyStaticAliasesRetainPublicMemberOrigins(@TempDir Path root) throws Exception {
        Map<String, String> sources = Map.of(
                "storage/package-info.java", """
                        @fr.thegamerfarmer.jype.annotations.TypeSystem({
                          @fr.thegamerfarmer.jype.annotations.TypeDef(name="ENTITY",bound=Number.class)
                        }) package storage;
                        """,
                "storage/State.java", """
                        package storage;
                        public class State {
                            /** Original shared alias field. */
                            public static ENTITY entity;
                            @lombok.Getter @lombok.Setter public static ENTITY accessor;
                            public static ENTITY current() { return entity; }
                        }
                        """,
                "storage/Utility.java", "package storage; public class Utility<T> {}");
        Path classes = root.resolve("classes");
        JypeTestCompiler.compileTo(sources, classes, List.of(), "-Xplugin:Jype", "-processor",
                "fr.thegamerfarmer.jype.JypeProcessorShim").assertSuccess();
        var data = readClass(classes.resolve("storage").resolve("State.class"));
        assertEquals(List.of("ENTITY"), data.parameters());
        assertTrue(readClass(classes.resolve("storage").resolve("Utility.class")).publicView().managedParameters().isEmpty());
        assertNotNull(data.publicView());
        assertTrue(data.memberViews().stream().anyMatch(member -> member.generated()
                && member.name().startsWith("$jype$view$")));
        assertEquals("ENTITY", data.memberViews().stream().filter(member -> member.name().equals("entity"))
                .findFirst().orElseThrow().sourceType());
        var accessor = data.memberViews().stream().filter(member -> member.name().equals("getAccessor"))
                .findFirst().orElseThrow();
        assertEquals("ENTITY", accessor.sourceType());
        assertTrue(accessor.declaredParameters().isEmpty());
        assertFalse(accessor.generated(), "Lombok-compatible business accessors are not hidden helpers");
        Path docs = root.resolve("html");
        render(root.resolve("jype-javadoc-src"), docs);
        String html = Files.readString(docs.resolve("storage").resolve("State.html"));
        assertTrue(html.contains("Original shared alias field."));
        assertTrue(html.contains("ENTITY.html"));
        assertFalse(html.contains("$jype$"));
    }

    @Test
    void metadataDefaultsAndInvalidVersionsAreReadConservatively(@TempDir Path root) throws Exception {
        Path classes = root.resolve("classes");
        JypeTestCompiler.compileTo(Map.of(
                "metadata/Default.java", """
                        package metadata;
                        @fr.thegamerfarmer.jype.annotations.JypeClass(managedParameters={"ENTITY"},typeSystem="metadata")
                        public class Default<ENTITY> {
                            @fr.thegamerfarmer.jype.annotations.JypeMember(sourceType="ENTITY")
                            public ENTITY value;
                        }
                        """,
                "metadata/Future.java", """
                        package metadata;
                        @fr.thegamerfarmer.jype.annotations.JypeClass(version=2,managedParameters={"ENTITY"},typeSystem="metadata")
                        public class Future<ENTITY> {}
                        """,
                "metadata/WrongOrder.java", """
                        package metadata;
                        @fr.thegamerfarmer.jype.annotations.JypeClass(managedParameters={"SECOND","FIRST"},typeSystem="metadata")
                        public class WrongOrder<FIRST,SECOND> {}
                        """,
                "metadata/FutureMember.java", """
                        package metadata;
                        public class FutureMember {
                            @fr.thegamerfarmer.jype.annotations.JypeMember(version=2) public String value;
                        }
                        """), classes, List.of(), "-proc:none").assertSuccess();
        var defaults = readClass(classes.resolve("metadata").resolve("Default.class"));
        assertTrue(defaults.publicView().declaredParameters().isEmpty());
        assertFalse(defaults.memberViews().get(0).generated());
        for (String name : List.of("Future", "WrongOrder", "FutureMember")) {
            assertThrows(java.io.IOException.class, () -> readClass(classes.resolve("metadata").resolve(name + ".class")));
        }
    }

    private static TypeSystemClassFileReader.ClassData readClass(Path path) throws Exception {
        return TypeSystemClassFileReader.readClass(new SimpleJavaFileObject(path.toUri(), JavaFileObject.Kind.CLASS) {
            @Override public InputStream openInputStream() throws java.io.IOException { return Files.newInputStream(path); }
        });
    }

    private static void render(Path sources, Path output) throws Exception {
        try (var files = Files.walk(sources);
             var manager = ToolProvider.getSystemDocumentationTool().getStandardFileManager(null, null, null)) {
            StringWriter log = new StringWriter();
            assertTrue(ToolProvider.getSystemDocumentationTool().getTask(log, manager, null, null,
                    List.of("-quiet", "-Xdoclint:all,-missing", "-d", output.toString()),
                    manager.getJavaFileObjectsFromPaths(files.filter(p -> p.toString().endsWith(".java")).toList()))
                    .call(), log::toString);
        }
    }
}
