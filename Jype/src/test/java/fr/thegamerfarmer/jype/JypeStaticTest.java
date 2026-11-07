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
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name="J_A", bound=BoundA.class),
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name="J_B", bound=BoundB.class)
                }) package shared;
                """);
        sources.put("shared/BoundA.java", """
                package shared;
                public class BoundA { J_A self; public int value() { return 42; } }
                """);
        sources.put("shared/BoundB.java", """
                package shared;
                public class BoundB {
                    public J_A item;
                    public J_A get() { return item; }
                    public void put(J_A value) { item = value; }
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
                    @lombok.Getter @lombok.Setter public static J_B hub;
                    @lombok.Getter @lombok.Setter public static Map<String, J_A> items = new HashMap<>();
                    void init(J_B input, J_A item) {
                        hub = input;
                        hub.put(item);
                        items.put("one", item);
                        J_A read = items.get("one");
                    }
                }
                """);
        sources.put("shared/Consumer.java", """
                package shared;
                import java.util.*;
                public class Consumer {
                    J_A item;
                    J_B hub = Registry.getHub();
                    Map<String, J_A> items = Registry.getItems();
                    J_A chain() { return Registry.getHub().get(); }
                    void write() { Registry.setHub(hub); Registry.setItems(items); }
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
                    static J_A item;
                    J_A instance;
                    String shadow(String item) { return item.trim(); }
                    String local() { String item = "ok"; return item.trim(); }
                    java.util.function.Function<String, String> lambda() { return item -> item.trim(); }
                    J_A direct() { return this.item; }
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
                    static J_A item;
                    static J_A read() { return item; }
                    static void write(J_A value) { item = value; }
                    static int member() { J_A value = read(); return value.value(); }
                    J_A instance() { write(item); return read(); }
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
                    J_A item;
                    public T find(J_A item) { return null; }
                }
                """);
        sources.put("shared/Registry.java", """
                package shared;
                public class Registry {
                    @lombok.Getter static final Values<Integer> values = new Values<>();
                    J_A item;
                    Integer read() { return values.find(item); }
                }
                """);
        sources.put("shared/Consumer.java", """
                package shared;
                public class Consumer {
                    J_A item;
                    Integer read() { return Registry.getValues().find(item); }
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
                    @lombok.Getter @lombok.Setter static J_B hub;
                    @lombok.Getter @lombok.Setter static Map<String, J_A> items = new HashMap<>();
                    J_A marker;
                    void write(J_B value, J_A item) {
                        hub = value;
                        hub.put(item);
                        items.put("one", item);
                    }
                    J_A read() { return items.get("one"); }
                    J_A chain() { return Registry.getHub().get(); }
                    public static void main(String[] args) throws Exception {
                        Registry first = new Registry();
                        Registry second = new Registry();
                        BoundA item = new BoundA();
                        first.write(new BoundB(), item);
                        if (second.read() != item || second.chain() != item) throw new AssertionError("not shared");
                        if (getItems().get("one") != item) throw new AssertionError("getter copied storage");
                        if (Registry.class.getDeclaredMethod("getHub").getTypeParameters().length != 2)
                            throw new AssertionError("missing method parameters");
                        if (!(Registry.class.getDeclaredMethod("getHub").getGenericReturnType()
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
                    @lombok.Getter @lombok.Setter static J_A item;
                    void wrong() { Registry.setItem("not a string"); }
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
                    protected static J_A item;
                    public static <T> J_A convert(T value) { return item; }
                }
                """);
        sources.put("shared/Child.java", """
                package shared;
                public class Child extends Base {
                    J_A read() { return item; }
                    J_A qualified() { return this.item; }
                    J_A method() { return Base.convert("ok"); }
                    J_A explicitMethod() { return Base.<String>convert("ok"); }
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
                    @lombok.Getter(lombok.AccessLevel.PRIVATE) static J_A item;
                    @lombok.Getter @lombok.Setter static final java.util.List<J_A> items = new java.util.ArrayList<>();
                    @lombok.Getter(lombok.AccessLevel.NONE) static J_B hidden;
                    J_A own() { return getItem(); }
                }
                """);
        sources.put("shared/Invalid.java", """
                package shared;
                class Invalid { Object read() { return Registry.getItem(); } }
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
                    @lombok.Getter @lombok.Setter protected static J_B hub;
                    @lombok.Getter protected static J_A item;
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
                            J_A marker;
                            @lombok.Getter static J_B copy;
                            J_A direct() { return item; }
                            J_A chained() { return shared.Registry.getHub().get(); }
                            J_A own() { return Child.getCopy().get(); }
                        }
                        """)).assertSuccess();
    }

    @Test
    void staticInitializationArraysAndMultipleDeclaratorsWork() throws Exception {
        var sources = sources();
        sources.put("shared/Registry.java", """
                package shared;
                public class Registry {
                    static J_A first, second;
                    static J_A[] items;
                    static { J_A value = (J_A) new BoundA(); first = value; }
                    J_A marker;
                    J_A replace(J_A value) { items[0] = value; return items[0]; }
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
                    static <J_A> J_A identity(J_A input) { return input; }
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
                        %s static final J_A item = null;
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
                    public static J_A item;
                    @lombok.Getter public static J_B hub;
                }
                """);
        sources.put("shared/nested/Consumer.java", """
                package shared.nested;
                import static shared.Registry.item;
                import static shared.Registry.getHub;
                class Consumer {
                    J_A marker;
                    J_A read() { return item; }
                    J_A chain() { return getHub().get(); }
                    String shadow(String item) { return item.trim(); }
                }
                """);
        JypeTestCompiler.compilePermissive(sources).assertSuccess();
    }

    @Test
    void directAssignmentsAndCollectionMutationsDoNotBypassTheViewType() throws IOException {
        for (String invalid : new String[]{
                "items = new java.util.HashMap<String, String>();",
                "items.put(\"bad\", \"not an item\");"}) {
            for (String modifier : new String[]{"", "static "}) {
                var sources = sources();
                sources.put("shared/Registry.java", """
                        package shared;
                        class Registry {
                            static java.util.Map<String, J_A> items = new java.util.HashMap<>();
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
                    static J_A item;
                    static int sequence;
                    J_A marker;
                    static Registry receiver() { sequence = sequence * 10 + 1; return null; }
                    static BoundA argument() { sequence = sequence * 10 + 2; return new BoundA(); }
                    J_A assign(J_A value) { return item = value; }
                    public static void main(String[] args) {
                        J_A result = receiver().item = (J_A) argument();
                        if (sequence != 12 || result != item) throw new AssertionError("assignment semantics changed");
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
