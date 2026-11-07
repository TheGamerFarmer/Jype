package fr.thegamerfarmer.jype;

import fr.thegamerfarmer.jype.util.JypeTestCompiler;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JypeCompiledInheritanceTest {
    @Test
    void completesTwentyParametersFromJarWithoutExplicitProcessorSelection() throws IOException {
        var names = java.util.stream.IntStream.range(0, 19)
                .mapToObj(i -> i == 0 ? "J_A" : "TYPE_" + i).toList();
        String parameters = String.join(", ", names);
        String definitions = names.stream().map(name ->
                "@fr.thegamerfarmer.jype.annotations.TypeDef(name=\"" + name + "\", bound=Object.class)")
                .collect(java.util.stream.Collectors.joining(", "));
        JypeTestCompiler.compileTwoPassJar(Map.of(
                "api/package-info.java",
                "@fr.thegamerfarmer.jype.annotations.TypeSystem({" + definitions + "}) package api;",
                "api/Primary.java", "package api; public abstract class Primary<" + parameters + "> {}",
                "api/Secondary.java", "package api; public abstract class Secondary<PARENT extends Primary<"
                        + parameters + ">, " + parameters + "> {}"),
                Map.of(
                        "consumer/package-info.java", """
                                @fr.thegamerfarmer.jype.annotations.TypeSystem(
                                    inherit="api",
                                    overrides=@fr.thegamerfarmer.jype.annotations.TypeDef(name="J_A", bound=String.class)
                                ) package consumer;
                                """,
                        "consumer/ImplPrimary.java",
                        "package consumer; import api.Primary; public abstract class ImplPrimary extends Primary {}",
                        "consumer/ImplSecondary.java", """
                                package consumer;
                                import api.*;
                                public abstract class ImplSecondary<PARENT extends ImplPrimary> extends Secondary<PARENT> {}
                                """)).assertSuccess();
    }

    private Map<String, String> library() {
        return Map.of(
                "api/package-info.java", """
                        @fr.thegamerfarmer.jype.annotations.TypeSystem({
                            @fr.thegamerfarmer.jype.annotations.TypeDef(name="J_A", bound=Object.class),
                            @fr.thegamerfarmer.jype.annotations.TypeDef(name="J_B", bound=Object.class)
                        }) package api;
                        """,
                "api/Primary.java", "package api; public abstract class Primary<J_A, J_B> {}",
                "api/Secondary.java", """
                        package api;
                        public abstract class Secondary<PARENT extends Primary<J_A, J_B>, J_A, J_B> {}
                        """);
    }

    @Test
    void completesPartialCompiledSuperclassWithOrdinaryParameterSharedByName() throws IOException {
        JypeTestCompiler.compileTwoPass(library(), Map.of(
                "consumer/package-info.java", """
                        @fr.thegamerfarmer.jype.annotations.TypeSystem(
                            inherit="api",
                            overrides=@fr.thegamerfarmer.jype.annotations.TypeDef(name="J_A", bound=String.class)
                        ) package consumer;
                        """,
                "consumer/ImplPrimary.java", """
                        package consumer;
                        import api.Primary;
                        public abstract class ImplPrimary extends Primary {}
                        """,
                "consumer/ImplSecondary.java", """
                        package consumer;
                        import api.Secondary;
                        public abstract class ImplSecondary<PARENT extends ImplPrimary> extends Secondary<PARENT> {}
                        """)).assertSuccess();
    }

    @Test
    void doesNotInventMissingOrdinaryCompiledArguments() throws IOException {
        var result = JypeTestCompiler.compileTwoPass(library(), Map.of(
                "consumer/Invalid.java", """
                        package consumer;
                        public abstract class Invalid<T> extends api.Secondary<T> {}
                        """));
        result.assertFailure();
        assertTrue(result.hasError("wrong number of type arguments")
                || result.hasError("not within bounds"));
    }
}
