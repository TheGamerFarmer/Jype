package fr.thegamerfarmer.jype;

import com.sun.source.tree.CompilationUnitTree;
import org.jetbrains.annotations.Nullable;

import javax.annotation.processing.ProcessingEnvironment;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Writes Javadoc-compatible copies after the final annotation-processing round.
 *
 * <p>Original sources are never changed. Output follows declared packages, including
 * generated sources, under a sibling {@code jype-javadoc-src} directory of CLASS_OUTPUT.
 * A failed source produces a warning and does not prevent other sources from being copied.
 * Syntax-aware rewriting is delegated to {@link JavadocSourcePatcher}.
 */
final class JavadocSourceGenerator {
    private final ProcessingEnvironment env;
    private final Map<String, List<String[]>> originalParamOrders;
    private final Map<String, List<String[]>> declarationPatches = new LinkedHashMap<>();
    private final Map<String, List<SourceEdit>> staticEdits = new LinkedHashMap<>();
    private final Map<String, Set<String>> aliases = new LinkedHashMap<>();
    private final Map<String, List<String[]>> sourceOrigins = new LinkedHashMap<>();
    private final DocumentaryDependencies dependencies;
    private @Nullable Path outputDir;
    private boolean outputDirInitialized;

    /** A half-open replacement range in the original, unmodified source text. */
    record SourceEdit(int start, int end, String replacement) {}

    /**
     * Retains the processor's live original-order map; later rounds may add declarations.
     * Parameter entries use {@code {name, "true"|"false"}} to mark managed parameters.
     */
    JavadocSourceGenerator(ProcessingEnvironment env, Map<String, List<String[]>> originalParamOrders) {
        this.env = env;
        this.originalParamOrders = originalParamOrders;
        dependencies = new DocumentaryDependencies(env, this::addAliases);
    }

    /** Registers pre-Enter static lowering edits keyed by source URI, not filesystem path. */
    void addStaticEdits(Map<String, List<SourceEdit>> edits) {
        staticEdits.putAll(edits);
    }

    /** Records a declaration's final order; the first round providing it owns the patch. */
    void addDeclPatch(String qualifiedName, List<String[]> parameterOrder) {
        declarationPatches.putIfAbsent(qualifiedName, parameterOrder);
    }

    void addAliases(String packageName, Set<String> names) {
        aliases.computeIfAbsent(packageName, ignored -> new LinkedHashSet<>()).addAll(names);
    }

    void addOrigin(String name, List<String[]> parameters) {
        sourceOrigins.put(name, parameters);
    }

    void addDependency(javax.lang.model.type.TypeMirror type) {
        dependencies.collect(type);
    }

    /** Resolves output once, while CLASS_OUTPUT is available during a normal round. */
    void ensureOutputDir() {
        if (!outputDirInitialized) {
            outputDir = computeOutputDir();
            outputDirInitialized = true;
            if (outputDir != null && Files.exists(outputDir)) {
                try (var files = Files.walk(outputDir)) {
                    for (Path file : files.filter(Files::isRegularFile)
                            .filter(path -> path.toString().endsWith(".java")).toList()) {
                        Files.delete(file);
                    }
                } catch (Exception exception) {
                    env.getMessager().printMessage(Diagnostic.Kind.WARNING,
                            "[Jype] Cannot clear stale Javadoc sources: " + exception.getMessage());
                    outputDir = null;
                }
            }
        }
    }

