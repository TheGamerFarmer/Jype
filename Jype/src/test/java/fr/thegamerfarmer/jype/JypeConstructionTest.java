package fr.thegamerfarmer.jype;

import fr.thegamerfarmer.jype.util.JypeTestCompiler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code new ALIAS(...)} creates the class bound by the most specific type system of the running
 * program, including in separately compiled libraries, static methods and constructor references.
 */
class JypeConstructionTest {
    private static final String[] STRICT = {"-Xplugin:Jype", "-processor",
            "fr.thegamerfarmer.jype.JypeProcessorShim", "-Xlint:rawtypes,unchecked", "-Werror"};
    /** A singleton's static alias field intentionally triggers the shared-storage warning. */
    private static final String[] LENIENT = Arrays.copyOf(STRICT, STRICT.length - 1);

    private static final Map<String, String> API = Map.of(
            "api/package-info.java", """
                    @fr.thegamerfarmer.jype.annotations.TypeSystem({
                        @fr.thegamerfarmer.jype.annotations.TypeDef(name="ENTITY", bound=ApiEntity.class),
                        @fr.thegamerfarmer.jype.annotations.TypeDef(name="LINK", bound=Link.class),
                        @fr.thegamerfarmer.jype.annotations.TypeDef(name="SHAPE", bound=Shape.class),
                        @fr.thegamerfarmer.jype.annotations.TypeDef(name="CLUSTER", bound=Cluster.class)
                    }) package api;
                    """,
            "api/ApiEntity.java", """
                    package api;
                    public class ApiEntity {
                        public String name() { return "api"; }
                        public CLUSTER cluster() { return new CLUSTER(); }
                    }
                    """,
            "api/Link.java", """
                    package api;
                    public class Link {
                        public final ENTITY entity;
                        public Link(ENTITY entity) { this.entity = entity; }
                        public String kind() { return "api"; }
                    }
                    """,
            "api/Shape.java", """
                    package api;
                    public abstract class Shape { public abstract String title(); }
                    """,
            "api/Cluster.java", """
                    package api;
                    import java.util.function.Supplier;
                    public class Cluster {
                        private static final Supplier<Object> SHARED = () -> new ENTITY();
                        public ENTITY create() { return new ENTITY(); }
                        public LINK link(ENTITY entity) { return new LINK(entity); }
                        public String nested() { return new ENTITY().name(); }
                        public SHAPE shape() { return new SHAPE(); }
                        public static ENTITY fromStatic() { return new ENTITY(); }
                        public static Object shared() { return SHARED.get(); }
                        public Supplier<ENTITY> supplier() { return ENTITY::new; }
                    }
                    """);

    private static final Map<String, String> GAME = Map.of(
            "impl/package-info.java", """
                    @fr.thegamerfarmer.jype.annotations.TypeSystem(inherit="api", overrides={
                        @fr.thegamerfarmer.jype.annotations.TypeDef(name="ENTITY", bound=AppEntity.class),
                        @fr.thegamerfarmer.jype.annotations.TypeDef(name="LINK", bound=AppLink.class),
                        @fr.thegamerfarmer.jype.annotations.TypeDef(name="SHAPE", bound=AppShape.class)
                    }) package impl;
                    """,
            "impl/AppEntity.java", """
                    package impl;
                    public class AppEntity extends api.ApiEntity { public String name() { return "impl"; } }
                    """,
            "impl/AppLink.java", """
                    package impl;
                    public class AppLink extends api.Link {
                        public AppLink(AppEntity entity) { super(entity); }
                        public String kind() { return "impl"; }
                    }
                    """,
            "impl/AppShape.java", """
                    package impl;
                    public class AppShape extends api.Shape { public String title() { return "impl"; } }
                    """,
            "impl/Builder.java", """
                    package impl;
                    public class Builder {
                        public String own() { ENTITY entity = new ENTITY(); return entity.name(); }
                    }
                    """);

    private Path root;

