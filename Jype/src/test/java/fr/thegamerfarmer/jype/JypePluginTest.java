package fr.thegamerfarmer.jype;

import fr.thegamerfarmer.jype.util.JypeTestCompiler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Integration tests for JypePlugin (fixing partial extends clauses between
 * classes in the same compilation, without @TypeSystem).
 *
 * The plugin uses only {@code typeParamMap}: it detects the subclass's own
 * parameters and injects them into the superclass extends clause.
 */
class JypePluginTest {

    @Test
    @DisplayName("SubGeneric<A,B> extends SuperGeneric<String> → plugin injects A,B → extends SuperGeneric<A,B,String>")
    void partialArgsFixedBySameCompilation() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();

        sources.put("plugintest/SuperGeneric.java", """
                package plugintest;
                public class SuperGeneric<A, B, C> {
                }
                """);

        // superParams = [A, B, C]
        // ownParams   = {A, B}   → shared
        // nonSharedCount = 1 (C only)
        // providedArgs = [String]  → C = String
        // Result: extends SuperGeneric<A, B, String>
        sources.put("plugintest/SubGeneric.java", """
                package plugintest;
                public class SubGeneric<A, B> extends SuperGeneric<String> {
                }
                """);

        JypeTestCompiler.compile(sources).assertSuccess();
    }

    @Test
    @DisplayName("Without Jype, SubGeneric<A,B> extends SuperGeneric<String> → wrong number of type arguments → failure")
    void partialArgsFailsWithoutPlugin() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();

        sources.put("plugintest/SuperGeneric.java", """
                package plugintest;
                public class SuperGeneric<A, B, C> {
                }
                """);

        sources.put("plugintest/SubGeneric.java", """
                package plugintest;
                public class SubGeneric<A, B> extends SuperGeneric<String> {
                }
                """);

        JypeTestCompiler.compileWithoutJype(sources).assertFailure();
    }

    @Test
    @DisplayName("Multiple subclasses with different partial arguments → plugin fixes them all")
    void multipleClassesFixed() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();

        sources.put("plugintest/SuperGeneric.java", """
                package plugintest;
                public class SuperGeneric<A, B, C> {
                }
                """);

        sources.put("plugintest/SubA.java", """
                package plugintest;
                public class SubA<A, B> extends SuperGeneric<String> {
                }
                """);

        sources.put("plugintest/SubB.java", """
                package plugintest;
                public class SubB<A, B> extends SuperGeneric<Integer> {
                }
                """);

        JypeTestCompiler.compile(sources).assertSuccess();
    }

    @Test
    @DisplayName("Inner class with partial extends → plugin processes static inner classes")
    void innerClassFixed() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();

        sources.put("plugintest/SuperGeneric.java", """
                package plugintest;
                public class SuperGeneric<A, B, C> {
                }
                """);

        // Inner<A,B> extends SuperGeneric<String>  →  plugin: A,B shared, C=String
        // Result: Inner<A,B> extends SuperGeneric<A, B, String>
        sources.put("plugintest/Outer.java", """
                package plugintest;
                public class Outer {
                    public static class Inner<A, B> extends SuperGeneric<String> {
                    }
                }
                """);

        JypeTestCompiler.compile(sources).assertSuccess();
    }

    @Test
    @DisplayName("Provided argument count ≠ nonSharedCount → buildFixedArgs returns null → javac reports a clean error")
    void wrongArgCountCausesError() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();

        // SuperGeneric<A, B, C>: 3 parameters
        sources.put("plugintest/SuperGeneric.java", """
                package plugintest;
                public class SuperGeneric<A, B, C> {
                }
                """);

        // SubGeneric<A, B>: ownParams={A,B}, nonSharedCount=1 (C only)
        // The user provides 2 unshared arguments (String, Integer) → mismatch
        // → buildFixedArgs returns null → extends remains <String, Integer> (wrong arity)
        sources.put("plugintest/SubGeneric.java", """
                package plugintest;
                public class SubGeneric<A, B> extends SuperGeneric<String, Integer> {
                }
                """);

        JypeTestCompiler.compilePermissive(sources).assertFailure();
    }

    @Test
    @DisplayName("extends SuperGeneric<A,B,String> already complete → plugin does not modify it; compiles normally")
    void alreadyCompleteNotModified() throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();

        sources.put("plugintest/SuperGeneric.java", """
                package plugintest;
                public class SuperGeneric<A, B, C> {
                }
                """);

        // Already complete: the plugin must not modify it; args.size() == superParams.size().
        sources.put("plugintest/SubFull.java", """
                package plugintest;
                public class SubFull<A, B> extends SuperGeneric<A, B, String> {
                }
                """);

        JypeTestCompiler.compile(sources).assertSuccess();
    }
}