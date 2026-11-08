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
 * {@code ALIAS.member} reaches the binding's static members in every context: instance members of
 * plain classes, static member classes, enums (which cannot own alias parameters) and static
 * initializers.
 */
class JypeAliasQualifierTest {
    private static final String[] OPTIONS = {"-Xplugin:Jype", "-processor",
            "fr.thegamerfarmer.jype.JypeProcessorShim"};

    private Path root;

    @BeforeEach
    void workspace() throws Exception {
        root = Files.createTempDirectory(Files.createDirectories(Path.of("target", "test-workspaces")), "jype-qualifier-");
    }

    @AfterEach
    void clean() throws Exception {
        try (var files = Files.walk(root)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    @Test
    void aliasQualifiersReachTheBindingInEveryContext() throws Exception {
        Path classes = root.resolve("classes");
        JypeTestCompiler.compileTo(Map.of(
                "api/package-info.java", """
                        @fr.thegamerfarmer.jype.annotations.TypeSystem({
                            @fr.thegamerfarmer.jype.annotations.TypeDef(name="ENTITY", bound=api.Entity.class),
                            @fr.thegamerfarmer.jype.annotations.TypeDef(name="SERVICE", bound=api.Service.class)
                        }) package api;
                        """,
                "api/Entity.java", "package api; public class Entity { public String name() { return \"entity\"; } }",
                "api/Service.java", """
                        package api;
                        public class Service {
                            private static SERVICE instance;
                            public static SERVICE shared() { if (instance == null) instance = new SERVICE(); return instance; }
                            public static final String PREFIX = "#";
                            protected Service() {}
                            public String get(String key) { return PREFIX + key; }
                        }
                        """,
                "api/layout/Sheet.java", """
                        package api.layout;
                        public class Sheet {
                            protected ENTITY entity;
                            public enum Level {
                                LOW("low"), HIGH("high") { public String label() { return SERVICE.shared().get("anonymous"); } };
                                private final String label;
                                ENTITY owner = new ENTITY();
                                Level(String key) { label = SERVICE.shared().get(key); }
                                public String label() { return label; }
                                public String owner() { ENTITY local = owner; return local.name() + SERVICE.PREFIX; }
                            }
                            public enum Kind {
                                SERVICE, OTHER;
                                public String first() { return SERVICE.name(); }
                            }
                            public static class Nested { public String read() { return SERVICE.shared().get("nested"); } }
                            public static String direct() { return SERVICE.shared().get("direct"); }
                        }
                        """,
                "api/layout/Plain.java", """
                        package api.layout;
                        public class Plain {
                            final Object field = SERVICE.shared();
                            static final String STATIC = SERVICE.shared().get("static");
                            static String block;
                            static { block = SERVICE.shared().get("block"); }
                            public String instance() { return SERVICE.shared().get("instance"); }
                            public String lambda() {
                                java.util.function.Supplier<String> supplier = () -> SERVICE.shared().get("lambda");
                                return supplier.get();
                            }
                            public static String statics() { return STATIC + block; }
                            public boolean same() { return field == SERVICE.shared(); }
                        }
                        """), classes, List.of(), OPTIONS).assertSuccess();
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()}, getClass().getClassLoader())) {
            Object[] levels = loader.loadClass("api.layout.Sheet$Level").getEnumConstants();
            assertEquals("#low", call(levels[0], "label"));
            assertEquals("#anonymous", call(levels[1], "label"));
            assertEquals("entity#", call(levels[0], "owner"));
            assertEquals("api.Entity", loader.loadClass("api.layout.Sheet$Level").getDeclaredField("owner").getType().getName(),
                    "An enum sees aliases as their bindings");
            Object[] kinds = loader.loadClass("api.layout.Sheet$Kind").getEnumConstants();
            assertEquals("SERVICE", call(kinds[1], "first"), "An enum constant named like an alias stays a constant");
            assertEquals("#nested", call(loader.loadClass("api.layout.Sheet$Nested").getConstructor().newInstance(), "read"));
            assertEquals("#direct", loader.loadClass("api.layout.Sheet").getMethod("direct").invoke(null));
            Object plain = loader.loadClass("api.layout.Plain").getConstructor().newInstance();
            assertEquals("#instance", call(plain, "instance"));
            assertEquals("#lambda", call(plain, "lambda"));
            assertEquals(true, call(plain, "same"));
            assertEquals("#static#block", loader.loadClass("api.layout.Plain").getMethod("statics").invoke(null));
        }
    }

    private static Object call(Object target, String method) throws Exception {
        var candidate = target.getClass().getMethod(method);
        candidate.setAccessible(true);
        return candidate.invoke(target);
    }
}