    @BeforeEach
    void workspace() throws Exception {
        root = Files.createTempDirectory(Files.createDirectories(Path.of("target", "test-workspaces")), "jype-new-");
    }

    @AfterEach
    void clean() throws Exception {
        try (var files = Files.walk(root)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    @Test
    void libraryAloneCreatesItsOwnBindings() throws Exception {
        Path api = compile(API, "api");
        try (URLClassLoader loader = loader(api)) {
            Object cluster = loader.loadClass("api.Cluster").getConstructor().newInstance();
            assertEquals("api.ApiEntity", call(cluster, "create").getClass().getName());
            assertEquals("api", call(cluster, "nested"));
            assertEquals("api.Link", call(cluster, "link", call(cluster, "create")).getClass().getName());
            assertEquals("api.ApiEntity", staticCall(loader, "api.Cluster", "fromStatic").getClass().getName());
            assertEquals("api.ApiEntity", staticCall(loader, "api.Cluster", "shared").getClass().getName());
            assertEquals("api.ApiEntity", ((Supplier<?>) call(cluster, "supplier")).get().getClass().getName());
            var failure = assertThrows(IllegalStateException.class, () -> call(cluster, "shape"));
            assertTrue(failure.getMessage().contains("SHAPE is bound to api.Shape"), failure.getMessage());
        }
        Path documentation = api.resolveSibling("jype-javadoc-src");
        try (var files = Files.walk(documentation)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().startsWith("Jype$")),
                    "Generated factories are not documented");
        }
        assertTrue(Files.readString(documentation.resolve("api").resolve("Cluster.java"))
                .contains("return new ENTITY();"), "Documentation copies keep the written creation");
    }

    @Test
    void consumerBindingsApplyInsideTheCompiledLibrary() throws Exception {
        Path api = compile(API, "api");
        Path impl = compile(GAME, "impl", api);
        try (URLClassLoader loader = loader(impl, api)) {
            // Loading any consumer class installs its bindings before the library creates objects.
            loader.loadClass("impl.AppEntity").getConstructor().newInstance();
            Object cluster = loader.loadClass("api.Cluster").getConstructor().newInstance();
            Object entity = call(cluster, "create");
            assertEquals("impl.AppEntity", entity.getClass().getName());
            assertEquals("impl", call(cluster, "nested"));
            assertEquals("impl.AppLink", call(cluster, "link", entity).getClass().getName());
            assertEquals("impl.AppShape", call(cluster, "shape").getClass().getName());
            assertEquals("impl.AppEntity", staticCall(loader, "api.Cluster", "fromStatic").getClass().getName());
            assertEquals("impl.AppEntity", staticCall(loader, "api.Cluster", "shared").getClass().getName());
            assertEquals("impl.AppEntity", ((Supplier<?>) call(cluster, "supplier")).get().getClass().getName());
            // CLUSTER is not overridden: the library's cluster still creates the consumer's entities.
            Object libraryCluster = call(entity, "cluster");
            assertEquals("api.Cluster", libraryCluster.getClass().getName());
            assertEquals("impl.AppEntity", call(libraryCluster, "create").getClass().getName());
            Object spawner = loader.loadClass("impl.Builder").getConstructor().newInstance();
            assertEquals("impl", call(spawner, "own"));
        }
    }

