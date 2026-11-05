package fr.thegamerfarmer.jype;

import fr.thegamerfarmer.jype.util.JypeTestCompiler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for JypeProcessor (annotation processing through @TypeSystem).
 * Compiles Java sources in memory and verifies that Jype correctly injects
 * missing generic type arguments.
 */
class JypeProcessorTest {

    // -------------------------------------------------------------------------
    // Fixtures
    // -------------------------------------------------------------------------

    /**
     * Context B: concrete bounds, package {@code testpkg}.
     * Provides the 6 base sources (package-info + Base + BaseA + BaseB + ConcreteA + ConcreteB).
     */
    private static Map<String, String> baseFixture() {
        Map<String, String> sources = new LinkedHashMap<>();

        sources.put("testpkg/package-info.java", """
                @fr.thegamerfarmer.jype.annotations.TypeSystem({
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name = "TYPE_A", bound = testpkg.ConcreteA.class),
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name = "TYPE_B", bound = testpkg.ConcreteB.class)
                })
                package testpkg;
                """);

        sources.put("testpkg/Base.java", """
                package testpkg;
                public abstract class Base<
                        TYPE_A extends BaseA<TYPE_A, TYPE_B>,
                        TYPE_B extends BaseB<TYPE_A, TYPE_B>> {
                }
                """);

        sources.put("testpkg/BaseA.java", """
                package testpkg;
                public abstract class BaseA<
                        TYPE_A extends BaseA<TYPE_A, TYPE_B>,
                        TYPE_B extends BaseB<TYPE_A, TYPE_B>>
                        extends Base<TYPE_A, TYPE_B> {
                }
                """);

        sources.put("testpkg/BaseB.java", """
                package testpkg;
                public abstract class BaseB<
                        TYPE_A extends BaseA<TYPE_A, TYPE_B>,
                        TYPE_B extends BaseB<TYPE_A, TYPE_B>>
                        extends Base<TYPE_A, TYPE_B> {
                }
                """);

        sources.put("testpkg/ConcreteA.java", """
                package testpkg;
                public class ConcreteA extends BaseA<ConcreteA, ConcreteB> {
                }
                """);

        sources.put("testpkg/ConcreteB.java", """
                package testpkg;
                public class ConcreteB extends BaseB<ConcreteA, ConcreteB> {
                }
                """);

        return sources;
    }

    /**
     * Context A: abstract bounds, package {@code apipkg}.
     * The TypeSystem maps names to the abstract classes themselves (not concrete classes).
     */
    private static Map<String, String> apiBoundFixture() {
        Map<String, String> sources = new LinkedHashMap<>();

        sources.put("apipkg/package-info.java", """
                @fr.thegamerfarmer.jype.annotations.TypeSystem({
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name = "TYPE_A", bound = apipkg.BaseA.class),
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name = "TYPE_B", bound = apipkg.BaseB.class)
                })
                package apipkg;
                """);

        sources.put("apipkg/Base.java", """
                package apipkg;
                public abstract class Base<
                        TYPE_A extends BaseA<TYPE_A, TYPE_B>,
                        TYPE_B extends BaseB<TYPE_A, TYPE_B>> {
                }
                """);

        sources.put("apipkg/BaseA.java", """
                package apipkg;
                public abstract class BaseA<
                        TYPE_A extends BaseA<TYPE_A, TYPE_B>,
                        TYPE_B extends BaseB<TYPE_A, TYPE_B>>
                        extends Base<TYPE_A, TYPE_B> {
                }
                """);

        sources.put("apipkg/BaseB.java", """
                package apipkg;
                public abstract class BaseB<
                        TYPE_A extends BaseA<TYPE_A, TYPE_B>,
                        TYPE_B extends BaseB<TYPE_A, TYPE_B>>
                        extends Base<TYPE_A, TYPE_B> {
                }
                """);

        return sources;
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Concrete class in pkg2 extends raw class in pkg1 → Jype injects concrete type FQNs")
    void rawExtendsWithCrossPackageFqn() throws IOException {
        // Reproduces APIUHC's MyPlayer/Speed/Strength/Resistance pattern:
        //   concrete types (ConcreteA, ConcreteB) are in a different package
        //   from the leaf class (Consumer) → Jype must inject FQNs to avoid
        //   "cannot find symbol" errors caused by missing imports.
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("apkg/package-info.java", """
                @fr.thegamerfarmer.jype.annotations.TypeSystem({
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name = "TYPE_A", bound = Object.class),
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name = "TYPE_B", bound = Object.class)
                })
                package apkg;
                """);
        sources.put("apkg/Base.java", """
                package apkg;
                public abstract class Base<TYPE_A, TYPE_B> {
                }
                """);
        // ConcreteA and ConcreteB are in package bpkg.
        sources.put("bpkg/ConcreteA.java", """
                package bpkg;
                public class ConcreteA {
                }
                """);
        sources.put("bpkg/ConcreteB.java", """
                package bpkg;
                public class ConcreteB {
                }
                """);
        // Pivot: class with complete extends in cpkg (provides the concreteTypeMap).
        sources.put("cpkg/Pivot.java", """
                package cpkg;
                import bpkg.ConcreteA;
                import bpkg.ConcreteB;
                public abstract class Pivot extends apkg.Base<ConcreteA, ConcreteB> {
                }
                """);
        // Consumer is in dpkg and does not import ConcreteA or ConcreteB.
        // → Jype must inject bpkg.ConcreteA and bpkg.ConcreteB (FQNs) to avoid "cannot find symbol".
        sources.put("dpkg/Consumer.java", """
                package dpkg;
                public class Consumer extends apkg.Base {
                }
                """);
        JypeTestCompiler.compile(sources).assertSuccess();
    }

    @Test
    @DisplayName("extends Base (raw) → Jype injects type arguments → no rawtypes warning")
    void rawExtendsGetsTypeArgs() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>(baseFixture());
        sources.put("testpkg/Consumer.java", """
                package testpkg;
                public class Consumer extends Base {
                }
                """);
        JypeTestCompiler.compile(sources).assertSuccess();
    }

