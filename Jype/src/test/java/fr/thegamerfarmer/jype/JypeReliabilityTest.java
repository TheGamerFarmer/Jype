package fr.thegamerfarmer.jype;

import fr.thegamerfarmer.jype.util.JypeTestCompiler;
import org.junit.jupiter.api.Test;

import javax.tools.Diagnostic;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JypeReliabilityTest {
    @Test
    void sourceReferencesUseTheFinalArityOfProcessorManagedTypes() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("api/ApiBase.java", """
                package api;
                public class ApiBase<J_A, J_B, J_C> {}
                """);
        sources.put("game/package-info.java", """
                @fr.thegamerfarmer.jype.annotations.TypeSystem({
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name="J_A", bound=Alpha.class),
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name="J_B", bound=Beta.class),
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name="J_C", bound=Gamma.class)
                }) package game;
                """);
        sources.put("game/Alpha.java", "package game; public class Alpha {}");
        sources.put("game/Beta.java", "package game; public class Beta {}");
        sources.put("game/Gamma.java", "package game; public class Gamma {}");
        sources.put("game/Partial.java", """
                package game;
                public class Partial<J_A> extends api.ApiBase<J_A, Beta, Gamma> {}
                """);
        sources.put("game/Use.java", """
                package game;
                public class Use {
                    private Partial partial = new Partial();
                    private java.util.List<Partial> values = new java.util.ArrayList<>();
                    public Partial accept(Partial value) { return value; }
                }
                """);

        JypeTestCompiler.compileWithOptions(sources,
                "-Xplugin:Jype", "-processor", "fr.thegamerfarmer.jype.JypeProcessorShim",
                "-Xlint:rawtypes", "-Werror").assertSuccess();
    }

    @Test
    void sourceBindingLeavesPreserveInheritedCollectionsAndStaticBounds() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("managed/package-info.java", """
                @fr.thegamerfarmer.jype.annotations.TypeSystem({
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name="J_A", bound=Registry.class),
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name="J_B", bound=Label.class)
                }) package managed;
                """);
        sources.put("managed/Base.java", """
                package managed;
                public abstract class Base {
                    protected J_A registry;
                    protected J_B label;
                    protected java.util.Map<String, Boolean> flags = new java.util.HashMap<>();
                }
                """);
        sources.put("managed/Registry.java", """
                package managed;
                public class Registry extends Base {
                    public boolean enabled() { return flags.get("a") | flags.get("b"); }
                }
                """);
        sources.put("managed/Label.java", "package managed; public abstract class Label extends Base {}");
        sources.put("managed/Facade.java", """
                package managed;
                public class Facade {
                    public J_A instance;
                    public static J_A shared;
                    public void update() { shared = instance; }
                    public boolean enabled() { return shared.enabled(); }
                }
                """);
        JypeTestCompiler.compilePermissive(sources).assertSuccess();
        Map<String, String> reversed = new LinkedHashMap<>();
        sources.entrySet().stream().toList().reversed()
                .forEach(entry -> reversed.put(entry.getKey(), entry.getValue()));
        JypeTestCompiler.compilePermissive(reversed).assertSuccess();
    }

    @Test
    void concreteSourceBoundIsNotReplacedByAnUnrelatedGenericAncestor() throws IOException {
        JypeTestCompiler.compileWithOptions(Map.of(
                "managed/package-info.java", """
                        @fr.thegamerfarmer.jype.annotations.TypeSystem(
                            @fr.thegamerfarmer.jype.annotations.TypeDef(name="J_A", bound=Registry.class))
                        package managed;
                        """,
                "managed/Base.java", "package managed; public abstract class Base<J_A> {}",
                "managed/Registry.java", """
                        package managed;
                        public class Registry extends Base<Registry> {
                            public boolean enabled() { return true; }
                        }
                        """,
                "managed/Facade.java", """
                        package managed;
                        public class Facade<J_A> {
                            public J_A instance;
                            public static J_A shared;
                            public void update() { shared = instance; }
                            public boolean enabled() { return shared.enabled(); }
                        }
                        """),
                "-Xplugin:Jype", "-processor", "fr.thegamerfarmer.jype.JypeProcessorShim").assertSuccess();
    }

    @Test
    void reflectionClassTokensDoNotAcquireInstanceTypeArguments() throws IOException {
        JypeTestCompiler.compileWithOptions(Map.of(
                "managed/package-info.java", """
                        @fr.thegamerfarmer.jype.annotations.TypeSystem(
                            @fr.thegamerfarmer.jype.annotations.TypeDef(name="J_A", bound=Node.class))
                        package managed;
                        """,
                "managed/Node.java", "package managed; public class Node { public J_A linked; }",
                "managed/Reflection.java", """
                        package managed;
                        @SuppressWarnings("rawtypes")
                        public class Reflection {
                            public static <T> java.util.Set<Class<? extends T>> subtypes(Class<T> type) {
                                return java.util.Set.of();
                            }
                            public void inspect() {
                                for (Class<? extends Node> type : subtypes(Node.class)) {
                                    type.getName();
                                }
                            }
                        }
                        """),
                "-Xplugin:Jype", "-processor", "fr.thegamerfarmer.jype.JypeProcessorShim",
                "-Xlint:rawtypes,unchecked", "-Werror").assertSuccess();
    }

    @Test
    void processorInjectedBoundParametersAreReadableBeforeTheBoundClassPass() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("api/Base.java", "package api; public class Base<J_A, J_B> {}");
        sources.put("game/package-info.java", """
                @fr.thegamerfarmer.jype.annotations.TypeSystem({
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name="J_A", bound=Alpha.class),
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name="J_B", bound=Beta.class)
                }) package game;
                """);
        sources.put("game/Consumer.java", "package game; public class Consumer extends api.Base {}");
        sources.put("game/Alpha.java", "package game; public class Alpha {}");
        sources.put("game/Beta.java", "package game; public abstract class Beta extends api.Base {}");
        JypeTestCompiler.compileWithOptions(sources, "-processor",
                "fr.thegamerfarmer.jype.JypeProcessorShim").assertSuccess();
        Map<String, String> reversed = new LinkedHashMap<>();
        sources.entrySet().stream().toList().reversed().forEach(entry -> reversed.put(entry.getKey(), entry.getValue()));
        JypeTestCompiler.compileWithOptions(reversed, "-processor",
                "fr.thegamerfarmer.jype.JypeProcessorShim").assertSuccess();
        JypeTestCompiler.compile(sources).assertSuccess();
        JypeTestCompiler.compile(reversed).assertSuccess();
    }

    @Test
    void inheritedTypeSystemCanSpecializeGenericBoundsAcrossCompiledModules() throws IOException {
        Map<String, String> api = new LinkedHashMap<>();
        Map<String, String> apiBounds = new LinkedHashMap<>();
        apiBounds.put("J_A", "ApiAlpha");
        apiBounds.put("J_B", "ApiBeta");
        apiBounds.put("J_C", "ApiGamma");
        apiBounds.put("J_D", "ApiDelta");
        apiBounds.put("J_E", "ApiEpsilon");
        apiBounds.put("J_F", "ApiZeta");
        apiBounds.put("J_G", "ApiEta");
        apiBounds.put("J_H", "ApiTheta");
        apiBounds.put("J_I", "ApiIota");
        apiBounds.put("J_J", "ApiKappa");
        apiBounds.put("J_K", "ApiLambda");
        apiBounds.put("J_L", "ApiMu");
        apiBounds.put("J_M", "ApiNu");
        apiBounds.put("J_N", "ApiXi");
        apiBounds.put("J_O", "ApiOmicron");
        apiBounds.put("J_P", "ApiPi");
        apiBounds.put("J_Q", "ApiRho");
        apiBounds.put("J_R", "ApiSigma");
        apiBounds.put("J_S", "ApiTau");
        String typeDefinitions = apiBounds.entrySet().stream()
                .map(entry -> "@fr.thegamerfarmer.jype.annotations.TypeDef(name=\""
                        + entry.getKey() + "\", bound=" + entry.getValue() + ".class)")
                .collect(java.util.stream.Collectors.joining(",\n"));
        api.put("api/package-info.java", """
                @fr.thegamerfarmer.jype.annotations.TypeSystem({
                    %s
                }) package api;
                """.formatted(typeDefinitions));
        String fields = apiBounds.keySet().stream()
                .map(name -> "protected " + name + " " + Character.toLowerCase(name.charAt(0))
                        + name.substring(1) + ";")
                .collect(java.util.stream.Collectors.joining("\n"));
        api.put("api/ApiBase.java", "package api; public class ApiBase {" + fields + "}");
        for (String type : apiBounds.values()) {
            String modifier = type.equals("ApiBeta") || type.equals("ApiGamma") ? "abstract " : "";
            api.put("api/" + type + ".java", "package api; public " + modifier
                    + "class " + type + " extends ApiBase {}");
        }
        String staticFields = apiBounds.keySet().stream()
                .map(name -> "@lombok.Getter @lombok.Setter protected static " + name + " "
                        + Character.toLowerCase(name.charAt(0)) + name.substring(1) + ";")
                .collect(java.util.stream.Collectors.joining("\n"));
        api.put("api/ApiHub.java", "package api; public class ApiHub {" + staticFields + "}");
        api.put("api/Contract.java", "package api; public interface Contract { void run(J_A item); }");
        api.put("api/ApiMasterForm.java", "package api; public abstract class ApiMasterForm extends ApiBase {}");
        api.put("api/ApiSubForm.java", """
                package api;
                public abstract class ApiSubForm<MASTER_FORM extends ApiMasterForm> extends ApiBase {
                    public MASTER_FORM master;
                }
                """);

        Map<String, String> game = new LinkedHashMap<>();
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("J_A", "ImplAlpha");
        overrides.put("J_B", "ImplBeta");
        overrides.put("J_C", "ImplGamma");
        overrides.put("J_D", "ImplDelta");
        overrides.put("J_E", "ImplEpsilon");
        overrides.put("J_F", "ImplZeta");
        overrides.put("J_G", "ImplEta");
        overrides.put("J_H", "ImplTheta");
        overrides.put("J_O", "ImplOmicron");
        overrides.put("J_Q", "ImplRho");
        overrides.put("J_R", "ImplSigma");
        String overrideDefinitions = overrides.entrySet().stream()
                .map(entry -> "@fr.thegamerfarmer.jype.annotations.TypeDef(name=\""
                        + entry.getKey() + "\", bound=" + entry.getValue() + ".class)")
                .collect(java.util.stream.Collectors.joining(",\n"));
        game.put("game/package-info.java", """
                @fr.thegamerfarmer.jype.annotations.TypeSystem(
                    inherit="api",
                    overrides={
                        %s
                    }
                ) package game;
                """.formatted(overrideDefinitions));
        for (var entry : overrides.entrySet()) {
            String type = entry.getValue();
            String parent = apiBounds.get(entry.getKey());
            String modifier = type.equals("ImplBeta") || type.equals("ImplGamma") ? "abstract " : "";
            String body = type.equals("ImplAlpha") ? """
                    public ImplBeta linked() { return j_B; }
                    public ApiIota store() { return j_I; }
                    """ : "";
            game.put("game/" + type + ".java", "package game; import api." + parent + "; import api.ApiIota;"
                    + " public " + modifier + "class " + type + " extends " + parent + " {" + body + "}");
        }
        game.put("game/variants/ImplBetaLeaf.java", """
                package game.variants;
                import game.ImplBeta;
                public class ImplBetaLeaf extends ImplBeta {}
                """);
        game.put("game/Util.java", """
                package game;
                import api.ApiHub;
                import api.ApiIota;
                public class Util {
                    public ImplAlpha first(java.util.List<ImplAlpha> items) { return items.get(0); }
                    public static ApiIota store() { return ApiHub.getJ_I(); }
                    public static ApiIota store(ImplAlpha item) { return item.store(); }
                }
                """);
        game.put("game/ImplBase.java", """
                package game;
                import api.ApiBase;
                public abstract class ImplBase extends ApiBase {
                    public ImplAlpha item() { return j_A; }
                }
                """);
        game.put("game/Gui.java", """
                package game;
                public class Gui extends ImplBase {
                    public static void open(ImplAlpha item) { item.linked(); }
                }
                """);
        game.put("game/forms/ImplMasterForm.java", """
                package game.forms;
                import api.ApiMasterForm;
                public abstract class ImplMasterForm extends ApiMasterForm {}
                """);
        game.put("game/forms/ImplSubForm.java", """
                package game.forms;
                import api.ApiSubForm;
                public abstract class ImplSubForm<MASTER_FORM extends ImplMasterForm> extends ApiSubForm<MASTER_FORM> {}
                """);
        game.put("game/forms/ConcreteForm.java", "package game.forms; public class ConcreteForm extends ImplMasterForm {}");
        game.put("game/forms/ConcreteSubForm.java", """
                package game.forms;
                public class ConcreteSubForm extends ImplSubForm<ConcreteForm> {
                    public ConcreteForm root() { return master; }
                }
                """);
        game.put("game/Variants.java", """
                package game;
                public enum Variants implements api.Contract {
                    DEFAULT;
                    @Override public void run(ImplAlpha item) { item.linked(); }
                }
                """);
        game.put("game/Category.java", """
                package game;
                public enum Category {
                    STANDARD;
                    public boolean accepts(ImplAlpha item) { return item != null; }
                    public static java.util.List<Category> all() { return java.util.Arrays.asList(values()); }
                }
                """);
        game.put("game/Main.java", """
                package game;
                import api.ApiHub;
                import api.ApiIota;
                public class Main extends ApiHub {
                    public ApiIota store(ImplAlpha item) {
                        ImplBeta linked = item.linked();
                        return linked == null ? item.store() : item.store();
                    }
                }
                """);

        JypeTestCompiler.compileTwoPassWithJype(api, game).assertSuccess();
    }

    @Test
    void partialMemberArgumentsAreCompletedBeforeEnter() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("managed/package-info.java", """
                @fr.thegamerfarmer.jype.annotations.TypeSystem({
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name="J_A", bound=Entity.class)
                }) package managed;
                """);
        sources.put("managed/Entity.java", "package managed; public class Entity { public int value; }");
        sources.put("managed/Values.java", """
                package managed;
                public class Values<T> {
                    J_A entity;
                    Values<T> copy;
                    public Values() {}
                    public Values(Values<T> source) { copy = source; }
                }
                """);
        sources.put("managed/Registry.java", """
                package managed;
                public class Registry {
                    Values<String> values = new Values<>();
                    J_A instance;
                    Values<String> copy() { return new Values<String>(values); }
                }
                """);
        JypeTestCompiler.compilePermissive(sources).assertSuccess();
    }

    @Test
    void nonGenericBoundsSupplyMembersAndRespectInterfaces() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("managed/package-info.java", """
                @fr.thegamerfarmer.jype.annotations.TypeSystem({
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name="REGISTRY", bound=Registry.class),
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name="CONTRACT", bound=Contract.class)
                }) package managed;
                """);
        sources.put("managed/Registry.java", """
                package managed;
                public class Registry { public final int value = 42; public int resolve() { return value; } }
                """);
        sources.put("managed/Contract.java", "package managed; public interface Contract { int count(); }");
        sources.put("managed/Holder.java", """
                package managed;
                public class Holder {
                    REGISTRY registry;
                    CONTRACT contract;
                    int count() { return registry.value + registry.resolve() + contract.count(); }
                }
                """);
        JypeTestCompiler.compile(sources).assertSuccess();
    }

    @Test
    void managedStaticFieldsUseSharedStorage() throws IOException {
        var result = JypeTestCompiler.compilePermissive(Map.of(
                "managed/package-info.java", system("managed", "TYPE"),
                "managed/Holder.java", """
                        package managed;
                        public class Holder {
                            static TYPE value;
                            TYPE read() { return value; }
                            void write(TYPE input) { value = input; }
                            static TYPE readStatic() { return value; }
                        }
                        """));
        result.assertSuccess();
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.getMessage(null).contains("shared storage")));
    }

    private static String system(String pkg, String name) {
        return """
                @fr.thegamerfarmer.jype.annotations.TypeSystem({
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name = "%s", bound = Object.class)
                })
                package %s;
                """.formatted(name, pkg);
    }

    @Test
    void independentSystemsMayBindTheSameParameterDifferently() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("base/Base.java", "package base; public class Base<TYPE> {}");
        for (String pkg : new String[]{"first", "second", "first.nested"}) {
            sources.put(pkg.replace('.', '/') + "/package-info.java", system(pkg, "TYPE"));
            sources.put(pkg.replace('.', '/') + "/Value.java", "package " + pkg + "; public class Value {}");
            sources.put(pkg.replace('.', '/') + "/Anchor.java",
                    "package " + pkg + "; public class Anchor extends base.Base<Value> {}");
            sources.put(pkg.replace('.', '/') + "/Child.java",
                    "package " + pkg + "; public class Child extends base.Base { TYPE field; }");
        }
        JypeTestCompiler.compile(sources).assertSuccess();
        Map<String, String> reversed = new LinkedHashMap<>();
        sources.entrySet().stream().toList().reversed().forEach(e -> reversed.put(e.getKey(), e.getValue()));
        JypeTestCompiler.compile(reversed).assertSuccess();
    }

    @Test
    void managedNamesDoNotLeakIntoUnrelatedPackages() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("managed/package-info.java", system("managed", "TYPE"));
        sources.put("managed/Holder.java", "package managed; public class Holder { TYPE field; }");
        sources.put("plain/TYPE.java", "package plain; public class TYPE {}");
        sources.put("plain/Holder.java",
                "package plain; public class Holder { TYPE field; Holder copy; }");
        JypeTestCompiler.compile(sources).assertSuccess();
    }

    @Test
    void concreteBindingsResolveWildcardAndQualifiedNamesConsistently() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("managed/package-info.java", system("managed", "TYPE"));
        sources.put("managed/Base.java", "package managed; public class Base<TYPE> {}");
        sources.put("values/Value.java", "package values; public class Value {}");
        sources.put("managed/A.java", "package managed; import values.*; public class A extends Base<Value> {}");
        sources.put("managed/B.java", "package managed; public class B extends Base<values.Value> {}");
        sources.put("managed/Child.java", "package managed; public class Child extends Base {}");
        JypeTestCompiler.compile(sources).assertSuccess();
    }

    @Test
    void conflictsHaveSourcePositionsAndSystemNames() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("managed/package-info.java", system("managed", "TYPE"));
        sources.put("managed/Base.java", "package managed; public class Base<TYPE> {}");
        sources.put("managed/A.java", "package managed; public class A extends Base<A> {}");
        sources.put("managed/B.java", "package managed; public class B extends Base<B> {}");
        var result = JypeTestCompiler.compilePermissive(sources);
        result.assertFailure();
        var conflict = result.diagnostics().stream()
                .filter(d -> d.getMessage(null).contains("Conflicting concrete bindings")).findFirst().orElseThrow();
        assertNotNull(conflict.getSource());
        assertTrue(conflict.getLineNumber() > 0);
        assertTrue(conflict.getMessage(null).contains("@TypeSystem managed"));
    }

    @Test
    void debugLogsAreOptIn() throws IOException {
        Map<String, String> sources = Map.of(
                "managed/package-info.java", system("managed", "TYPE"),
                "managed/Holder.java", "package managed; public class Holder<TYPE> {}");
        var normal = JypeTestCompiler.compile(sources);
        normal.assertSuccess();
        assertFalse(normal.diagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.NOTE
                && d.getMessage(null).contains("[Jype]")));
        var debug = JypeTestCompiler.compileWithOptions(sources, "-Xplugin:Jype", "-Ajype.debug=true");
        debug.assertSuccess();
        assertTrue(debug.diagnostics().stream().anyMatch(d -> d.getMessage(null).contains("[Jype] processClass")));
    }

    @Test
    void incompleteActivationIsReported() throws IOException {
        Map<String, String> sources = Map.of(
                "managed/package-info.java", system("managed", "TYPE"),
                "managed/Holder.java", "package managed; public class Holder {}");
        var processorOnly = JypeTestCompiler.compileWithOptions(sources,
                "-processor", "fr.thegamerfarmer.jype.JypeProcessorShim");
        processorOnly.assertSuccess();
        assertTrue(processorOnly.diagnostics().stream()
                .anyMatch(d -> d.getMessage(null).contains("plugin is not active")));
        var pluginOnly = JypeTestCompiler.compileWithOptions(sources, "-Xplugin:Jype", "-proc:none");
        pluginOnly.assertSuccess();
        assertTrue(pluginOnly.diagnostics().stream()
                .anyMatch(d -> d.getMessage(null).contains("processor did not run")));
    }

    @Test
    void explicitValueSyntaxAndPartialInterfacesAreSupported() throws IOException {
        Map<String, String> sources = Map.of(
                "managed/package-info.java", system("managed", "TYPE").replace("TypeSystem({", "TypeSystem(value = {"),
                "managed/Contract.java", "package managed; public interface Contract<TYPE, E> {}",
                "managed/Child.java", "package managed; public interface Child<TYPE> extends Contract<String> {}",
                "managed/Implementation.java",
                "package managed; public class Implementation<TYPE> implements Contract<String> {}");
        JypeTestCompiler.compile(sources).assertSuccess();
    }

    @Test
    void invalidMappingNamesAndIgnoredDefinitionsAreRejected() throws IOException {
        for (String invalid : new String[]{" TYPE", "bad.name", "class", ""}) {
            var result = JypeTestCompiler.compilePermissive(Map.of(
                    "managed/package-info.java", system("managed", invalid),
                    "managed/Holder.java", "package managed; public class Holder {}"));
            result.assertFailure();
            assertTrue(result.hasError("@TypeDef name"));
        }
        var overridesWithoutParent = JypeTestCompiler.compilePermissive(Map.of(
                "managed/package-info.java",
                system("managed", "TYPE").replace("TypeSystem({", "TypeSystem(overrides = {"),
                "managed/Holder.java", "package managed; public class Holder {}"));
        overridesWithoutParent.assertFailure();
        assertTrue(overridesWithoutParent.hasError("overrides require non-empty inherit"));
    }

    @Test
    void explicitEmptyOverridesRequireInheritance() throws IOException {
        for (String attributes : new String[]{
                "overrides={}", "inherit=\"\", overrides={}",
                "value={}, overrides={}", "inherit=\"\", overrides={"
                + "@fr.thegamerfarmer.jype.annotations.TypeDef(name=\"TYPE\", bound=Object.class)}"}) {
            var result = JypeTestCompiler.compilePermissive(Map.of(
                    "managed/package-info.java",
                    "@fr.thegamerfarmer.jype.annotations.TypeSystem(" + attributes + ") package managed;",
                    "managed/Holder.java", "package managed; public class Holder {}"));
            result.assertFailure();
            assertTrue(result.hasError("overrides require non-empty inherit"), result.diagnostics().toString());
        }
        JypeTestCompiler.compile(Map.of(
                "managed/package-info.java", "@fr.thegamerfarmer.jype.annotations.TypeSystem() package managed;",
                "managed/Holder.java", "package managed; public class Holder {}")).assertSuccess();
    }

    @Test
    void overridesValidateAgainstTransitiveInheritedNames() throws IOException {
        for (String parent : new String[]{"base", "derived"}) {
            for (String name : new String[]{"TYPE", "ABSENT"}) {
                var result = JypeTestCompiler.compilePermissive(Map.of(
                        "base/package-info.java", system("base", "TYPE"),
                        "derived/package-info.java",
                        "@fr.thegamerfarmer.jype.annotations.TypeSystem(inherit=\"base\") package derived;",
                        "managed/package-info.java",
                        "@fr.thegamerfarmer.jype.annotations.TypeSystem(inherit=\"" + parent + "\", overrides={"
                        + "@fr.thegamerfarmer.jype.annotations.TypeDef(name=\"" + name
                        + "\", bound=String.class)}) package managed;",
                        "managed/Holder.java", "package managed; public class Holder {}"));
                if (name.equals("TYPE")) {
                    result.assertSuccess();
                } else {
                    result.assertFailure();
                    assertTrue(result.hasError("does not exist in the inherited @TypeSystem"),
                            result.diagnostics().toString());
                }
            }
        }
        JypeTestCompiler.compile(Map.of(
                "base/package-info.java", system("base", "TYPE"),
                "managed/package-info.java",
                "@fr.thegamerfarmer.jype.annotations.TypeSystem(inherit=\"base\", overrides={}) package managed;",
                "managed/Holder.java", "package managed; public class Holder {}")).assertSuccess();
    }

    @Test
    void compiledExplicitEmptyOverridesRequireInheritance() throws IOException {
        var result = JypeTestCompiler.compileTwoPass(Map.of(
                "library/package-info.java",
                "@fr.thegamerfarmer.jype.annotations.TypeSystem(overrides={}) package library;",
                "library/Anchor.java", "package library; public class Anchor {}"), Map.of(
                "managed/package-info.java",
                "@fr.thegamerfarmer.jype.annotations.TypeSystem(inherit=\"library\") package managed;",
                "managed/Holder.java", "package managed; public class Holder {}"));
        result.assertFailure();
        assertTrue(result.hasError("overrides require non-empty inherit"), result.diagnostics().toString());
    }

    @Test
    void ambiguousWildcardImportsAreNotChosenBySourceOrder() throws IOException {
        Map<String, String> sources = Map.of(
                "one/Base.java", "package one; public class Base<A, B> {}",
                "two/Base.java", "package two; public class Base<A, B, C> {}",
                "consumer/Child.java", """
                        package consumer;
                        import one.*;
                        import two.*;
                        public class Child<A> extends Base<String> {}
                        """);
        var result = JypeTestCompiler.compilePermissive(sources);
        result.assertFailure();
        assertTrue(result.hasError("ambiguous"));
    }

    @Test
    void compiledAndJavaLangBindingsResolveToCanonicalTypes() throws IOException {
        for (String type : new String[]{"String", "Thread.State"}) {
            Map<String, String> sources = Map.of(
                    "managed/package-info.java", system("managed", "TYPE"),
                    "managed/Base.java",
                    "package managed; public class Base<TYPE> { public TYPE get() { return null; } }",
                    "managed/Anchor.java", "package managed; public class Anchor extends Base<" + type + "> {}",
                    "managed/Child.java", "package managed; public class Child extends Base {}",
                    "managed/Use.java", "package managed; public class Use { "
                            + type + " value = new Child().get(); }");
            JypeTestCompiler.compile(sources).assertSuccess();
        }
    }

    @Test
    void oneSourceFileMayConsumeTwoIndependentSystems() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        for (String pkg : new String[]{"first", "second"}) {
            sources.put(pkg + "/package-info.java", system(pkg, "TYPE"));
            sources.put(pkg + "/Base.java", "package " + pkg
                    + "; public class Base<TYPE> { public TYPE get() { return null; } }");
            sources.put(pkg + "/Value.java", "package " + pkg + "; public class Value {}");
        }
        sources.put("consumer/Consumers.java", """
                package consumer;
                class FirstAnchor extends first.Base<first.Value> {}
                class SecondAnchor extends second.Base<second.Value> {}
                class FirstChild extends first.Base {}
                class SecondChild extends second.Base {}
                class Consumers {
                    first.Value first = new FirstChild().get();
                    second.Value second = new SecondChild().get();
                }
                """);
        JypeTestCompiler.compile(sources).assertSuccess();
    }

    @Test
    void enclosingTypeVariablesAreNotConcreteBindings() throws IOException {
        JypeTestCompiler.compile(Map.of(
                "p/package-info.java", system("p", "API"),
                "p/Base.java", "package p; public class Base<API> { public API get() { return null; } }",
                "p/T.java", "package p; public class T {}",
                "p/Good.java", "package p; public class Good {}",
                "p/Anchor.java", "package p; public class Anchor extends Base<Good> {}",
                "p/Outer.java", """
                        package p;
                        public class Outer<T> {
                            class Nested extends Base<T> {}
                            T get(Nested nested) { return nested.get(); }
                        }
                        """
        )).assertSuccess();
    }
}