    @Test
    void deepestTypeSystemWinsWhateverTheLoadingOrder() throws Exception {
        Path base = compile(Map.of(
                "base/package-info.java", """
                        @fr.thegamerfarmer.jype.annotations.TypeSystem({
                            @fr.thegamerfarmer.jype.annotations.TypeDef(name="ENTITY", bound=BaseEntity.class),
                            @fr.thegamerfarmer.jype.annotations.TypeDef(name="SHAPE", bound=BaseShape.class)
                        }) package base;
                        """,
                "base/BaseEntity.java", "package base; public class BaseEntity { public String name() { return \"base\"; } }",
                "base/BaseShape.java", "package base; public class BaseShape { public String title() { return \"base\"; } }",
                "base/Factory.java", """
                        package base;
                        public class Factory {
                            public ENTITY entity() { return new ENTITY(); }
                            public SHAPE shape() { return new SHAPE(); }
                        }
                        """), "base");
        Path middle = compile(Map.of(
                "middle/package-info.java", """
                        @fr.thegamerfarmer.jype.annotations.TypeSystem(inherit="base", overrides={
                            @fr.thegamerfarmer.jype.annotations.TypeDef(name="ENTITY", bound=MiddleEntity.class),
                            @fr.thegamerfarmer.jype.annotations.TypeDef(name="SHAPE", bound=MiddleShape.class)
                        }) package middle;
                        """,
                "middle/MiddleEntity.java", """
                        package middle;
                        public class MiddleEntity extends base.BaseEntity { public String name() { return "middle"; } }
                        """,
                "middle/MiddleShape.java", """
                        package middle;
                        public class MiddleShape extends base.BaseShape { public String title() { return "middle"; } }
                        """,
                "middle/Builder.java", """
                        package middle;
                        public class Builder {
                            public String own() { ENTITY entity = new ENTITY(); return entity.name(); }
                        }
                        """), "middle", base);
        Path deep = compile(Map.of(
                "deep/package-info.java", """
                        @fr.thegamerfarmer.jype.annotations.TypeSystem(inherit="middle", overrides={
                            @fr.thegamerfarmer.jype.annotations.TypeDef(name="ENTITY", bound=DeepEntity.class)
                        }) package deep;
                        """,
                "deep/DeepEntity.java", """
                        package deep;
                        public class DeepEntity extends middle.MiddleEntity { public String name() { return "deep"; } }
                        """), "deep", base, middle);
        try (URLClassLoader loader = loader(deep, middle, base)) {
            loader.loadClass("deep.DeepEntity").getConstructor().newInstance();
            // Initializing the intermediate type system afterwards must not undo the deeper binding.
            loader.loadClass("middle.MiddleShape").getConstructor().newInstance();
            Object factory = loader.loadClass("base.Factory").getConstructor().newInstance();
            assertEquals("deep", call(call(factory, "entity"), "name"));
            assertEquals("middle", call(call(factory, "shape"), "title"), "An override that is not redefined is kept");
            Object spawner = loader.loadClass("middle.Builder").getConstructor().newInstance();
            assertEquals("deep", call(spawner, "own"), "The intermediate system's own factory is also replaced");
        }
    }

    @Test
    void singleCompilationInstallsSourceFactories() throws Exception {
        Map<String, String> sources = new HashMap<>(API);
        sources.putAll(GAME);
        Path classes = compile(sources, "single");
        try (URLClassLoader loader = loader(classes)) {
            loader.loadClass("impl.AppShape").getConstructor().newInstance();
            Object cluster = loader.loadClass("api.Cluster").getConstructor().newInstance();
            assertEquals("impl.AppEntity", call(cluster, "create").getClass().getName());
            assertEquals("impl.AppLink", call(cluster, "link", call(cluster, "create")).getClass().getName());
        }
    }