    @Test
    @DisplayName("extends Base (raw) without Jype → rawtypes warning → -Werror → failure")
    void rawExtendsFailsWithoutJype() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>(baseFixture());
        sources.put("testpkg/Consumer.java", """
                package testpkg;
                public class Consumer extends Base {
                }
                """);
        JypeTestCompiler.compileWithoutJype(sources).assertFailure();
    }

    @Test
    @DisplayName("extends ListenerBase<String> with E last → plugin injects TYPE_A,TYPE_B + processor injects bounds")
    void partialExtendsUserTypeAtEnd() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>(apiBoundFixture());

        // ListenerBase<TYPE_A, TYPE_B, E> extends Base<TYPE_A, TYPE_B>
        // MyListener declares its own TYPE_A, TYPE_B and writes extends ListenerBase<String>.
        // → Plugin: ownParams={TYPE_A,TYPE_B}, nonSharedCount=1 → extends ListenerBase<TYPE_A,TYPE_B,String>
        // → Processor: injects F-bounds on TYPE_A and TYPE_B.
        sources.put("apipkg/ListenerBase.java", """
                package apipkg;
                public abstract class ListenerBase<
                        TYPE_A extends BaseA<TYPE_A, TYPE_B>,
                        TYPE_B extends BaseB<TYPE_A, TYPE_B>,
                        E>
                        extends Base<TYPE_A, TYPE_B> {
                }
                """);

        sources.put("apipkg/MyListener.java", """
                package apipkg;
                public abstract class MyListener<TYPE_A, TYPE_B> extends ListenerBase<String> {
                }
                """);

        JypeTestCompiler.compile(sources).assertSuccess();
    }

    @Test
    @DisplayName("extends WrapperFirst<String> with E first → plugin injects TYPE_A,TYPE_B after it")
    void partialExtendsUserTypeAtStart() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>(apiBoundFixture());

        // WrapperFirst<E, TYPE_A, TYPE_B> extends Base<TYPE_A, TYPE_B>
        // MyWrapper declares its own TYPE_A, TYPE_B and writes extends WrapperFirst<String>.
        // → Plugin: ownParams={TYPE_A,TYPE_B}, nonSharedCount=1 → extends WrapperFirst<String,TYPE_A,TYPE_B>
        // → Processor: injects F-bounds on TYPE_A and TYPE_B.
        sources.put("apipkg/WrapperFirst.java", """
                package apipkg;
                public abstract class WrapperFirst<
                        E,
                        TYPE_A extends BaseA<TYPE_A, TYPE_B>,
                        TYPE_B extends BaseB<TYPE_A, TYPE_B>>
                        extends Base<TYPE_A, TYPE_B> {
                }
                """);

        sources.put("apipkg/MyWrapper.java", """
                package apipkg;
                public abstract class MyWrapper<TYPE_A, TYPE_B> extends WrapperFirst<String> {
                }
                """);

        JypeTestCompiler.compile(sources).assertSuccess();
    }

    @Test
    @DisplayName("extends TripleMiddle<String> with E in the middle → plugin places TYPE_A before and TYPE_B after it")
    void partialExtendsUserTypeInMiddle() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>(apiBoundFixture());

        // TripleMiddle<TYPE_A, E, TYPE_B> extends Base<TYPE_A, TYPE_B>
        // MyTriple declares its own TYPE_A, TYPE_B and writes extends TripleMiddle<String>.
        // → Plugin: ownParams={TYPE_A,TYPE_B}, nonSharedCount=1 → extends TripleMiddle<TYPE_A,String,TYPE_B>
        // → Processor: injects F-bounds on TYPE_A and TYPE_B.
        sources.put("apipkg/TripleMiddle.java", """
                package apipkg;
                public abstract class TripleMiddle<
                        TYPE_A extends BaseA<TYPE_A, TYPE_B>,
                        E,
                        TYPE_B extends BaseB<TYPE_A, TYPE_B>>
                        extends Base<TYPE_A, TYPE_B> {
                }
                """);

        sources.put("apipkg/MyTriple.java", """
                package apipkg;
                public abstract class MyTriple<TYPE_A, TYPE_B> extends TripleMiddle<String> {
                }
                """);

        JypeTestCompiler.compile(sources).assertSuccess();
    }

    @Test
    @DisplayName("Context A: Jype injects F-bounds on TYPE_A, TYPE_B AND completes extends")
    void fBoundedTypeParamsInjected() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>(apiBoundFixture());

        // AbstractSub<TYPE_A, TYPE_B> extends Base
        // Jype must inject:
        //   TYPE_A extends BaseA<TYPE_A, TYPE_B>
        //   TYPE_B extends BaseB<TYPE_A, TYPE_B>
        // AND complete: extends Base<TYPE_A, TYPE_B>
        sources.put("apipkg/AbstractSub.java", """
                package apipkg;
                public abstract class AbstractSub<TYPE_A, TYPE_B> extends Base {
                }
                """);

        JypeTestCompiler.compile(sources).assertSuccess();
    }

    @Test
    @DisplayName("Multiple @TypeSystem annotations in one compilation apply to their respective packages")
    void multipleTypeSystemsAreScopedToTheirPackages() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("alpha/package-info.java", """
                @fr.thegamerfarmer.jype.annotations.TypeSystem({
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name = "TYPE", bound = Object.class)
                })
                package alpha;
                """);
        sources.put("alpha/Base.java", """
                package alpha;
                public abstract class Base<TYPE> {}
                """);
        sources.put("alpha/Consumer.java", """
                package alpha;
                public class Consumer extends Base {}
                """);

        sources.put("beta/package-info.java", """
                @fr.thegamerfarmer.jype.annotations.TypeSystem({
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name = "TYPE", bound = Object.class)
                })
                package beta;
                """);
        sources.put("beta/Base.java", """
                package beta;
                public abstract class Base<TYPE> {}
                """);
        sources.put("beta/Consumer.java", """
                package beta;
                public class Consumer extends Base {}
                """);

        JypeTestCompiler.compile(sources).assertSuccess();
    }

    @Test
    @DisplayName("Classes with the same name in different packages are resolved by FQN")
    void sameSimpleNameClassesResolveByQualifiedName() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("alpha/package-info.java", """
                @fr.thegamerfarmer.jype.annotations.TypeSystem({
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name = "TYPE_A", bound = Object.class)
                })
                package alpha;
                """);
        sources.put("alpha/Base.java", """
                package alpha;
                public class Base<TYPE_A, EXTRA_A, EXTRA_B> {}
                """);
        sources.put("alpha/Child.java", """
                package alpha;
                public class Child<TYPE_A> extends alpha.Base<String, Integer> {}
                """);

        sources.put("beta/package-info.java", """
                @fr.thegamerfarmer.jype.annotations.TypeSystem({
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name = "TYPE_A", bound = Object.class)
                })
                package beta;
                """);
        sources.put("beta/Base.java", """
                package beta;
                public class Base<TYPE_A, EXTRA> {}
                """);
        sources.put("beta/Child.java", """
                package beta;
                public class Child<TYPE_A> extends beta.Base<String> {}
                """);

        JypeTestCompiler.compile(sources).assertSuccess();
    }

    @Test
    @DisplayName("Override of a name missing from the parent TypeSystem produces an explicit diagnostic")
    void unknownTypeSystemOverrideIsRejected() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("parentpkg/package-info.java", """
                @fr.thegamerfarmer.jype.annotations.TypeSystem({
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name = "TYPE_A", bound = Object.class)
                })
                package parentpkg;
                """);
        sources.put("childpkg/package-info.java", """
                @fr.thegamerfarmer.jype.annotations.TypeSystem(
                    inherit = "parentpkg",
                    overrides = {
                        @fr.thegamerfarmer.jype.annotations.TypeDef(name = "TYPE_MISSING", bound = Object.class)
                    }
                )
                package childpkg;
                """);
        sources.put("childpkg/Consumer.java", """
                package childpkg;
                public class Consumer {}
                """);

        var result = JypeTestCompiler.compilePermissive(sources);
        result.assertFailure();
        assertTrue(result.hasError("does not exist in the inherited @TypeSystem"),
                "Expected a diagnostic for an unknown TypeSystem override");
    }

    @Test
    @DisplayName("Duplicate @TypeDef names produce an explicit diagnostic")
    void duplicateTypeSystemDefinitionsAreRejected() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("testpkg/package-info.java", """
                @fr.thegamerfarmer.jype.annotations.TypeSystem({
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name = "TYPE_A", bound = Object.class),
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name = "TYPE_A", bound = String.class)
                })
                package testpkg;
                """);
        sources.put("testpkg/Consumer.java", """
                package testpkg;
                public class Consumer {}
                """);

        var result = JypeTestCompiler.compilePermissive(sources);
        result.assertFailure();
        assertTrue(result.hasError("Duplicate @TypeDef name"),
                "Expected a diagnostic for duplicate TypeDef names");
    }

    @Test
    @DisplayName("Primitive @TypeDef bound is rejected with an explicit diagnostic")
    void primitiveTypeSystemBoundIsRejected() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("testpkg/package-info.java", """
                @fr.thegamerfarmer.jype.annotations.TypeSystem({
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name = "TYPE_A", bound = int.class)
                })
                package testpkg;
                """);
        sources.put("testpkg/Consumer.java", """
                package testpkg;
                public class Consumer {}
                """);

        var result = JypeTestCompiler.compilePermissive(sources);
        result.assertFailure();
        assertTrue(result.hasError("must be a resolvable declared class or interface"),
                "Expected a diagnostic for a primitive TypeDef bound");
    }

    @Test
    @DisplayName("Incompatible concrete bindings produce a diagnostic")
    void conflictingConcreteBindingsAreRejected() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("conflictpkg/package-info.java", """
                @fr.thegamerfarmer.jype.annotations.TypeSystem({
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name = "TYPE_A", bound = Object.class)
                })
                package conflictpkg;
                """);
        sources.put("conflictpkg/APIBase.java", """
                package conflictpkg;
                public abstract class APIBase<TYPE_A> {}
                """);
        sources.put("conflictpkg/First.java", """
                package conflictpkg;
                public class First {}
                """);
        sources.put("conflictpkg/Second.java", """
                package conflictpkg;
                public class Second {}
                """);
        sources.put("conflictpkg/FirstBinding.java", """
                package conflictpkg;
                public abstract class FirstBinding extends APIBase<First> {}
                """);
        sources.put("conflictpkg/SecondBinding.java", """
                package conflictpkg;
                public abstract class SecondBinding extends APIBase<Second> {}
                """);

        var result = JypeTestCompiler.compilePermissive(sources);

        result.assertFailure();
        assertTrue(result.hasError("Conflicting concrete bindings for TYPE_A"),
                "Expected a diagnostic when two source types bind the same managed parameter differently; got: "
                        + result.diagnostics());
    }

    @Test
    @DisplayName("@TypeSystem inheritance: childpkg inherits from parentpkg and overrides bounds → Consumer compiles with Jype")
    void typeSystemInheritance() throws IOException {
        // Pass 1: compile the parent package without Jype (as a library).
        Map<String, String> parentSources = new LinkedHashMap<>();

        parentSources.put("parentpkg/package-info.java", """
                @fr.thegamerfarmer.jype.annotations.TypeSystem({
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name = "TYPE_A", bound = parentpkg.DefaultA.class),
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name = "TYPE_B", bound = parentpkg.DefaultB.class)
                })
                package parentpkg;
                """);

        parentSources.put("parentpkg/Base.java", """
                package parentpkg;
                public abstract class Base<
                        TYPE_A extends BaseA<TYPE_A, TYPE_B>,
                        TYPE_B extends BaseB<TYPE_A, TYPE_B>> {
                }
                """);

        parentSources.put("parentpkg/BaseA.java", """
                package parentpkg;
                public abstract class BaseA<
                        TYPE_A extends BaseA<TYPE_A, TYPE_B>,
                        TYPE_B extends BaseB<TYPE_A, TYPE_B>>
                        extends Base<TYPE_A, TYPE_B> {
                }
                """);

        parentSources.put("parentpkg/BaseB.java", """
                package parentpkg;
                public abstract class BaseB<
                        TYPE_A extends BaseA<TYPE_A, TYPE_B>,
                        TYPE_B extends BaseB<TYPE_A, TYPE_B>>
                        extends Base<TYPE_A, TYPE_B> {
                }
                """);

        parentSources.put("parentpkg/DefaultA.java", """
                package parentpkg;
                public class DefaultA extends BaseA<DefaultA, DefaultB> {
                }
                """);

        parentSources.put("parentpkg/DefaultB.java", """
                package parentpkg;
                public class DefaultB extends BaseB<DefaultA, DefaultB> {
                }
                """);

        // Pass 2: compile the child package with Jype.
        Map<String, String> childSources = new LinkedHashMap<>();

        childSources.put("childpkg/package-info.java", """
                @fr.thegamerfarmer.jype.annotations.TypeSystem(
                    inherit = "parentpkg",
                    overrides = {
                        @fr.thegamerfarmer.jype.annotations.TypeDef(name = "TYPE_A", bound = childpkg.ChildA.class),
                        @fr.thegamerfarmer.jype.annotations.TypeDef(name = "TYPE_B", bound = childpkg.ChildB.class)
                    }
                )
                package childpkg;
                """);

        childSources.put("childpkg/ChildA.java", """
                package childpkg;
                public class ChildA extends parentpkg.BaseA<ChildA, ChildB> {
                }
                """);

        childSources.put("childpkg/ChildB.java", """
                package childpkg;
                public class ChildB extends parentpkg.BaseB<ChildA, ChildB> {
                }
                """);

        childSources.put("childpkg/Consumer.java", """
                package childpkg;
                public class Consumer extends parentpkg.Base {
                }
                """);

        JypeTestCompiler.compileTwoPass(parentSources, childSources).assertSuccess();
    }

    @Test
    @DisplayName("Inherited @TypeSystem without overrides uses the parent's bounds")
    void typeSystemInheritanceWithoutOverrides() throws IOException {
        Map<String, String> parentSources = new LinkedHashMap<>();
        parentSources.put("parentpkg/package-info.java", """
                @fr.thegamerfarmer.jype.annotations.TypeSystem({
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name = "TYPE_A", bound = parentpkg.ConcreteA.class),
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name = "TYPE_B", bound = parentpkg.ConcreteB.class)
                })
                package parentpkg;
                """);
        parentSources.put("parentpkg/Base.java", """
                package parentpkg;
                public abstract class Base<TYPE_A, TYPE_B> {}
                """);
        parentSources.put("parentpkg/ConcreteA.java", """
                package parentpkg;
                public class ConcreteA {}
                """);
        parentSources.put("parentpkg/ConcreteB.java", """
                package parentpkg;
                public class ConcreteB {}
                """);

        Map<String, String> childSources = new LinkedHashMap<>();
        childSources.put("childpkg/package-info.java", """
                @fr.thegamerfarmer.jype.annotations.TypeSystem(inherit = "parentpkg")
                package childpkg;
                """);
        childSources.put("childpkg/Consumer.java", """
                package childpkg;
                public class Consumer extends parentpkg.Base {}
                """);
        childSources.put("childpkg/BodyOnly.java", """
                package childpkg;
                public class BodyOnly {
                    TYPE_A value;
                }
                """);

        JypeTestCompiler.compileTwoPass(parentSources, childSources).assertSuccess();
    }

    @Test
    @DisplayName("extends Base<ConcreteA, ConcreteB> already complete → Jype leaves it unchanged and compiles normally")
    void alreadyFullyParameterizedUnchanged() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>(baseFixture());
        sources.put("testpkg/Consumer.java", """
                package testpkg;
                public class Consumer extends Base<ConcreteA, ConcreteB> {
                }
                """);
        JypeTestCompiler.compile(sources).assertSuccess();
    }

    @Test
    @DisplayName("Inner class: Inner extends Base (raw) → Jype processes inner classes")
    void innerClassProcessed() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>(baseFixture());
        sources.put("testpkg/Outer.java", """
                package testpkg;
                public class Outer {
                    public static class Inner extends Base {
                    }
                }
                """);
        JypeTestCompiler.compile(sources).assertSuccess();
    }

    @Test
    @DisplayName("import testpkg.* in otherpkg → resolution through wildcard import → Consumer compiles")
    void wildcardImportResolves() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>(baseFixture());
        sources.put("otherpkg/Consumer.java", """
                package otherpkg;
                import testpkg.*;
                public class Consumer extends Base {
                }
                """);
        JypeTestCompiler.compile(sources).assertSuccess();
    }

    @Test
    @DisplayName("Class with only SOME Jype parameters (TYPE_A but not TYPE_B) extends raw → Jype skips injection; compilePermissive succeeds")
    void partialJypeParamsSkipsInjection() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>(apiBoundFixture());

        // PrivateBase<TYPE_A, E> declares only TYPE_A among the Jype parameters.
        // TYPE_B is missing and its bound (BaseB) has type parameters → raw injection
        // would violate the F-bounds (raw is a supertype, not a subtype). Jype skips injection.
        // PrivateBase inherits Base raw → rawtypes warning, not an error → compilePermissive ✓
        sources.put("apipkg/PrivateBase.java", """
                package apipkg;
                abstract class PrivateBase<TYPE_A, E> extends Base {
                }
                """);

        JypeTestCompiler.compilePermissive(sources).assertSuccess();
    }

    @Test
    @DisplayName("Raw field → Jype injects type arguments into the field; -Xlint:rawtypes passes")
    void fieldTypeArgsInjected() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>(baseFixture());

        // Manager<TYPE_A, TYPE_B> uses the TypeSystem names.
        sources.put("testpkg/Manager.java", """
                package testpkg;
                public class Manager<TYPE_A, TYPE_B> {}
                """);

        // Holder declares a raw "Manager mgr" field;
        // Jype must inject type arguments so -Xlint:rawtypes does not report an error.
        sources.put("testpkg/Holder.java", """
                package testpkg;
                public abstract class Holder<
                        TYPE_A extends BaseA<TYPE_A, TYPE_B>,
                        TYPE_B extends BaseB<TYPE_A, TYPE_B>>
                        extends Base<TYPE_A, TYPE_B> {
                    public Manager mgr;
                }
                """);

        sources.put("testpkg/Consumer.java", """
                package testpkg;
                public class Consumer extends Holder {}
                """);

        JypeTestCompiler.compile(sources).assertSuccess();
    }

    @Test
    @DisplayName("Pass 1 + buildMixedTypeArgs injection case: SubAbstract<TYPE_A> extends PartialBase<TYPE_A, String>")
    void mixedTypeArgsCasInjection() throws IOException {
        // apiBoundFixture: TYPE_A → BaseA (which has its own parameters), TYPE_B → BaseB.
        // F-bounds CAN be injected into PartialBase.
        Map<String, String> sources = new LinkedHashMap<>(apiBoundFixture());

        // PartialBase<TYPE_A, E> has only TYPE_A among the Jype parameters.
        // Pass 1: Jype injects missing TYPE_B. PartialBase becomes <TYPE_A, E, TYPE_B>.
        // Pass 2: F-bounds are injected on TYPE_A and TYPE_B.
        sources.put("apipkg/PartialBase.java", """
                package apipkg;
                public abstract class PartialBase<TYPE_A, E> extends Base {
                }
                """);

        // SubAbstract<TYPE_A> supplies 2 arguments: TYPE_A (owned; Jype replaces it with its TypeVar)
        // and String (for E). originalCount = 2. providedArgs.size() = 2.
        // → buildMixedTypeArgs injection path: TYPE_B is inserted, String is retained for E.
        sources.put("apipkg/SubAbstract.java", """
                package apipkg;
                public abstract class SubAbstract<TYPE_A> extends PartialBase<TYPE_A, String> {
                }
                """);

        JypeTestCompiler.compile(sources).assertSuccess();
    }

    @Test
    @DisplayName("Wrong argument count (2 non-Jype when nonJypeCount=1) → Jype bails out, javac errors → no NPE")
    void wrongArgCountDoesNotNPE() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>(baseFixture());

        // Listener has 3 parameters: TYPE_A, TYPE_B, E.
        // nonJypeCount = 1 (E only).
        // BadConsumer supplies 2 non-Jype arguments → mismatch → Jype returns null and bails out.
        sources.put("testpkg/Listener.java", """
                package testpkg;
                public abstract class Listener<
                        TYPE_A extends BaseA<TYPE_A, TYPE_B>,
                        TYPE_B extends BaseB<TYPE_A, TYPE_B>,
                        E>
                        extends Base<TYPE_A, TYPE_B> {
                }
                """);

        sources.put("testpkg/BadConsumer.java", """
                package testpkg;
                public class BadConsumer extends Listener<String, Integer> {
                }
                """);

        // Jype emits a WARNING and bails out → javac errors on the wrong argument count.
        // Compilation fails cleanly, without a NullPointerException.
        JypeTestCompiler.compilePermissive(sources).assertFailure();
    }

    @Test
    @DisplayName("Abstract class with no declared Jype parameters uses TYPE_A/TYPE_B in its body → plugin injects parameters, processor injects bounds")
    void bodyUsageInjectsTypeParams() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>(apiBoundFixture());

        // AbstractHolder declares no Jype parameters but uses TYPE_A and TYPE_B
        // in a field and method.
        // → JypePlugin (AFTER_PARSE) detects the usages → injects TYPE_A and TYPE_B.
        // → TypeInjectionPass (processor) injects the F-bounds.
        sources.put("apipkg/AbstractHolder.java", """
                package apipkg;
                public abstract class AbstractHolder extends Base {
                    protected TYPE_A player;
                    public abstract void setPlayer(TYPE_A player);
                    public abstract TYPE_B getRole();
                }
                """);

        JypeTestCompiler.compile(sources).assertSuccess();
    }

    @Test
    @DisplayName("Concrete (non-abstract) class with no declared Jype parameters uses TYPE_A in its body → plugin injects parameters as for an abstract class")
    void concreteClassBodyUsageInjectsTypeParams() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>(apiBoundFixture());

        // ConcreteHolder is a concrete (non-abstract) class that uses TYPE_A in
        // its fields and methods without declaring Jype parameters.
        // → Before the fix, the "abstract only" restriction in injectBodyDetectedTypeParams
        //   prevented injection → "cannot find symbol: class TYPE_A".
        // → After the fix, JypePlugin handles concrete classes the same way.
        sources.put("apipkg/ConcreteHolder.java", """
                package apipkg;
                public class ConcreteHolder extends Base {
                    protected TYPE_A item;
                    public void setItem(TYPE_A item) { this.item = item; }
                    public TYPE_A getItem() { return this.item; }
                }
                """);

        JypeTestCompiler.compile(sources).assertSuccess();
    }

    @Test
    @DisplayName("Generic method <TYPE_A> with managed TYPE_A → TYPE_A is method-level; Jype does not inject class parameters")
    void methodLevelTypeParamNotInjectedAsClassParam() throws IOException {
        // baseFixture has ConcreteA/ConcreteB → buildConcreteTypeMap completes Consumer's
        // extends with Base<ConcreteA,ConcreteB> → no rawtypes warning on Consumer.
        Map<String, String> sources = new LinkedHashMap<>(baseFixture());

        // Service has a generic <TYPE_A> method: TYPE_A is a METHOD type parameter.
        // Without the fix: findUsedManagedNames detects TYPE_A in the return type →
        //   Jype injects TYPE_A,TYPE_B as CLASS parameters → Service<TYPE_A,TYPE_B>
        //   → "new Service()" in Consumer is rawtypes → -Werror → failure.
        // With the fix: TYPE_A is excluded because it is a method parameter → no injection
        //   → Service is not generic → "new Service()" is OK → success.
        sources.put("testpkg/Service.java", """
                package testpkg;
                import java.util.Map;
                import java.util.HashMap;
                public class Service {
                    public <TYPE_A> Map<String, TYPE_A> getAll() { return new HashMap<>(); }
                }
                """);

        // Consumer extends Base (→ completed as Base<ConcreteA,ConcreteB> by JypePlugin)
        // and instantiates Service. If Service becomes generic (bug), new Service() is rawtypes.
        sources.put("testpkg/Consumer.java", """
                package testpkg;
                public class Consumer extends Base {
                    private Service service = new Service();
                }
                """);

        JypeTestCompiler.compile(sources).assertSuccess();
    }

    @Test
    @DisplayName("Class with no Jype parameters extends Abstract<E> (only unmanaged argument) → Jype injects Jype parameters and fixes extends")
    void concreteExtendsAbstractWithOnlyNonManagedArg() throws IOException {
        // apiBoundFixture: TYPE_A → BaseA (abstract, with type parameters) →
        // TypeInjectionPass can inject F-bounds into EventSub.TYPE_A/TYPE_B,
        // allowing Attr to check the extends bounds correctly.
        Map<String, String> sources = new LinkedHashMap<>(apiBoundFixture());

        // AbstractListener<TYPE_A, TYPE_B, E>: 2 Jype parameters + 1 unmanaged (E).
        sources.put("apipkg/AbstractListener.java", """
                package apipkg;
                public abstract class AbstractListener<
                        TYPE_A extends BaseA<TYPE_A, TYPE_B>,
                        TYPE_B extends BaseB<TYPE_A, TYPE_B>,
                        E>
                        extends Base<TYPE_A, TYPE_B> {
                    protected abstract void handle(E event);
                }
                """);

        // EventSub supplies only String (for E):
        // Without the fix: buildFixedArgs → null (nonSharedCount=3, provided=1) → "wrong number of type args".
        // With the fix: tryInjectManagedParams injects TYPE_A,TYPE_B into EventSub,
        //   then buildFixedArgs → [TYPE_A, TYPE_B, String] → EventSub<TYPE_A,TYPE_B> extends
        //   AbstractListener<TYPE_A, TYPE_B, String>
        //   TypeInjectionPass injects the bounds (TYPE_A extends BaseA<TYPE_A,TYPE_B>, etc.).
        //   → Attr: EventSub.TYPE_A satisfies the bound → success.
        sources.put("apipkg/EventSub.java", """
                package apipkg;
                public class EventSub extends AbstractListener<String> {
                    protected void handle(String event) {}
                }
                """);

        JypeTestCompiler.compile(sources).assertSuccess();
    }

    @Test
    @DisplayName("Abstract class with no Jype parameters extends a raw Jype class, with no body usage → injection based on extends")
    void abstractNoBodyUsageRawExtends() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>(apiBoundFixture());

        // AbstractSub does not use TYPE_A/TYPE_B in its body AND has no parameters.
        // JypePlugin cannot detect body usage, but TypeInjectionPass (zero-ownership pass)
        // must still inject through the abstract + raw-extends feature.
        // This test checks detection through the extends clause.
        sources.put("apipkg/AbstractSub.java", """
                package apipkg;
                public abstract class AbstractSub extends Base {
                }
                """);

        JypeTestCompiler.compile(sources).assertSuccess();
    }

    @Test
    @DisplayName("Partially parameterized type-parameter bound → propagateBoundDependencies injects missing Jype parameters and fixTypeParamBounds completes the bound")
    void typeParamBoundCompletedViaBoundsPropagation() throws IOException {
        // Simple TypeSystem: J_A and J_B map to Object (no F-bounds).
        // TypeInjectionPass cannot inject bounds (Object has no type parameters).
        // The goal is to test only type-parameter bound completion by
        // propagateBoundDependencies + fixTypeParamBounds.
        Map<String, String> sources = new LinkedHashMap<>();

        sources.put("bndpkg/package-info.java", """
                @fr.thegamerfarmer.jype.annotations.TypeSystem({
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name = "J_A", bound = Object.class),
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name = "J_B", bound = Object.class)
                })
                package bndpkg;
                """);

        // APIPower uses J_A in its body → plugin injects J_A and J_B.
        // Result: APIPower<J_A, J_B>.
        sources.put("bndpkg/APIPower.java", """
                package bndpkg;
                public abstract class APIPower {
                    protected J_A player;
                }
                """);

        // Container<T extends APIPower<T>>: T's bound is partial (1 argument for APIPower's 2 parameters).
        // Container does NOT use J_A/J_B in its body.
        // → injectBodyDetectedTypeParams : rien
        // → propagateBoundDependencies : T's bound APIPower<T> → APIPower a [J_A, J_B] →
        //     position 1 (J_B) is missing → injects J_B into Container.
        // → fixTypeParamBounds : APIPower<T> → APIPower<T, J_B>
        // → Container<T extends APIPower<T, J_B>, J_B>
        sources.put("bndpkg/Container.java", """
                package bndpkg;
                public abstract class Container<T extends APIPower<T>> {
                }
                """);

        JypeTestCompiler.compile(sources).assertSuccess();
    }

    @Test
    @DisplayName("Abstract class with no body usage and raw extends to a fully managed class → Jype injects parameters and completes extends")
    void abstractRawExtendsAllManagedParams() throws IOException {
        // Reproduces the API UHC Role/APIRole pattern:
        //   APIRole uses J_A in its body → plugin injects J_A and J_B.
        //   Role extends APIRole raw — an abstract class with no body usage.
        //   → propagateBoundDependencies: APIRole is fully managed → injects J_A, J_B into Role.
        //   → fixClassDecl (all-managed abstract path): Role extends APIRole<J_A, J_B>.
        //   No concrete type (e.g. MyPlayer) should appear in Role.
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("rolepkg/package-info.java", """
                @fr.thegamerfarmer.jype.annotations.TypeSystem({
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name = "J_A", bound = Object.class),
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name = "J_B", bound = Object.class)
                })
                package rolepkg;
                """);
        sources.put("rolepkg/APIRole.java", """
                package rolepkg;
                public abstract class APIRole {
                    protected J_A player;
                }
                """);
        sources.put("rolepkg/Role.java", """
                package rolepkg;
                public abstract class Role extends APIRole {
                }
                """);
        JypeTestCompiler.compile(sources).assertSuccess();
    }

    @Test
    @DisplayName("Nested wildcard: Sub<?> in completed extends → expandAllNestedWildcards → Sub<?,?,?>")
    void nestedWildcardExpandedInExtendsArg() throws IOException {
        // Reproduces the SubPower/MasterPower pattern:
        //   APIMaster<SUB_T> uses J_A → injects J_A, J_B → APIMaster<SUB_T, J_A, J_B>
        //   Master extends APIMaster<Sub<?>> → tryInjectManagedParams → Master<J_A,J_B>
        //     extends APIMaster<Sub<?>, J_A, J_B>
        //   Sub<M extends Master> extends APISub<Sub<?>, M> :
        //     APISub<SUB,M> obtient J_A,J_B → APISub<SUB,M,J_A,J_B>
        //     tryInjectManagedParams → Sub<M,J_A,J_B> extends APISub<Sub<?>,M,J_A,J_B>
        //   Post-pass: Sub<?> → Sub<?,?,?,> (3 parameters).
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("subpkg/package-info.java", """
                @fr.thegamerfarmer.jype.annotations.TypeSystem({
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name = "J_A", bound = Object.class),
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name = "J_B", bound = Object.class)
                })
                package subpkg;
                """);
        // APIMaster uses J_A → injects J_A, J_B.
        sources.put("subpkg/APIMaster.java", """
                package subpkg;
                public abstract class APIMaster<API_SUB> {
                    public abstract void give(J_A player);
                }
                """);
        // APISub does not use J_A/J_B in its body.
        sources.put("subpkg/APISub.java", """
                package subpkg;
                public abstract class APISub<SUB, MASTER> {
                }
                """);
        // Master extends APIMaster<Sub<?>> → parameters injected through tryInjectManagedParams.
        sources.put("subpkg/Master.java", """
                package subpkg;
                public abstract class Master extends APIMaster<Sub<?>> {
                }
                """);
        // Sub<M extends Master> extends APISub<Sub<?>, M>
        sources.put("subpkg/Sub.java", """
                package subpkg;
                public abstract class Sub<M extends Master> extends APISub<Sub<?>, M> {
                }
                """);
        JypeTestCompiler.compile(sources).assertSuccess();
    }

    @Test
    @DisplayName("Explicitly provided shared parameter + managed trailing parameters → tryBuildTrailingManagedArgs completes extends")
    void trailingManagedArgsAfterExplicitSharedParam() throws IOException {
        // Reproduces the SubPower case where a type parameter has the SAME name as the superclass parameter:
        //   APISub<API_SUB_POWER, MASTER_POWER, J_A, J_B> — after J_A, J_B are propagated
        //   Sub<MASTER_POWER extends Master> → MASTER_POWER belongs to both ownParams AND superParams.
        //     buildFixedArgs: nonShared = 1 (API_SUB_POWER), provided = 2 → fails.
        //     tryBuildTrailingManagedArgs: trailing [J_A, J_B] ∈ ownParams → [Sub<?>, MASTER_POWER, J_A, J_B].
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("trailpkg/package-info.java", """
                @fr.thegamerfarmer.jype.annotations.TypeSystem({
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name = "J_A", bound = Object.class),
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name = "J_B", bound = Object.class)
                })
                package trailpkg;
                """);
        // APIMaster uses J_A → injects J_A, J_B → APIMaster<API_SUB, J_A, J_B>.
        sources.put("trailpkg/APIMaster.java", """
                package trailpkg;
                public abstract class APIMaster<API_SUB> {
                    public abstract void give(J_A player);
                }
                """);
        // APISub<SUB, MASTER_POWER> → after propagation from APIMaster: APISub<SUB, MASTER_POWER, J_A, J_B>.
        sources.put("trailpkg/APISub.java", """
                package trailpkg;
                public abstract class APISub<SUB, MASTER_POWER extends APIMaster<SUB>> {
                }
                """);
        // Master extends APIMaster<Sub<?>> → J_A, J_B injected → Master<J_A, J_B>.
        sources.put("trailpkg/Master.java", """
                package trailpkg;
                public abstract class Master extends APIMaster<Sub<?>> {
                }
                """);
        // Sub<MASTER_POWER extends Master> extends APISub<Sub<?>, MASTER_POWER>
        // MASTER_POWER (Sub parameter) == MASTER_POWER (APISub parameter) → "shared" in buildFixedArgs.
        // → tryBuildTrailingManagedArgs must complete: [Sub<?>, MASTER_POWER, J_A, J_B].
        sources.put("trailpkg/Sub.java", """
                package trailpkg;
                public abstract class Sub<MASTER_POWER extends Master> extends APISub<Sub<?>, MASTER_POWER> {
                }
                """);
        JypeTestCompiler.compile(sources).assertSuccess();
    }

    @Test
    @DisplayName("Class uses a managed name ONLY in a method body → Jype injects parameters through body scanning")
    void bodyOnlyLocalVarInjectsTypeParams() throws IOException {
        // Reproduces the API UHC api_commands.Role pattern:
        //   Cmd extends APIBase raw (no managed parameters in fields/signatures)
        //   but uses J_A as a local variable type in execute():
        //     J_A item = null;
        // Without the fix: findUsedManagedNames ignores the body → no injection → unknown J_A → error.
        // With the fix: body scanning detects J_A → injects J_A, J_B → Cmd<J_A,J_B>.
        //               Sub extends Cmd → Jype replaces them with concrete types.
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("bodypkg/package-info.java", """
                @fr.thegamerfarmer.jype.annotations.TypeSystem({
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name = "J_A", bound = Object.class),
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name = "J_B", bound = Object.class)
                })
                package bodypkg;
                """);
        sources.put("bodypkg/APIBase.java", """
                package bodypkg;
                public abstract class APIBase {
                    protected J_A item;
                }
                """);
        // Pivot: provides the concreteTypeMap (J_A→ConcreteJA, J_B→ConcreteJB).
        sources.put("bodypkg/ConcreteJA.java", """
                package bodypkg;
                public class ConcreteJA {}
                """);
        sources.put("bodypkg/ConcreteJB.java", """
                package bodypkg;
                public class ConcreteJB {}
                """);
        sources.put("bodypkg/Pivot.java", """
                package bodypkg;
                public abstract class Pivot extends APIBase<ConcreteJA, ConcreteJB> {}
                """);
        // Cmd uses J_A ONLY in its body (local variable) — no field or signature.
        // → Body scanning must trigger injection of J_A and J_B into Cmd.
        sources.put("bodypkg/Cmd.java", """
                package bodypkg;
                public class Cmd extends APIBase {
                    public void execute() {
                        J_A item = null;
                    }
                }
                """);
        // Sub extends Cmd raw → Jype replaces them with concrete types.
        sources.put("bodypkg/Sub.java", """
                package bodypkg;
                public class Sub extends Cmd {
                }
                """);
        JypeTestCompiler.compile(sources).assertSuccess();
    }

    @Test
    @DisplayName("Abstract binding class in concreteSimpleNameSet stays non-generic — gets Color-Enum concrete extends")
    void abstractBindingClassGetsConcreteExtends() throws IOException {
        // Reproduces the roles.Role / APIRole pattern from APIUHC:
        //   APIJa/APIJb have explicit F-recursive type params (user-declared).
        //   BindingA extends APIJa raw — like roles.Role extends APIRole.
        //   Anchor extends APIJa<BindingA, BindingB> → BindingA enters concreteSimpleNameSet.
        // Without fix: BindingA becomes generic via propagation → TypeInjectionPass injects
        //   F-recursive bounds → raw BindingA used in Anchor fails "not within bounds".
        // With fix: concreteSimpleNameSet protects BindingA → stays non-generic →
        //   buildConcreteArgs gives BindingA extends APIJa<BindingA, BindingB> (Color-Enum) → ✓
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("bindpkg/package-info.java", """
                @fr.thegamerfarmer.jype.annotations.TypeSystem({
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name = "J_A", bound = bindpkg.APIJa.class),
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name = "J_B", bound = bindpkg.APIJb.class)
                })
                package bindpkg;
                """);
        sources.put("bindpkg/APIJa.java", """
                package bindpkg;
                public abstract class APIJa<J_A extends APIJa<J_A, J_B>, J_B extends APIJb<J_A, J_B>> {}
                """);
        sources.put("bindpkg/APIJb.java", """
                package bindpkg;
                public abstract class APIJb<J_A extends APIJa<J_A, J_B>, J_B extends APIJb<J_A, J_B>> {}
                """);
        // BindingA: abstract, extends APIJa raw — should NOT become generic
        sources.put("bindpkg/BindingA.java", """
                package bindpkg;
                public abstract class BindingA extends APIJa {
                }
                """);
        sources.put("bindpkg/BindingB.java", """
                package bindpkg;
                public abstract class BindingB extends APIJb {
                }
                """);
        // Anchor: establishes BindingA/BindingB as concrete types for J_A/J_B
        sources.put("bindpkg/Anchor.java", """
                package bindpkg;
                public abstract class Anchor extends APIJa<BindingA, BindingB> {
                }
                """);
        JypeTestCompiler.compile(sources).assertSuccess();
    }

    @Test
    @DisplayName("Enhanced for loop variable uses managed type → deep body scanning injects params")
    void enhancedForLoopTriggersBodyInjection() throws IOException {
        // Old scanner: only top-level JCVariableDecl statements — missed for-each loop vars.
        // New recursive scanner: catches JCEnhancedForLoop.var.vartype.
        // FinalHeal uses J_A ONLY as the for-each variable type → needs deep scanning.
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("forpkg/package-info.java", """
                @fr.thegamerfarmer.jype.annotations.TypeSystem({
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name = "J_A", bound = Object.class),
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name = "J_B", bound = Object.class)
                })
                package forpkg;
                """);
        sources.put("forpkg/APIBase.java", """
                package forpkg;
                import java.util.List;
                import java.util.ArrayList;
                public abstract class APIBase<J_A, J_B> {
                    protected List<J_A> players = new ArrayList<>();
                    public List<J_A> getPlayers() { return players; }
                }
                """);
        sources.put("forpkg/ConcreteJA.java", """
                package forpkg;
                public class ConcreteJA {}
                """);
        sources.put("forpkg/ConcreteJB.java", """
                package forpkg;
                public class ConcreteJB {}
                """);
        sources.put("forpkg/Pivot.java", """
                package forpkg;
                public abstract class Pivot extends APIBase<ConcreteJA, ConcreteJB> {}
                """);
        // FinalHeal: J_A appears ONLY in the enhanced for loop variable type.
        // getPlayers() is inherited (return type has J_A in APIBase, not FinalHeal scope).
        // Without deep scanning: no top-level JCVariableDecl with J_A → no injection → "cannot find symbol J_A".
        // With deep scanning: JCEnhancedForLoop.var.vartype = J_A → inject J_A, J_B → compiles.
        sources.put("forpkg/FinalHeal.java", """
                package forpkg;
                public class FinalHeal extends APIBase {
                    public void execute() {
                        for (J_A player : getPlayers()) {
                        }
                    }
                }
                """);
        JypeTestCompiler.compile(sources).assertSuccess();
    }

    @Test
    @DisplayName("Nested if-block variable uses managed type → deep body scanning injects params")
    void nestedIfBlockTriggersBodyInjection() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("ifpkg/package-info.java", """
                @fr.thegamerfarmer.jype.annotations.TypeSystem({
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name = "J_A", bound = Object.class),
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name = "J_B", bound = Object.class)
                })
                package ifpkg;
                """);
        sources.put("ifpkg/APIBase.java", """
                package ifpkg;
                public abstract class APIBase<J_A, J_B> {
                    public J_A getItem() { return null; }
                }
                """);
        sources.put("ifpkg/ConcreteJA.java", """
                package ifpkg;
                public class ConcreteJA {}
                """);
        sources.put("ifpkg/ConcreteJB.java", """
                package ifpkg;
                public class ConcreteJB {}
                """);
        sources.put("ifpkg/Pivot.java", """
                package ifpkg;
                public abstract class Pivot extends APIBase<ConcreteJA, ConcreteJB> {}
                """);
        // Handler: J_A appears ONLY inside an if-block (JCVariableDecl nested in JCIf.thenpart).
        // Old top-level scanner: top-level statement is JCIf — not JCVariableDecl → missed.
        // New recursive scanner: scans JCIf.thenpart → JCBlock.stats → finds J_A item.
        sources.put("ifpkg/Handler.java", """
                package ifpkg;
                public class Handler extends APIBase {
                    public void handle(boolean flag) {
                        if (flag) {
                            J_A item = getItem();
                        }
                    }
                }
                """);
        JypeTestCompiler.compile(sources).assertSuccess();
    }

    @Test
    @DisplayName("Abstract Jype class used as concrete type arg is filtered — real concrete type wins in concreteTypeMap")
    void abstractTypeArgFilteredFromConcreteTypeMap() throws IOException {
        // Reproduces APIUHC bug: APIPlayerJoinEvent extends EventBase<APIPlayer,...> where APIPlayer
        // is abstract and has managed params. Without the fix, collectConcreteTypes records
        // J_A→APIPlayer (abstract) before Power registers J_A→MyPlayer. buildConcreteArgs then
        // emits "extends APIAbstract<APIPlayer,...>" → "type argument not within bounds".
        // With fix: APIPlayer has managed params in typeParamMap → skipped → MyPlayer wins.
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("filtpkg/package-info.java", """
                @fr.thegamerfarmer.jype.annotations.TypeSystem({
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name = "J_A", bound = Object.class),
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name = "J_B", bound = Object.class)
                })
                package filtpkg;
                """);
        // APIBase and APIRole: abstract, use J_A/J_B in fields → Jype injects J_A,J_B into both
        sources.put("filtpkg/APIBase.java", """
                package filtpkg;
                public abstract class APIBase {
                    protected J_A item;
                }
                """);
        sources.put("filtpkg/APIRole.java", """
                package filtpkg;
                public abstract class APIRole {
                    protected J_B role;
                }
                """);
        // EventBase: user-declared J_A,J_B params
        sources.put("filtpkg/EventBase.java", """
                package filtpkg;
                public abstract class EventBase<J_A, J_B> {}
                """);
        // EventClass BEFORE Power: extends EventBase<APIBase, APIRole>
        // Without fix: J_A→APIBase and J_B→APIRole pollute concreteTypeMap (both abstract).
        // With fix: both have managed params → filtered → slots stay empty for Power/Role to fill.
        // @SuppressWarnings("rawtypes"): APIBase/APIRole are generic after Jype injection;
        // passing them without type args triggers -Xlint:rawtypes — suppressed here as the
        // pattern mirrors APIUHC sources (APIPlayer passed raw in event class declarations).
        sources.put("filtpkg/EventClass.java", """
                package filtpkg;
                @SuppressWarnings("rawtypes")
                public class EventClass extends EventBase<APIBase, APIRole> {}
                """);
        // Concrete types with no managed params
        sources.put("filtpkg/MyPlayer.java", "package filtpkg; public class MyPlayer {}");
        sources.put("filtpkg/MyRole.java", "package filtpkg; public class MyRole {}");
        // Power/RolePivot: establish J_A→MyPlayer, J_B→MyRole in concreteTypeMap
        sources.put("filtpkg/Power.java", """
                package filtpkg;
                public abstract class Power extends APIBase<MyPlayer, MyRole> {}
                """);
        sources.put("filtpkg/RolePivot.java", """
                package filtpkg;
                public abstract class RolePivot extends APIRole<MyPlayer, MyRole> {}
                """);
        // Leaf: raw extends APIBase → buildConcreteArgs must emit MyPlayer/MyRole (not APIBase/APIRole)
        sources.put("filtpkg/Leaf.java", """
                package filtpkg;
                public class Leaf extends APIBase {}
                """);
        JypeTestCompiler.compile(sources).assertSuccess();
    }

    @Test
    @DisplayName("Managed type used only inside a lambda body → lambda scanning injects params")
    void lambdaBodyTriggersBodyInjection() throws IOException {
        // Reproduces OnEveryOneSecond pattern from APIUHC:
        //   Bukkit.getScheduler().runTask(plugin, () -> { for (J_A player : getPlayers()) {...} })
        // The for-each is inside a JCLambda passed as argument to a JCMethodInvocation,
        // which is itself a JCExpressionStatement — not handled by the old scanner.
        // New scanner: JCExpressionStatement → scanExprForManagedTypes → JCMethodInvocation args
        //   → JCLambda body → JCEnhancedForLoop → J_A found → injection triggered.
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("lambdapkg/package-info.java", """
                @fr.thegamerfarmer.jype.annotations.TypeSystem({
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name = "J_A", bound = Object.class),
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name = "J_B", bound = Object.class)
                })
                package lambdapkg;
                """);
        sources.put("lambdapkg/APIBase.java", """
                package lambdapkg;
                import java.util.List;
                public abstract class APIBase<J_A, J_B> {
                    public List<J_A> getPlayers() { return null; }
                }
                """);
        sources.put("lambdapkg/Scheduler.java", """
                package lambdapkg;
                public class Scheduler {
                    public static void runLater(Runnable r) { r.run(); }
                }
                """);
        sources.put("lambdapkg/ConcreteJA.java", "package lambdapkg; public class ConcreteJA {}");
        sources.put("lambdapkg/ConcreteJB.java", "package lambdapkg; public class ConcreteJB {}");
        sources.put("lambdapkg/Pivot.java", """
                package lambdapkg;
                public abstract class Pivot extends APIBase<ConcreteJA, ConcreteJB> {}
                """);
        // J_A used ONLY inside a lambda body — no field, no method signature, no top-level var.
        sources.put("lambdapkg/Task.java", """
                package lambdapkg;
                public class Task extends APIBase {
                    public void execute() {
                        Scheduler.runLater(() -> {
                            for (J_A player : getPlayers()) {
                            }
                        });
                    }
                }
                """);
        JypeTestCompiler.compile(sources).assertSuccess();
    }

    @Test
    @DisplayName("Raw bound converted to a typed bound when all parameters are in ownParams")
    void rawBoundConvertedToTyped() throws IOException {
        // Reproduces the SubPower pattern: <M extends Master>, where Master has managed parameters.
        // After injection, Sub has J_A,J_B in ownParams.
        // fixTypeParamBounds: raw Master bound (JCIdent) → Master<J_A,J_B> (JCTypeApply).
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("bndpkg2/package-info.java", """
                @fr.thegamerfarmer.jype.annotations.TypeSystem({
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name = "J_A", bound = Object.class),
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name = "J_B", bound = Object.class)
                })
                package bndpkg2;
                """);
        sources.put("bndpkg2/Base.java", """
                package bndpkg2;
                public abstract class Base {
                    protected J_A item;
                }
                """);
        // Master extends Base (raw abstract all-managed) → gets J_A, J_B injected
        sources.put("bndpkg2/Master.java", """
                package bndpkg2;
                public abstract class Master extends Base {
                }
                """);
        // Sub<M extends Master> — M's bound is raw; after Sub gets J_A,J_B,
        // fixTypeParamBounds should convert "Master" → "Master<J_A,J_B>"
        sources.put("bndpkg2/Sub.java", """
                package bndpkg2;
                public abstract class Sub<M extends Master> {
                }
                """);
        JypeTestCompiler.compile(sources).assertSuccess();
    }
}