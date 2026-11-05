package fr.thegamerfarmer.jype;

import com.sun.source.tree.*;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.Trees;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;

/**
 * Resolves type references and inserts missing parameters in source text for Javadoc.
 *
 * <p>Uses parsed source positions, not regular expressions, to preserve comments and
 * unrelated expressions. Resolution is lexical and import-aware; ambiguous imports
 * are rejected rather than guessed. No class symbols or compilation files are mutated.
 */
final class JavadocSourcePatcher {
    private JavadocSourcePatcher() {}

    /**
     * Patches a standalone source using known declaration orders.
     * Each order entry is {@code {name, "true"|"false"}}, indicating a managed parameter.
     *
     * @throws Exception if parsing, order validation or unambiguous resolution fails
     */
    static String patchContent(String content, Map<String, List<String[]>> originalParamOrders,
                               Map<String, List<String[]>> declarationPatches) throws Exception {
        Map<String, List<String[]>> allTargets = new LinkedHashMap<>(originalParamOrders);
        allTargets.putAll(declarationPatches);
        if (allTargets.isEmpty()) return content;
        return patchContent(content, parse(content), originalParamOrders, declarationPatches, name -> false);
    }

    /** Patches an already parsed source, consulting the compiler for imported type existence. */
    static String patchContent(String content, ParsedSource parsed,
                                       Map<String, List<String[]>> originalParamOrders,
                                       Map<String, List<String[]>> declarationPatches,
                                       Predicate<String> knownType) {
        Map<String, List<String[]>> allTargets = new LinkedHashMap<>(originalParamOrders);
        allTargets.putAll(declarationPatches);
        for (var entry : allTargets.entrySet()) {
            if (entry.getValue() == null) {
                throw new IllegalArgumentException("Missing Javadoc parameter order for " + entry.getKey());
            }
            Set<String> names = new HashSet<>();
            for (String[] parameter : entry.getValue()) {
                if (parameter == null || parameter.length != 2
                        || parameter[0] == null || parameter[1] == null
                        || !javax.lang.model.SourceVersion.isIdentifier(parameter[0])
                        || javax.lang.model.SourceVersion.isKeyword(parameter[0])
                        || !Set.of("true", "false").contains(parameter[1])
                        || !names.add(parameter[0])) {
                    throw new IllegalArgumentException("Invalid Javadoc parameter order for " + entry.getKey());
                }
            }
        }
        JavadocPatchCollector collector = new JavadocPatchCollector(content, parsed.unit(),
                parsed.positions(), allTargets, declarationPatches, originalParamOrders, knownType);
        collector.scan(parsed.unit(), null);
        return collector.apply();
    }

    /** A parse-only tree and positions valid for precisely the input text. */
    record ParsedSource(CompilationUnitTree unit, SourcePositions positions) {}

    /** Parses without running processors; syntax errors are reported before any output is written. */
    static ParsedSource parse(String content) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("A JDK compiler is required for Javadoc source patching");
        }
        JavaFileObject source = new SimpleJavaFileObject(URI.create("string:///JypeJavadoc.java"),
                JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return content;
            }
        };
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (var manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostics,
                    List.of("-proc:none"), null, List.of(source));
            CompilationUnitTree unit = task.parse().iterator().next();
            List<String> errors = diagnostics.getDiagnostics().stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .map(d -> "line " + d.getLineNumber() + ": " + d.getMessage(Locale.ROOT)).toList();
            if (!errors.isEmpty()) {
                throw new IllegalArgumentException("Cannot parse Javadoc source: " + String.join("; ", errors));
            }
            return new ParsedSource(unit, Trees.instance(task).getSourcePositions());
        }
    }

    record Token(String text, int start, int end) {}

    static List<Token> tokenize(String source) {
        List<Token> result = new ArrayList<>();
        for (int i = 0; i < source.length();) {
            char c = source.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                i += 2;
                while (i < source.length() && source.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < source.length()
                        && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) i++;
                i = Math.min(source.length(), i + 2);
                continue;
            }
            if (c == '"' || c == '\'') {
                boolean textBlock = c == '"' && i + 2 < source.length()
                        && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"';
                int delimiter = textBlock ? 3 : 1;
                i += delimiter;
                while (i < source.length()) {
                    if (source.charAt(i) == '\\') { i = Math.min(source.length(), i + 2); continue; }
                    if (textBlock && i + 2 < source.length() && source.startsWith("\"\"\"", i)) {
                        i += 3;
                        break;
                    }
                    if (!textBlock && source.charAt(i) == c) { i++; break; }
                    i++;
                }
                continue;
            }
            int start = i;
            if (Character.isJavaIdentifierStart(c)) {
                i++;
                while (i < source.length() && Character.isJavaIdentifierPart(source.charAt(i))) i++;
            } else {
                i++;
            }
            result.add(new Token(source.substring(start, i), start, i));
        }
        return result;
    }

}