    @Test
    void constructorShapesFollowTheBindings() throws Exception {
        Path shapes = compile(Map.of(
                "shapes/package-info.java", """
                        @fr.thegamerfarmer.jype.annotations.TypeSystem({
                            @fr.thegamerfarmer.jype.annotations.TypeDef(name="KIND", bound=Kind.class),
                            @fr.thegamerfarmer.jype.annotations.TypeDef(name="BOX", bound=Box.class),
                            @fr.thegamerfarmer.jype.annotations.TypeDef(name="SHAPE", bound=Shape.class)
                        }) package shapes;
                        """,
                "shapes/Kind.java", "package shapes; public interface Kind { String label(); }",
                "shapes/Box.java", """
                        package shapes;
                        public class Box {
                            public final String content;
                            public Box(int size) { content = "size" + size; }
                            public Box(String... names) { content = String.join("+", names); }
                            public Box(java.util.List<String> names) { content = "list" + names; }
                            public String content() { return content; }
                        }
                        """,
                "shapes/Shape.java", "package shapes; public abstract class Shape { public Shape(String title) {} }",
                "shapes/Factory.java", """
                        package shapes;
                        public class Factory {
                            public KIND kind() { return new KIND(); }
                            public KIND kind(int value) { return new KIND(value); }
                            public BOX sized() { return new BOX(3); }
                            public BOX named() { return new BOX("a", "b"); }
                            public BOX listed() { return new BOX(java.util.List.of("c")); }
                            public SHAPE shape() { return new SHAPE("chief"); }
                        }
                        """), "shapes");
        Map<String, String> consumer = Map.of(
                "client/package-info.java", """
                        @fr.thegamerfarmer.jype.annotations.TypeSystem(inherit="shapes", overrides={
                            @fr.thegamerfarmer.jype.annotations.TypeDef(name="KIND", bound=ClientKind.class),
                            @fr.thegamerfarmer.jype.annotations.TypeDef(name="BOX", bound=ClientBox.class),
                            @fr.thegamerfarmer.jype.annotations.TypeDef(name="SHAPE", bound=ClientShape.class)
                        }) package client;
                        """,
                "client/ClientKind.java", """
                        package client;
                        public class ClientKind implements shapes.Kind {
                            private final String label;
                            public ClientKind() { label = "client"; }
                            public ClientKind(int value) { label = "client" + value; }
                            public String label() { return label; }
                        }
                        """,
                "client/ClientBox.java", """
                        package client;
                        public class ClientBox extends shapes.Box {
                            public ClientBox(int size) { super(size * 10); }
                            public ClientBox(String... names) { super(names); }
                        }
                        """,
                "client/ClientShape.java", """
                        package client;
                        public abstract class ClientShape extends shapes.Shape { public ClientShape(String title) { super(title); } }
                        """,
                "client/Mode.java", "package client; public enum Mode { ON, OFF; static final int COUNT = 2; }",
                "client/Marker.java", "package client; public interface Marker {}");
        Path client = root.resolve("client").resolve("classes");
        var result = JypeTestCompiler.compileTo(consumer, client, List.of(shapes),
                "-Xplugin:Jype", "-processor", "fr.thegamerfarmer.jype.JypeProcessorShim");
        result.assertSuccess();
        assertTrue(result.diagnostics().stream().anyMatch(diagnostic -> diagnostic.getKind() == javax.tools.Diagnostic.Kind.WARNING
                        && diagnostic.getMessage(null).contains("client.ClientBox declares no constructor compatible with BOX(java.util.List)")),
                result.diagnostics().toString());
        try (URLClassLoader loader = loader(client, shapes)) {
            assertEquals(2, loader.loadClass("client.Mode").getEnumConstants().length);
            Object factory = loader.loadClass("shapes.Factory").getConstructor().newInstance();
            assertEquals("client", call(call(factory, "kind"), "label"));
            assertEquals("client7", call(call(factory, "kind", 7), "label"));
            assertEquals("size30", call(call(factory, "sized"), "content"));
            assertEquals("a+b", call(call(factory, "named"), "content"));
            assertEquals("client.ClientBox", call(factory, "named").getClass().getName());
            var missing = assertThrows(IllegalStateException.class, () -> call(factory, "listed"));
            assertTrue(missing.getMessage().contains("BOX(java.util.List)"), missing.getMessage());
            var abstractShape = assertThrows(IllegalStateException.class, () -> call(factory, "shape"));
            assertTrue(abstractShape.getMessage().contains("SHAPE is bound to client.ClientShape"), abstractShape.getMessage());
        }
    }

