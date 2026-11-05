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
                .mapToObj(i -> i == 0 ? "PLAYER" : "TYPE_" + i).toList();
        String parameters = String.join(", ", names);
        String definitions = names.stream().map(name ->
                "@fr.thegamerfarmer.jype.annotations.TypeDef(name=\"" + name + "\", bound=Object.class)")
                .collect(java.util.stream.Collectors.joining(", "));
        JypeTestCompiler.compileTwoPassJar(Map.of(
                "api/package-info.java",
                "@fr.thegamerfarmer.jype.annotations.TypeSystem({" + definitions + "}) package api;",
                "api/Master.java", "package api; public abstract class Master<" + parameters + "> {}",
                "api/Sub.java", "package api; public abstract class Sub<MASTER_POWER extends Master<"
                        + parameters + ">, " + parameters + "> {}"),
                Map.of(
                        "consumer/package-info.java", """
                                @fr.thegamerfarmer.jype.annotations.TypeSystem(
                                    inherit="api",
                                    overrides=@fr.thegamerfarmer.jype.annotations.TypeDef(name="PLAYER", bound=String.class)
                                ) package consumer;
                                """,
                        "consumer/BGMaster.java",
                        "package consumer; import api.Master; public abstract class BGMaster extends Master {}",
                        "consumer/BGSub.java", """
                                package consumer;
                                import api.*;
                                public abstract class BGSub<MASTER_POWER extends BGMaster> extends Sub<MASTER_POWER> {}
                                """)).assertSuccess();
    }

    private Map<String, String> library() {
        return Map.of(
                "api/package-info.java", """
                        @fr.thegamerfarmer.jype.annotations.TypeSystem({
                            @fr.thegamerfarmer.jype.annotations.TypeDef(name="PLAYER", bound=Object.class),
                            @fr.thegamerfarmer.jype.annotations.TypeDef(name="ROLE", bound=Object.class)
                        }) package api;
                        """,
                "api/Master.java", "package api; public abstract class Master<PLAYER, ROLE> {}",
                "api/Sub.java", """
                        package api;
                        public abstract class Sub<MASTER_POWER extends Master<PLAYER, ROLE>, PLAYER, ROLE> {}
                        """);
    }

    @Test
    void completesPartialCompiledSuperclassWithOrdinaryParameterSharedByName() throws IOException {
        JypeTestCompiler.compileTwoPass(library(), Map.of(
                "consumer/package-info.java", """
                        @fr.thegamerfarmer.jype.annotations.TypeSystem(
                            inherit="api",
                            overrides=@fr.thegamerfarmer.jype.annotations.TypeDef(name="PLAYER", bound=String.class)
                        ) package consumer;
                        """,
                "consumer/BGMaster.java", """
                        package consumer;
                        import api.Master;
                        public abstract class BGMaster extends Master {}
                        """,
                "consumer/BGSub.java", """
                        package consumer;
                        import api.Sub;
                        public abstract class BGSub<MASTER_POWER extends BGMaster> extends Sub<MASTER_POWER> {}
                        """)).assertSuccess();
    }

    @Test
    void doesNotInventMissingOrdinaryCompiledArguments() throws IOException {
        var result = JypeTestCompiler.compileTwoPass(library(), Map.of(
                "consumer/Invalid.java", """
                        package consumer;
                        public abstract class Invalid<T> extends api.Sub<T> {}
                        """));
        result.assertFailure();
        assertTrue(result.hasError("wrong number of type arguments")
                || result.hasError("not within bounds"));
    }
}