    /** Writes collected sources during {@code processingOver()}, warning on individual failures. */
    void generate(Collection<JavaFileObject> sourceFiles) {
        if (sourceFiles.isEmpty()) return;
        ensureOutputDir();
        Path outDir = outputDir;
        if (outDir == null) {
            env.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "[Jype] Generated 0 of " + sourceFiles.size() + " javadoc sources: output directory unavailable");
            return;
        }
        int written = 0;
        Set<Path> destinations = new HashSet<>();
        for (JavaFileObject source : sourceFiles) {
            try {
                // Runtime static lowering is deliberately absent from the documentary view.
                String content = readContent(source);
                var parsed = JavadocSourcePatcher.parse(content);
                Path relative = relativePath(source, parsed.unit());
                Path destination = outDir.resolve(relative).normalize();
                if (!destination.startsWith(outDir.toAbsolutePath().normalize())) {
                    throw new IllegalStateException("Output path escapes the Javadoc source directory: " + relative);
                }
                if (!destinations.add(destination)) {
                    throw new IllegalStateException("Duplicate Javadoc output path: " + relative);
                }
                Map<String, List<String[]>> origins = new LinkedHashMap<>(originalParamOrders);
                origins.putAll(dependencies.parameterOrders(true));
                origins.putAll(sourceOrigins);
                Map<String, List<String[]>> declarations = new LinkedHashMap<>(dependencies.parameterOrders(false));
                declarations.putAll(declarationPatches);
                content = JavadocSourcePatcher.publicContent(content, parsed, origins, declarations,
                        name -> env.getElementUtils().getTypeElement(name) != null);
                Files.createDirectories(destination.getParent());
                Files.writeString(destination, content, StandardCharsets.UTF_8);
                written++;
            } catch (Exception exception) {
                env.getMessager().printMessage(Diagnostic.Kind.WARNING,
                        "[Jype] Failed to write javadoc source for " + source.toUri() + ": " + exception.getMessage());
            }
            try {
                for (var dependency : dependencies.sources().entrySet()) {
                    Path destination = outDir.resolve(dependency.getKey().replace('.', java.io.File.separatorChar) + ".java");
                    if (destinations.contains(destination)) continue;
                    Files.createDirectories(destination.getParent());
                    Files.writeString(destination, dependency.getValue(), StandardCharsets.UTF_8);
                }
            } catch (Exception exception) {
                env.getMessager().printMessage(Diagnostic.Kind.WARNING,
                        "[Jype] Failed to write compiled documentary declarations: " + exception.getMessage());
            }
            for (var entry : aliases.entrySet()) {
                for (String alias : entry.getValue()) {
                    Path destination = outDir.resolve(entry.getKey().replace('.', java.io.File.separatorChar))
                            .resolve(alias + ".java");
                    if (destinations.contains(destination)) continue;
                    try {
                        Files.createDirectories(destination.getParent());
                        Files.writeString(destination, "package " + entry.getKey() + ";\n"
                                + "/** Documentary Jype alias " + alias + "; not a runtime class. */\n"
                                + "public interface " + alias + " {}\n", StandardCharsets.UTF_8);
                    } catch (Exception exception) {
                        env.getMessager().printMessage(Diagnostic.Kind.WARNING,
                                "[Jype] Failed to write documentary alias " + alias + ": " + exception.getMessage());
                    }
                }
            }
        }
        JypeDiagnostics.debug(env,
                "[Jype] Generated " + written + " of " + sourceFiles.size() + " javadoc sources in " + outDir);
    }

    /** Parse-only patching entry point, also usable without an annotation-processing environment. */
    static String patchContent(String content, Map<String, List<String[]>> originalOrders,
                               Map<String, List<String[]>> declarationOrders) throws Exception {
        return JavadocSourcePatcher.patchContent(content, originalOrders, declarationOrders);
    }

    private static String readContent(JavaFileObject source) throws Exception {
        try (Reader reader = source.openReader(true)) {
            StringBuilder content = new StringBuilder();
            char[] buffer = new char[8192];
            int count;
            while ((count = reader.read(buffer)) != -1) content.append(buffer, 0, count);
            return content.toString();
        }
    }

    private @Nullable Path computeOutputDir() {
        try {
            var resource = env.getFiler().getResource(StandardLocation.CLASS_OUTPUT, "", "dummy");
            Path classesDir = Paths.get(resource.toUri()).getParent();
            if (classesDir == null || classesDir.getParent() == null) {
                throw new IllegalStateException("CLASS_OUTPUT has no parent directory");
            }
            return classesDir.getParent().resolve("jype-javadoc-src").toAbsolutePath().normalize();
        } catch (Exception exception) {
            env.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "[Jype] computeJavadocSrcDir failed: " + exception.getMessage());
            return null;
        }
    }

    private static Path relativePath(JavaFileObject source, CompilationUnitTree unit) {
        String uriPath = source.toUri().getPath();
        if (uriPath == null) throw new IllegalArgumentException("Source URI has no path: " + source.toUri());
        String filename = uriPath.substring(uriPath.lastIndexOf('/') + 1);
        if (!filename.endsWith(".java") || filename.equals(".java") || filename.contains("\\")) {
            throw new IllegalArgumentException("Invalid Java source filename: " + filename);
        }
        Path relative = Path.of("");
        if (unit.getPackageName() != null) {
            for (String part : unit.getPackageName().toString().split("\\.")) {
                if (!javax.lang.model.SourceVersion.isIdentifier(part)
                        || javax.lang.model.SourceVersion.isKeyword(part)) {
                    throw new IllegalArgumentException("Invalid Java package: " + unit.getPackageName());
                }
                relative = relative.resolve(part);
            }
        }
        return relative.resolve(filename);
    }
}