    @Test
    void classInheritingACompiledSystemUsesItsFactory() throws Exception {
        Path api = compile(API, "api");
        Path other = compile(Map.of("other/Clone.java", """
                package other;
                public class Clone extends api.ApiEntity {
                    public String twin() { return new ENTITY().name(); }
                }
                """), "other", api);
        try (URLClassLoader loader = loader(other, api)) {
            assertEquals("api", call(loader.loadClass("other.Clone").getConstructor().newInstance(), "twin"));
        }
        Map<String, String> sources = new HashMap<>(API);
        sources.put("api/Outer.java", """
                package api;
                public class Outer {
                    public class Inner {}
                    public Object qualified() { return this.new ENTITY(); }
                }
                """);
        var result = JypeTestCompiler.compileTo(sources, root.resolve("qualified"), List.of(), STRICT);
        result.assertFailure();
        assertTrue(result.hasError("Qualified creation of managed type ENTITY is not supported"),
                result.diagnostics().toString());
    }

    @Test
    void consumerMustDeclareTheConstructorsUsedByTheLibrary() throws Exception {
        Path api = compile(API, "api");
        Map<String, String> sources = new HashMap<>(GAME);
        sources.put("impl/AppLink.java", """
                package impl;
                public class AppLink extends api.Link {
                    public AppLink() { super(null); }
                }
                """);
        var result = JypeTestCompiler.compileTo(sources, root.resolve("broken"), List.of(api), STRICT);
        result.assertFailure();
        assertTrue(result.hasError("impl.AppLink declares no constructor compatible with LINK(api.ApiEntity)"),
                result.diagnostics().toString());
    }

    @Test
    void unsupportedCreationsAreReported() throws Exception {
        Map<String, String> sources = new HashMap<>(API);
        sources.put("api/Invalid.java", """
                package api;
                public class Invalid {
                    public Object anonymous() { return new ENTITY() {}; }
                    public LINK arity() { return new LINK(); }
                    public <ENTITY> Object shadowed(ENTITY value) { return value; }
                }
                """);
        var result = JypeTestCompiler.compileTo(sources, root.resolve("invalid"), List.of(), STRICT);
        result.assertFailure();
        assertTrue(result.hasError("Anonymous subclasses of managed type ENTITY are not supported"),
                result.diagnostics().toString());
        assertTrue(result.hasError("LINK is bound to api.Link, which has no constructor with 0 parameter(s)"),
                result.diagnostics().toString());
    }

    private static final Map<String, String> SINGLETONS = Map.of(
            "single/package-info.java", """
                    @fr.thegamerfarmer.jype.annotations.TypeSystem({
                        @fr.thegamerfarmer.jype.annotations.TypeDef(name="SERVICE", bound=single.core.Service.class),
                        @fr.thegamerfarmer.jype.annotations.TypeDef(name="SECRET", bound=single.core.Secret.class)
                    }) package single;
                    """,
            "single/core/Service.java", """
                    package single.core;
                    public class Service {
                        private static SERVICE instance;
                        public static SERVICE shared() {
                            if (instance == null) instance = new SERVICE();
                            return instance;
                        }
                        protected Service() {}
                        public String name() { return "single"; }
                    }
                    """,
            "single/core/Secret.java", """
                    package single.core;
                    public class Secret {
                        private final String value;
                        private Secret(String value) {
                            if (value.isEmpty()) throw new IllegalArgumentException("empty secret");
                            this.value = value;
                        }
                        public static SECRET of(String value) { return new SECRET(value); }
                        public static java.util.function.Function<String, SECRET> maker() { return SECRET::new; }
                        public String value() { return value; }
                    }
                    """,
            "single/core/Locator.java", """
                    package single.core;
                    public class Locator { public static Object fresh() { return new SERVICE(); } }
                    """);

