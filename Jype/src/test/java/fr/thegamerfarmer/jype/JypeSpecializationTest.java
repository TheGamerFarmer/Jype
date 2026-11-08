package fr.thegamerfarmer.jype;

import fr.thegamerfarmer.jype.util.JypeTestCompiler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Specialized consumers whose library keeps a template-valued alias ({@code CLUSTER -> Cluster}):
 * static members of the consumer and a third level re-binding an alias closed by the second.
 */
class JypeSpecializationTest {
    private static final String[] STRICT = {"-Xplugin:Jype", "-processor",
            "fr.thegamerfarmer.jype.JypeProcessorShim", "-Xlint:rawtypes,unchecked", "-Werror"};

    private static final Map<String, String> API = Map.of(
            "api/package-info.java", """
                    @fr.thegamerfarmer.jype.annotations.TypeSystem({
                        @fr.thegamerfarmer.jype.annotations.TypeDef(name="ENTITY", bound=ApiEntity.class),
                        @fr.thegamerfarmer.jype.annotations.TypeDef(name="CLUSTER", bound=Cluster.class)
                    }) package api;
                    """,
            "api/ApiEntity.java", """
                    package api;
                    public class ApiEntity {
                        public CLUSTER cluster;
                        public String name() { return "api"; }
                    }
                    """,
            "api/Cluster.java", """
                    package api;
                    public class Cluster {
                        public ENTITY entity;
                        public java.util.List<ENTITY> all = new java.util.ArrayList<>();
                        public ENTITY produce() { entity = new ENTITY(); all.add(entity); return entity; }
                    }
                    """);

    private static final Map<String, String> GAME = Map.of(
            "impl/package-info.java", """
                    @fr.thegamerfarmer.jype.annotations.TypeSystem(inherit="api", overrides={
                        @fr.thegamerfarmer.jype.annotations.TypeDef(name="ENTITY", bound=AppEntity.class)
                    }) package impl;
                    """,
            "impl/AppEntity.java", """
                    package impl;
                    public class AppEntity extends api.ApiEntity { public String name() { return "impl"; } }
                    """,
            "impl/AppCluster.java", """
                    package impl;
                    public class AppCluster extends api.Cluster { public String first() { return produce().name(); } }
                    """,
            "impl/Helpers.java", """
                    package impl;
                    public class Helpers {
                        ENTITY owner;
                        public static ENTITY make() { return new ENTITY(); }
                        public static String describe(ENTITY entity) { ENTITY local = entity; return local.name(); }
                        public static CLUSTER clusterOf(ENTITY entity) { return entity.cluster; }
                        public static java.util.List<ENTITY> pair() {
                            java.util.List<ENTITY> entities = new java.util.ArrayList<>();
                            entities.add(make());
                            entities.add((ENTITY) new AppEntity());
                            return entities;
                        }
                        public static <ENTITY> ENTITY shadowed(ENTITY value) { return value; }
                        public static String run() {
                            return describe(make()) + pair().size() + (clusterOf(make()) == null) + shadowed("!");
                        }
                    }
                    """);

    private Path root;

    @BeforeEach
    void workspace() throws Exception {
        root = Files.createTempDirectory(Files.createDirectories(Path.of("target", "test-workspaces")), "jype-levels-");
    }

    @AfterEach
    void clean() throws Exception {
        try (var files = Files.walk(root)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    @Test
    void staticMembersOfASpecializedConsumerUseTheBindings() throws Exception {
        Path api = compile(API, "api");
        Path impl = compile(GAME, "impl", api);
        try (URLClassLoader loader = loader(impl, api)) {
            assertEquals("impl2true!", loader.loadClass("impl.Helpers").getMethod("run").invoke(null));
            var make = loader.loadClass("impl.Helpers").getMethod("make");
            assertEquals("impl.AppEntity", make.getReturnType().getName(), "ENTITY is the binding in static members");
        }
    }

    @Test
    void thirdLevelRebindsAnAliasClosedByTheSecondLevel() throws Exception {
        Path api = compile(API, "api");
        Path impl = compile(GAME, "impl", api);
        Path ext = compile(Map.of(
                "ext/package-info.java", """
                        @fr.thegamerfarmer.jype.annotations.TypeSystem(inherit="impl", overrides={
                            @fr.thegamerfarmer.jype.annotations.TypeDef(name="ENTITY", bound=ExtEntity.class)
                        }) package ext;
                        """,
                "ext/ExtEntity.java", """
                        package ext;
                        public class ExtEntity extends impl.AppEntity {
                            public String name() { return "ext"; }
                            public int bonus() { return 3; }
                        }
                        """,
                "ext/ExtCluster.java", """
                        package ext;
                        public class ExtCluster extends impl.AppCluster {
                            public String second() { return produce().name(); }
                        }
                        """,
                "ext/Hub.java", """
                        package ext;
                        public class Hub {
                            ENTITY head;
                            CLUSTER cluster;
                            api.Cluster raw;
                            java.util.List<ENTITY> entities = new java.util.ArrayList<>();
                            public String run() {
                                head = new ENTITY();
                                entities.add(head);
                                // AppEntity fixed ENTITY in its supertypes: ExtEntity members need a cast.
                                return head.name() + ((ExtEntity<?>) head).bonus();
                            }
                            public static ENTITY make() { return new ENTITY(); }
                            public static String main() {
                                ExtCluster m = new ExtCluster();
                                return m.first() + m.second() + make().name() + new Hub().run();
                            }
                        }
                        """), "ext", api, impl);
        try (URLClassLoader loader = loader(ext, impl, api)) {
            assertEquals("extextextext3", loader.loadClass("ext.Hub").getMethod("main").invoke(null));
        }
    }

    private Path compile(Map<String, String> sources, String name, Path... dependencies) throws Exception {
        Path output = root.resolve(name).resolve("classes");
        JypeTestCompiler.compileTo(sources, output, List.of(dependencies), STRICT).assertSuccess();
        return output;
    }

    private URLClassLoader loader(Path... classes) throws Exception {
        List<java.net.URL> urls = new ArrayList<>();
        for (Path path : classes) urls.add(path.toUri().toURL());
        return new URLClassLoader(urls.toArray(java.net.URL[]::new), getClass().getClassLoader());
    }
}
