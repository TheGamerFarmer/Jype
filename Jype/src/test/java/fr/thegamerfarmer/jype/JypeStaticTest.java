package fr.thegamerfarmer.jype;

import fr.thegamerfarmer.jype.util.JypeTestCompiler;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JypeStaticTest {
    @Test
    void standardLombokAccessLevelsPreserveGenericSignaturesAndVisibility() throws Exception {
        JypeTestCompiler.compileAndRun(Map.of(
                "levels/package-info.java", """
                        @fr.thegamerfarmer.jype.annotations.TypeSystem({
                            @fr.thegamerfarmer.jype.annotations.TypeDef(name="VALUE", bound=Number.class)
                        }) package levels;
                        """,
                "levels/Registry.java", """
                        package levels;
                        import java.lang.reflect.Modifier;
                        public class Registry {
                            @lombok.Getter(lombok.AccessLevel.PROTECTED)
                            @lombok.Setter(lombok.AccessLevel.PROTECTED) static VALUE protectedValue;
                            @lombok.Getter(lombok.AccessLevel.PACKAGE)
                            @lombok.Setter(lombok.AccessLevel.PACKAGE) static VALUE packageValue;
                            @lombok.Getter(lombok.AccessLevel.MODULE) static VALUE moduleValue;
                            @lombok.Getter @lombok.Setter static VALUE explicit;
                            static VALUE getExplicit() { return explicit; }
                            static void setExplicit(VALUE input) { explicit = input; }
                            public static void main(String[] args) throws Exception {
                                setProtectedValue(3);
                                setPackageValue(5);
                                setExplicit(7);
                                if (getProtectedValue().intValue() != 3 || getPackageValue().intValue() != 5
                                        || getExplicit().intValue() != 7) throw new AssertionError("storage changed");
                                int protectedFlags = Registry.class.getDeclaredMethod("getProtectedValue").getModifiers();
                                int packageFlags = Registry.class.getDeclaredMethod("getPackageValue").getModifiers();
                                int moduleFlags = Registry.class.getDeclaredMethod("getModuleValue").getModifiers();
                                if (!Modifier.isProtected(protectedFlags)) throw new AssertionError("not protected");
                                if ((packageFlags & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE)) != 0
                                        || (moduleFlags & (Modifier.PUBLIC | Modifier.PROTECTED | Modifier.PRIVATE)) != 0)
                                    throw new AssertionError("package visibility changed");
                                if (Registry.class.getDeclaredMethod("getExplicit").getTypeParameters().length != 1)
                                    throw new AssertionError("explicit accessor was not genericized");
                            }
                        }
                        """), "levels.Registry");
    }

    private Map<String, String> sources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("shared/package-info.java", """
                @fr.thegamerfarmer.jype.annotations.TypeSystem({
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name="PLAYER", bound=Player.class),
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name="MANAGER", bound=Manager.class)
                }) package shared;
                """);
        sources.put("shared/Player.java", """
                package shared;
                public class Player { PLAYER self; public int value() { return 42; } }
                """);
        sources.put("shared/Manager.java", """
                package shared;
                public class Manager {
                    public PLAYER player;
                    public PLAYER get() { return player; }
                    public void put(PLAYER value) { player = value; }
                }
                """);
        return sources;
    }

    @Test
    void lombokAccessorsRetainRecursiveBoundsAndCollectionElementTypes() throws IOException {
        var sources = sources();
        sources.put("shared/Registry.java", """
                package shared;
                import java.util.*;
                public class Registry {
                    @lombok.Getter @lombok.Setter public static MANAGER manager;
                    @lombok.Getter @lombok.Setter public static Map<String, PLAYER> players = new HashMap<>();
                    void init(MANAGER input, PLAYER player) {
                        manager = input;
                        manager.put(player);
                        players.put("one", player);
                        PLAYER read = players.get("one");
                    }
                }
                """);
        sources.put("shared/Consumer.java", """
                package shared;
                import java.util.*;
                public class Consumer {
                    PLAYER player;
                    MANAGER manager = Registry.getManager();
                    Map<String, PLAYER> players = Registry.getPlayers();
                    PLAYER chain() { return Registry.getManager().get(); }
                    void write() { Registry.setManager(manager); Registry.setPlayers(players); }
                }
                """);
        JypeTestCompiler.compilePermissive(sources).assertSuccess();
    }

    @Test
    void ordinaryClassTypeParametersAreStillIllegalInStaticMembers() throws IOException {
        var sources = sources();
        sources.put("shared/Invalid.java", "package shared; public class Invalid<T> { static T value; }");
        var result = JypeTestCompiler.compilePermissive(sources);
        result.assertFailure();
        assertTrue(result.hasError("non-static type variable T"));
    }

    @Test
    void localsAndLambdaParametersDoNotBecomeStaticFieldReads() throws IOException {
        var sources = sources();
        sources.put("shared/Registry.java", """
                package shared;
                public class Registry {
                    static PLAYER player;
                    PLAYER instance;
                    String shadow(String player) { return player.trim(); }
                    String local() { String player = "ok"; return player.trim(); }
                    java.util.function.Function<String, String> lambda() { return player -> player.trim(); }
                    PLAYER direct() { return this.player; }
                }
                """);
        JypeTestCompiler.compilePermissive(sources).assertSuccess();
    }

    @Test
    void staticMethodsOwnTheirManagedParameters() throws IOException {
        var sources = sources();
        sources.put("shared/Registry.java", """
                package shared;
                public class Registry {
                    static PLAYER player;
                    static PLAYER read() { return player; }
                    static void write(PLAYER value) { player = value; }
                    static int member() { PLAYER value = read(); return value.value(); }
                    PLAYER instance() { write(player); return read(); }
                }
                """);
        JypeTestCompiler.compilePermissive(sources).assertSuccess();
    }

    @Test
    void partialStaticTypesKeepOrdinaryArgumentsInTheirViews() throws IOException {
        var sources = sources();
        sources.put("shared/Values.java", """
                package shared;
                public class Values<T> {
                    PLAYER player;
                    public T find(PLAYER player) { return null; }
                }
                """);
        sources.put("shared/Registry.java", """
                package shared;
                public class Registry {
                    @lombok.Getter static final Values<Integer> values = new Values<>();
                    PLAYER player;
                    Integer read() { return values.find(player); }
                }
                """);
        sources.put("shared/Consumer.java", """
                package shared;
                public class Consumer {
                    PLAYER player;
                    Integer read() { return Registry.getValues().find(player); }
                }
                """);
        JypeTestCompiler.compilePermissive(sources).assertSuccess();
    }

    @Test
    void runtimeSharesStorageAndKeepsAccessorSignatures() throws Exception {
        var sources = sources();
        sources.put("shared/Registry.java", """
                package shared;
                import java.util.*;
                public class Registry {
                    @lombok.Getter @lombok.Setter static MANAGER manager;
                    @lombok.Getter @lombok.Setter static Map<String, PLAYER> players = new HashMap<>();
                    PLAYER marker;
                    void write(MANAGER value, PLAYER player) {
                        manager = value;
                        manager.put(player);
                        players.put("one", player);
                    }
                    PLAYER read() { return players.get("one"); }
                    PLAYER chain() { return Registry.getManager().get(); }
                    public static void main(String[] args) throws Exception {
                        Registry first = new Registry();
                        Registry second = new Registry();
                        Player player = new Player();
                        first.write(new Manager(), player);
                        if (second.read() != player || second.chain() != player) throw new AssertionError("not shared");
                        if (getPlayers().get("one") != player) throw new AssertionError("getter copied storage");
                        if (Registry.class.getDeclaredMethod("getManager").getTypeParameters().length != 2)
                            throw new AssertionError("missing method parameters");
                        if (!(Registry.class.getDeclaredMethod("getManager").getGenericReturnType()
                                instanceof java.lang.reflect.TypeVariable<?> variable)
                                || variable.getGenericDeclaration() instanceof Class)
                            throw new AssertionError("class parameter leaked into static signature");
                    }
                }
                """);
        JypeTestCompiler.compileAndRun(sources, "shared.Registry");
    }

    @Test
    void genericStaticAccessorsSurviveSeparateCompilation() throws IOException {
        Map<String, String> library = Map.of(
                "library/package-info.java", """
                        @fr.thegamerfarmer.jype.annotations.TypeSystem({
                            @fr.thegamerfarmer.jype.annotations.TypeDef(name="VALUE", bound=Number.class)
                        }) package library;
                        """,
                "library/Registry.java", """
                        package library;
                        public class Registry {
                            @lombok.Getter @lombok.Setter public static VALUE value;
                        }
                        """);
        JypeTestCompiler.compileTwoPassWithJype(library, Map.of(
                "consumer/Use.java", """
                        package consumer;
                        class Use {
                            Integer read() { return library.Registry.getValue(); }
                            void write() { library.Registry.setValue(Integer.valueOf(42)); }
                        }
                        """)).assertSuccess();
    }

    @Test
    void incompatibleValuesAreRejectedAtTheGenericSetterBoundary() throws IOException {
        var sources = sources();
        sources.put("shared/Registry.java", """
                package shared;
                public class Registry {
                    @lombok.Getter @lombok.Setter static PLAYER player;
                    void wrong() { Registry.setPlayer("not a player"); }
                }
                """);
        JypeTestCompiler.compilePermissive(sources).assertFailure();
    }

    @Test
    void inheritedViewsAndOrdinaryMethodParametersArePreserved() throws IOException {
        var sources = sources();
        sources.put("shared/Base.java", """
                package shared;
                public class Base {
                    protected static PLAYER player;
                    public static <T> PLAYER convert(T value) { return player; }
                }
                """);
        sources.put("shared/Child.java", """
                package shared;
                public class Child extends Base {
                    PLAYER read() { return player; }
                    PLAYER qualified() { return this.player; }
                    PLAYER method() { return Base.convert("ok"); }
                    PLAYER explicitMethod() { return Base.<String>convert("ok"); }
                    String shadow() {
                        String String = "value";
                        return String.trim();
                    }
                    Runnable anonymous() { return new Runnable() { public void run() {} }; }
                }
                """);
        JypeTestCompiler.compilePermissive(sources).assertSuccess();
    }

    @Test
    void accessorVisibilityAndFinalFieldsKeepTheirJavaRules() throws IOException {
        var sources = sources();
        sources.put("shared/Registry.java", """
                package shared;
                public class Registry {
                    @lombok.Getter(lombok.AccessLevel.PRIVATE) static PLAYER player;
                    @lombok.Getter @lombok.Setter static final java.util.List<PLAYER> players = new java.util.ArrayList<>();
                    @lombok.Getter(lombok.AccessLevel.NONE) static MANAGER hidden;
                    PLAYER own() { return getPlayer(); }
                }
                """);
        sources.put("shared/Invalid.java", """
                package shared;
                class Invalid { Object read() { return Registry.getPlayer(); } }
                """);
        var result = JypeTestCompiler.compilePermissive(sources);
        result.assertFailure();
        assertTrue(result.hasError("private access"));
    }

    @Test
    void inheritedClasspathStorageAndChainedGettersKeepTheConsumerBinding() throws IOException {
        var library = sources();
        library.put("shared/Registry.java", """
                package shared;
                public class Registry {
                    @lombok.Getter @lombok.Setter protected static MANAGER manager;
                    @lombok.Getter protected static PLAYER player;
                }
                """);
        JypeTestCompiler.compileTwoPassWithJype(library, Map.of(
                "consumer/package-info.java", """
                        @fr.thegamerfarmer.jype.annotations.TypeSystem(inherit="shared")
                        package consumer;
                        """,
                "consumer/Child.java", """
                        package consumer;
                        public class Child extends shared.Registry {
                            PLAYER marker;
                            @lombok.Getter static MANAGER copy;
                            PLAYER direct() { return player; }
                            PLAYER chained() { return shared.Registry.getManager().get(); }
                            PLAYER own() { return Child.getCopy().get(); }
                        }
                        """)).assertSuccess();
    }

    @Test
    void staticInitializationArraysAndMultipleDeclaratorsWork() throws Exception {
        var sources = sources();
        sources.put("shared/Registry.java", """
                package shared;
                public class Registry {
                    static PLAYER first, second;
                    static PLAYER[] players;
                    static { PLAYER value = (PLAYER) new Player(); first = value; }
                    PLAYER marker;
                    PLAYER replace(PLAYER value) { players[0] = value; return players[0]; }
                }
                """);
        var result = JypeTestCompiler.compilePermissive(sources);
        result.assertSuccess();
        assertFalse(result.diagnostics().stream().anyMatch(d -> d.getMessage(null).contains("Failed to write javadoc")));
    }

    @Test
    void explicitMethodTypeParametersRemainIndependent() throws IOException {
        var sources = sources();
        sources.put("shared/Methods.java", """
                package shared;
                class Methods {
                    static <PLAYER> PLAYER identity(PLAYER input) { return input; }
                    String use() { return Methods.<String>identity("ok"); }
                }
                """);
        JypeTestCompiler.compilePermissive(sources).assertSuccess();
    }

    @Test
    void unsupportedLombokAccessorsFailRatherThanChangingTheirBehavior() throws IOException {
        for (String annotations : new String[]{
                "@lombok.Getter(lazy=true)",
                "@lombok.Getter @lombok.experimental.Accessors(fluent=true)"}) {
            var sources = sources();
            sources.put("shared/Registry.java", """
                    package shared;
                    class Registry {
                        %s static final PLAYER player = null;
                    }
                    """.formatted(annotations));
            var result = JypeTestCompiler.compilePermissive(sources);
            result.assertFailure();
            assertTrue(result.hasError("Managed static fields support standard Lombok"));
        }
    }

    @Test
    void staticImportsKeepContextualTypesAndLocalShadowing() throws IOException {
        var sources = sources();
        sources.put("shared/Registry.java", """
                package shared;
                public class Registry {
                    public static PLAYER player;
                    @lombok.Getter public static MANAGER manager;
                }
                """);
        sources.put("shared/nested/Consumer.java", """
                package shared.nested;
                import static shared.Registry.player;
                import static shared.Registry.getManager;
                class Consumer {
                    PLAYER marker;
                    PLAYER read() { return player; }
                    PLAYER chain() { return getManager().get(); }
                    String shadow(String player) { return player.trim(); }
                }
                """);
        JypeTestCompiler.compilePermissive(sources).assertSuccess();
    }

    @Test
    void directAssignmentsAndCollectionMutationsDoNotBypassTheViewType() throws IOException {
        for (String invalid : new String[]{
                "players = new java.util.HashMap<String, String>();",
                "players.put(\"bad\", \"not a player\");"}) {
            for (String modifier : new String[]{"", "static "}) {
                var sources = sources();
                sources.put("shared/Registry.java", """
                        package shared;
                        class Registry {
                            static java.util.Map<String, PLAYER> players = new java.util.HashMap<>();
                            %svoid wrong() { %s }
                        }
                        """.formatted(modifier, invalid));
                JypeTestCompiler.compilePermissive(sources).assertFailure();
            }
        }
    }

    @Test
    void assignmentExpressionsKeepTheirValueAndEvaluationOrder() throws Exception {
        var sources = sources();
        sources.put("shared/Registry.java", """
                package shared;
                public class Registry {
                    static PLAYER player;
                    static int sequence;
                    PLAYER marker;
                    static Registry receiver() { sequence = sequence * 10 + 1; return null; }
                    static Player argument() { sequence = sequence * 10 + 2; return new Player(); }
                    PLAYER assign(PLAYER value) { return player = value; }
                    public static void main(String[] args) {
                        PLAYER result = receiver().player = (PLAYER) argument();
                        if (sequence != 12 || result != player) throw new AssertionError("assignment semantics changed");
                    }
                }
                """);
        JypeTestCompiler.compileAndRun(sources, "shared.Registry");
    }

    @Test
    void concreteConsumersUseTheirOverriddenStaticBindingWithoutClassParameters() throws IOException {
        Map<String, String> library = Map.of(
                "library/package-info.java", """
                        @fr.thegamerfarmer.jype.annotations.TypeSystem({
                            @fr.thegamerfarmer.jype.annotations.TypeDef(name="VALUE", bound=Number.class)
                        }) package library;
                        """,
                "library/Registry.java", """
                        package library;
                        public class Registry { @lombok.Getter public static VALUE value; }
                        """);
        JypeTestCompiler.compileTwoPassWithJype(library, Map.of(
                "consumer/package-info.java", """
                        @fr.thegamerfarmer.jype.annotations.TypeSystem(inherit="library", overrides={
                            @fr.thegamerfarmer.jype.annotations.TypeDef(name="VALUE", bound=Integer.class)
                        }) package consumer;
                        """,
                "consumer/Use.java", """
                        package consumer;
                        class Use {
                            int chained() { return library.Registry.getValue().compareTo(42); }
                            int direct() { return library.Registry.value.compareTo(42); }
                        }
                        """)).assertSuccess();
    }

    @Test
    void staticInitializationDoesNotHideIllegalForwardReferences() throws IOException {
        var result = JypeTestCompiler.compilePermissive(Map.of(
                "shared/package-info.java", """
                        @fr.thegamerfarmer.jype.annotations.TypeSystem({
                            @fr.thegamerfarmer.jype.annotations.TypeDef(name="VALUE", bound=Number.class)
                        }) package shared;
                        """,
                "shared/Invalid.java", """
                        package shared;
                        class Invalid { static VALUE first = second; static VALUE second; }
                        """));
        result.assertFailure();
        assertTrue(result.hasError("illegal forward reference"));
    }
}