    @Test
    void singletonsCreateThroughNonPublicConstructors() throws Exception {
        Path single = compile(SINGLETONS, "single", LENIENT);
        try (URLClassLoader loader = loader(single)) {
            Object service = staticCall(loader, "single.core.Service", "shared");
            assertEquals("single.core.Service", service.getClass().getName());
            assertSame(service, staticCall(loader, "single.core.Service", "shared"));
            assertEquals("single.core.Service", staticCall(loader, "single.core.Locator", "fresh").getClass().getName(),
                    "A protected constructor is reachable from its own package");
            var secret = loader.loadClass("single.core.Secret").getMethod("of", String.class);
            assertEquals("s3", call(secret.invoke(null, "s3"), "value"));
            var failure = assertThrows(InvocationTargetException.class, () -> secret.invoke(null, ""));
            assertEquals(IllegalArgumentException.class, failure.getCause().getClass(),
                    "The constructor's exception propagates unchanged");
            @SuppressWarnings("unchecked")
            var maker = (java.util.function.Function<String, ?>) staticCall(loader, "single.core.Secret", "maker");
            assertEquals("ref", call(maker.apply("ref"), "value"));
        }
        Path multi = compile(Map.of(
                "multi/package-info.java", """
                        @fr.thegamerfarmer.jype.annotations.TypeSystem(inherit="single", overrides={
                            @fr.thegamerfarmer.jype.annotations.TypeDef(name="SERVICE", bound=multi.core.ImplService.class)
                        }) package multi;
                        """,
                "multi/core/ImplService.java", """
                        package multi.core;
                        public class ImplService extends single.core.Service {
                            private ImplService() {}
                            public String name() { return "multi"; }
                        }
                        """,
                "multi/Main.java", "package multi; public class Main {}"), "multi", LENIENT, single);
        try (URLClassLoader loader = loader(multi, single)) {
            loader.loadClass("multi.Main").getConstructor().newInstance();
            assertEquals("multi", call(staticCall(loader, "single.core.Service", "shared"), "name"),
                    "The consumer's private constructor creates the library's singleton");
        }
    }

    @Test
    void creationsFollowJavaConstructorAccess() throws Exception {
        Map<String, String> sources = new HashMap<>(SINGLETONS);
        sources.put("single/Outsider.java", """
                package single;
                public class Outsider {
                    public Object service() { return new SERVICE(); }
                    public Object secret() { return new SECRET("x"); }
                }
                """);
        var result = JypeTestCompiler.compileTo(sources, root.resolve("outsider"), List.of(), LENIENT);
        result.assertFailure();
        assertTrue(result.hasError("SERVICE is bound to single.core.Service, whose constructor with 0 parameter(s)"
                + " is not accessible from single.Outsider"), result.diagnostics().toString());
        assertTrue(result.hasError("SECRET is bound to single.core.Secret, whose constructor with 1 parameter(s)"
                + " is not accessible from single.Outsider"), result.diagnostics().toString());
    }

    private Path compile(Map<String, String> sources, String name, Path... dependencies) throws Exception {
        return compile(sources, name, STRICT, dependencies);
    }

    private Path compile(Map<String, String> sources, String name, String[] options, Path... dependencies)
            throws Exception {
        Path output = root.resolve(name).resolve("classes");
        JypeTestCompiler.compileTo(sources, output, List.of(dependencies), options).assertSuccess();
        return output;
    }

    private URLClassLoader loader(Path... classes) throws Exception {
        List<java.net.URL> urls = new ArrayList<>();
        for (Path path : classes) urls.add(path.toUri().toURL());
        return new URLClassLoader(urls.toArray(java.net.URL[]::new), getClass().getClassLoader());
    }

    private static Object call(Object target, String method, Object... arguments) throws Exception {
        for (var candidate : target.getClass().getMethods()) {
            if (!candidate.getName().equals(method) || candidate.getParameterCount() != arguments.length) continue;
            try {
                return candidate.invoke(target, arguments);
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof Exception cause) throw cause;
                throw e;
            }
        }
        throw new NoSuchMethodException(method);
    }

    private static Object staticCall(ClassLoader loader, String type, String method) throws Exception {
        return loader.loadClass(type).getMethod(method).invoke(null);
    }
}
