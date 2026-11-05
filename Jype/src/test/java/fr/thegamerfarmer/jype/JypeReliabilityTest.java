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
    void partialMemberArgumentsAreCompletedBeforeEnter() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("managed/package-info.java", """
                @fr.thegamerfarmer.jype.annotations.TypeSystem({
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name="PLAYER", bound=Player.class)
                }) package managed;
                """);
        sources.put("managed/Player.java", "package managed; public class Player { public int value; }");
        sources.put("managed/Values.java", """
                package managed;
                public class Values<T> {
                    PLAYER player;
                    Values<T> copy;
                    public Values() {}
                    public Values(Values<T> source) { copy = source; }
                }
                """);
        sources.put("managed/Registry.java", """
                package managed;
                public class Registry {
                    Values<String> values = new Values<>();
                    PLAYER instance;
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
        assertTrue(overridesWithoutParent.hasError("must use either value"));
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
