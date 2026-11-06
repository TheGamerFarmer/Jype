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
                "@fr.thegamerfarmer.jype.annotations.TypeDef(name=\"PLAYER\",bound=LibPlayer.class)");
        for (int i = 0; i < 19; i++) definitions.append(
                ",@fr.thegamerfarmer.jype.annotations.TypeDef(name=\"TYPE_" + i + "\",bound=String.class)");
        library.put("library/package-info.java", "@fr.thegamerfarmer.jype.annotations.TypeSystem({"
                + definitions + "}) package library;");
        library.put("library/LibPlayer.java", """
                package library;
                /** Recursive player contract. */
                public abstract class LibPlayer {
                    /** Returns the original player alias. */
                    public abstract PLAYER friend();
                }
                """);
        library.put("library/Service.java", """
                package library;
                import java.util.List;
                /** Original service comments survive. */
                public abstract class Service {
                    /** Returns a player. */
                    public abstract PLAYER getPlayer();
                    /** Returns players. */
                    public abstract List<PLAYER> getPlayers();
                    /** Ordinary method generic shadows the alias. */
                    public <PLAYER extends Number> PLAYER identity(PLAYER value) { return value; }
                    public PLAYER echo(PLAYER input) { return input; }
                    public java.util.List<PLAYER> echo(java.util.List<PLAYER> input) { return input; }
                    /** Public static alias. */
                    public static PLAYER current() { return null; }
                }
                """);
        library.put("library/Box.java", """
                package library;
                /** Ordinary generic stays visible. */
                public class Box<T extends CharSequence> {
                    public PLAYER player;
                    public T value;
                    public class Nested { public PLAYER player; }
                }
                """);
        library.put("library/Declared.java", """
                package library;
                /**
                 * Original declared generic comments.
                 * @param <PLAYER> technical player slot
                 * @param <T> ordinary text parameter
                 */
                public class Declared<PLAYER, T extends CharSequence> {
                    public PLAYER player;
                }
                """);
        Path libraryClasses = root.resolve("library").resolve("classes");
        JypeTestCompiler.compileTo(library, libraryClasses, List.of(), STRICT).assertSuccess();
        Path libraryJar = JypeTestCompiler.jar(libraryClasses, root.resolve("library.jar"));
        var metadata = readClass(libraryClasses.resolve("library").resolve("Box.class"));
        assertNotNull(metadata.publicView());
        assertEquals(List.of("T"), metadata.publicView().declaredParameters());
        assertEquals(List.of("PLAYER", "T"), readClass(libraryClasses.resolve("library").resolve("Declared.class"))
                .publicView().declaredParameters(), "Capture must precede both Enter and processor injection");
        assertEquals("library", metadata.publicView().typeSystem());
        assertEquals(20, metadata.publicView().managedParameters().size());
        assertEquals(21, metadata.parameters().size(), "JVM generic Signature must remain intact");
        var serviceMetadata = readClass(libraryClasses.resolve("library").resolve("Service.class"));
        assertEquals("PLAYER", serviceMetadata.memberViews().stream()
                .filter(member -> member.name().equals("current")).findFirst().orElseThrow().sourceType());
        assertTrue(serviceMetadata.memberViews().stream().filter(member -> member.name().equals("current"))
                .findFirst().orElseThrow().declaredParameters().isEmpty());
        assertEquals(List.of("PLAYER"), serviceMetadata.memberViews().stream()
                .filter(member -> member.name().equals("identity")).findFirst().orElseThrow().declaredParameters());
        assertEquals(Set.of("PLAYER", "java.util.List<PLAYER>"), serviceMetadata.memberViews().stream()
                .filter(member -> member.name().equals("echo")).map(TypeSystemClassFileReader.MemberView::sourceType)
                .collect(java.util.stream.Collectors.toSet()), "Overloads sharing parameter names need distinct origins");
        assertTrue(readClass(libraryClasses.resolve("library").resolve("Box$Nested.class"))
                .publicView().declaredParameters().isEmpty());

        Map<String, String> api = Map.of(
                "api/package-info.java", """
                        @fr.thegamerfarmer.jype.annotations.TypeSystem(inherit="library") package api;
                        """,
                "api/APIUHC.java", """
                        package api;
                        /** Transitive compiled service. */
                        public abstract class APIUHC extends library.Service {
                            /** Original API comment. */
                            public abstract PLAYER getPlayer();
                        }
                        """,
                "api/Holder.java", """
                        package api;
                        /** A template that only mentions a compiled service, not an alias. */
                        public abstract class Holder {
                            public library.Service service;
                            public library.Box<String> box;
                            public library.Service use(library.Service input) {
                                library.Service local = input;
                                return local;
                            }
                            public static library.Service echo(library.Service input) {
                                library.Service local = input;
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
                "package external; public abstract class More extends api.APIUHC {}"),
                inheritedClasses, List.of(libraryJar, apiJar), STRICT).assertSuccess();
        assertEquals("api", readClass(inheritedClasses.resolve("external").resolve("More.class"))
                .publicView().typeSystem());
        Map<String, String> consumer = Map.of(
                "battle/package-info.java", """
                        @fr.thegamerfarmer.jype.annotations.TypeSystem(inherit="api",overrides={
                          @fr.thegamerfarmer.jype.annotations.TypeDef(name="PLAYER",bound=BattlePlayer.class)
                        }) package battle;
                        """,
                "battle/BattlePlayer.java", """
                        package battle;
                        public class BattlePlayer extends library.LibPlayer {
                            public BattlePlayer friend() { return this; }
                        }
                        """,
                "battle/Consumer.java", """
                        package battle;
                        import api.APIUHC;
                        import library.Box;
                        import java.util.List;
                        public class Consumer {
                            public APIUHC service;
                            public Box<String> box;
                            public APIUHC service(APIUHC value) {
                                APIUHC local = value;
                                BattlePlayer player = local.getPlayer();
                                List<BattlePlayer> players = local.getPlayers();
                                Box<String> localBox = new Box<String>();
                                localBox.player = player;
                                box = (Box<String>) localBox;
                                return local;
                            }
                            public static void main(String[] args) {
                                Box<String> value = new Box<String>();
                                value.player = new BattlePlayer();
                                if (value.player.friend() != value.player) throw new AssertionError();
                                if (library.Service.current() != null) throw new AssertionError();
                                new Runnable() {
                                    public void run() {
                                        Box<String> local = new Box<String>();
                                        local.player = new BattlePlayer();
                                        if (local.player.friend() != local.player) throw new AssertionError();
                                    }
                                }.run();
                            }
                        }
                        """);
        Path consumerClasses = root.resolve("consumer").resolve("classes");
        JypeTestCompiler.compileTo(consumer, consumerClasses, List.of(libraryJar, apiJar), STRICT).assertSuccess();
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{
                consumerClasses.toUri().toURL(), apiJar.toUri().toURL(), libraryJar.toUri().toURL()},
                getClass().getClassLoader())) {
            loader.loadClass("battle.Consumer").getMethod("main", String[].class).invoke(null, (Object) new String[0]);
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
        String html = Files.readString(docs.resolve("library").resolve("Service.html"));
        assertTrue(html.contains("Original service comments survive."));
        assertTrue(html.contains("PLAYER.html"), html);
        assertTrue(html.contains("getPlayers()"));
        String renderedText = html.replaceAll("<[^>]*>", "").replaceAll("\\s+", " ");
        assertTrue(renderedText.contains("List&lt;PLAYER&gt;"), "Rendered collection must use the original alias");
        assertTrue(renderedText.contains("PLAYER getPlayer()"), "Rendered return must not expand the caller binding");
        assertFalse(html.contains("TYPE_0"));
        assertFalse(html.contains("LibPlayer&lt;"));
        assertTrue(html.contains("Number"), "Ordinary method bound must remain documented");
        String boxHtml = Files.readString(docs.resolve("library").resolve("Box.html"));
        assertTrue(boxHtml.contains("CharSequence"));
        assertFalse(boxHtml.contains("TYPE_0"));
        String declaredHtml = Files.readString(docs.resolve("library").resolve("Declared.html"));
        assertTrue(declaredHtml.contains("ordinary text parameter"));
        assertFalse(declaredHtml.contains("technical player slot"));
        assertFalse(Files.exists(libraryClasses.resolve("library").resolve("PLAYER.class")),
                "Alias declarations must never enter runtime output");
        Path apiDocs = root.resolve("api-html");
        render(apiClasses.getParent().resolve("jype-javadoc-src"), apiDocs);
        String apiHtml = Files.readString(apiDocs.resolve("api").resolve("APIUHC.html"));
        assertTrue(apiHtml.contains("Original API comment."));
        assertTrue(apiHtml.contains("PLAYER.html"));
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
                          @fr.thegamerfarmer.jype.annotations.TypeDef(name="PLAYER",bound=Number.class)
                        }) package storage;
                        """,
                "storage/State.java", """
                        package storage;
                        public class State {
                            /** Original shared alias field. */
                            public static PLAYER player;
                            @lombok.Getter @lombok.Setter public static PLAYER accessor;
                            public static PLAYER current() { return player; }
                        }
                        """,
                "storage/Utility.java", "package storage; public class Utility<T> {}");
        Path classes = root.resolve("classes");
        JypeTestCompiler.compileTo(sources, classes, List.of(), "-Xplugin:Jype", "-processor",
                "fr.thegamerfarmer.jype.JypeProcessorShim").assertSuccess();
        var data = readClass(classes.resolve("storage").resolve("State.class"));
        assertEquals(List.of("PLAYER"), data.parameters());
        assertTrue(readClass(classes.resolve("storage").resolve("Utility.class")).publicView().managedParameters().isEmpty());
        assertNotNull(data.publicView());
        assertTrue(data.memberViews().stream().anyMatch(member -> member.generated()
                && member.name().startsWith("$jype$view$")));
        assertEquals("PLAYER", data.memberViews().stream().filter(member -> member.name().equals("player"))
                .findFirst().orElseThrow().sourceType());
        var accessor = data.memberViews().stream().filter(member -> member.name().equals("getAccessor"))
                .findFirst().orElseThrow();
        assertEquals("PLAYER", accessor.sourceType());
        assertTrue(accessor.declaredParameters().isEmpty());
        assertFalse(accessor.generated(), "Lombok-compatible business accessors are not hidden helpers");
        Path docs = root.resolve("html");
        render(root.resolve("jype-javadoc-src"), docs);
        String html = Files.readString(docs.resolve("storage").resolve("State.html"));
        assertTrue(html.contains("Original shared alias field."));
        assertTrue(html.contains("PLAYER.html"));
        assertFalse(html.contains("$jype$"));
    }

    @Test
    void metadataDefaultsAndInvalidVersionsAreReadConservatively(@TempDir Path root) throws Exception {
        Path classes = root.resolve("classes");
        JypeTestCompiler.compileTo(Map.of(
                "metadata/Default.java", """
                        package metadata;
                        @fr.thegamerfarmer.jype.annotations.JypeClass(managedParameters={"PLAYER"},typeSystem="metadata")
                        public class Default<PLAYER> {
                            @fr.thegamerfarmer.jype.annotations.JypeMember(sourceType="PLAYER")
                            public PLAYER value;
                        }
                        """,
                "metadata/Future.java", """
                        package metadata;
                        @fr.thegamerfarmer.jype.annotations.JypeClass(version=2,managedParameters={"PLAYER"},typeSystem="metadata")
                        public class Future<PLAYER> {}
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
