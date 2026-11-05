package fr.thegamerfarmer.jype.util;

import javax.tools.*;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.*;

import static org.junit.jupiter.api.Assertions.*;

public final class JypeTestCompiler {

    private JypeTestCompiler() {}

    private static final JavaCompiler JAVAC = ToolProvider.getSystemJavaCompiler();

    private static final List<String> JYPE_EXPORTS = List.of(
            "--add-exports", "jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
            "--add-exports", "jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
            "--add-exports", "jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED",
            "--add-exports", "jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED",
            "--add-exports", "jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
            "--add-exports", "jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED"
    );

    /**
     * Base classpath (the test JVM's java.class.path).
     */
    static List<File> currentClasspath() {
        return Arrays.stream(
                        System.getProperty("java.class.path", "").split(Pattern.quote(File.pathSeparator)))
                .filter(s -> !s.isBlank())
                .map(File::new)
                .collect(Collectors.toList());
    }

    /**
     * Classpath augmented with a temporary directory containing the Jype plugin's SPI file
     * ({@code META-INF/services/com.sun.source.util.Plugin → JypePluginShim}).
     *
     * <p>The SPI is restored after compiling the engine. If another test runner
     * does not copy Maven resources, this fallback makes {@code -Xplugin:Jype}
     * available to {@code javax.tools.JavaCompiler}.
     */
    private static final List<File> PLUGIN_CLASSPATH = buildPluginClasspath();

    private static List<File> buildPluginClasspath() {
        List<File> base = currentClasspath();

        // Check whether the SPI is already present (e.g. target/test-classes with a correct Maven config).
        boolean alreadyPresent = base.stream().anyMatch(
                f -> new File(f, "META-INF/services/com.sun.source.util.Plugin").exists());
        if (alreadyPresent) return base;

        try {
            Path spiRoot = workspace("jype-plugin-spi-");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteDir(spiRoot)));

            Path servicesDir = spiRoot.resolve("META-INF").resolve("services");
            Files.createDirectories(servicesDir);
            Files.writeString(servicesDir.resolve("com.sun.source.util.Plugin"),
                    "fr.thegamerfarmer.jype.JypePluginShim");

            List<File> cp = new ArrayList<>();
            cp.add(spiRoot.toFile()); // Put first so it is found before other entries.
            cp.addAll(base);
            return Collections.unmodifiableList(cp);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot prepare the Jype test plugin classpath", e);
        }
    }

    /**
     * Full compilation with Jype (plugin + processor) and strict lint.
     * A raw extends clause would fail, proving that Jype injected the type arguments.
     */
    public static Result compile(Map<String, String> sources) throws IOException {
        return run(sources, List.of("-Xplugin:Jype", "-Xlint:rawtypes", "-Werror"),
                PLUGIN_CLASSPATH, null);
    }

    /**
     * Compilation with Jype but without strict lint.
     * For tests that provide complete arguments or expect a clean failure.
     */
    public static Result compilePermissive(Map<String, String> sources) throws IOException {
        return run(sources, List.of("-Xplugin:Jype"), PLUGIN_CLASSPATH, null);
    }

    public static Result compileWithOptions(Map<String, String> sources, String... options) throws IOException {
        return run(sources, List.of(options), PLUGIN_CLASSPATH, null);
    }

    /**
     * Compilation WITHOUT Jype (-proc:none disables the processor; no -Xplugin:Jype).
     * Used to verify that some patterns REQUIRE Jype.
     */
    public static Result compileWithoutJype(Map<String, String> sources) throws IOException {
        return run(sources, List.of("-proc:none", "-Xlint:rawtypes", "-Werror"),
                currentClasspath(), null);
    }

    /**
     * Two-pass: first compile parentSources without Jype (as a library),
     * then compile childSources with Jype and the parent output on the classpath.
     */
    public static Result compileTwoPass(Map<String, String> parentSources,
                                        Map<String, String> childSources) throws IOException {
        return compileTwoPass(parentSources, childSources, false);
    }

    public static Result compileTwoPassWithJype(Map<String, String> parentSources,
                                               Map<String, String> childSources) throws IOException {
        return compileTwoPass(parentSources, childSources, true);
    }

    public static Result compileAndRun(Map<String, String> sources, String mainClass) throws Exception {
        Path root = workspace("jype-runtime-");
        try {
            Path output = Files.createDirectory(root.resolve("classes"));
            Result result = run(sources, List.of("-Xplugin:Jype"), PLUGIN_CLASSPATH, output);
            result.assertSuccess();
            try (var loader = new java.net.URLClassLoader(new java.net.URL[]{output.toUri().toURL()},
                    JypeTestCompiler.class.getClassLoader())) {
                try {
                    loader.loadClass(mainClass).getMethod("main", String[].class).invoke(null, (Object) new String[0]);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw new AssertionError("Compiled program failed", e.getCause());
                }
            }
            return result;
        } finally {
            deleteDir(root);
        }
    }

    private static Result compileTwoPass(Map<String, String> parentSources,
                                         Map<String, String> childSources, boolean parentUsesJype) throws IOException {
        Path parentRoot = workspace("jype-parent-");
        Path parentOut = Files.createDirectory(parentRoot.resolve("classes"));
        try {
            Result parentResult = run(parentSources, parentUsesJype ? List.of("-Xplugin:Jype") : List.of("-proc:none"),
                    parentUsesJype ? PLUGIN_CLASSPATH : currentClasspath(), parentOut);
            if (!parentResult.success) return parentResult;

            List<File> cp = new ArrayList<>(PLUGIN_CLASSPATH);
            cp.add(parentOut.toFile());
            return run(childSources, parentUsesJype ? List.of("-Xplugin:Jype")
                            : List.of("-Xplugin:Jype", "-Xlint:rawtypes", "-Werror"),
                    cp, null);
        } finally {
            deleteDir(parentRoot);
        }
    }

    private static Result run(Map<String, String> sources, List<String> extraOptions,
                               List<File> classpath, Path fixedOutputDir) throws IOException {
        Path workspace = workspace("jype-test-");
        Path srcDir = Files.createDirectory(workspace.resolve("src"));
        Path outDir = fixedOutputDir != null ? fixedOutputDir
                : Files.createDirectory(workspace.resolve("classes"));

        try {
            for (Map.Entry<String, String> e : sources.entrySet()) {
                Path file = srcDir.resolve(e.getKey());
                Files.createDirectories(file.getParent());
                Files.writeString(file, e.getValue());
            }

            DiagnosticCollector<JavaFileObject> dc = new DiagnosticCollector<>();
            try (StandardJavaFileManager fm = JAVAC.getStandardFileManager(dc, null, null)) {
                fm.setLocation(StandardLocation.CLASS_PATH, classpath);
                fm.setLocation(StandardLocation.ANNOTATION_PROCESSOR_PATH, classpath);
                fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outDir.toFile()));

                List<String> options = new ArrayList<>(JYPE_EXPORTS);
                options.addAll(extraOptions);

                List<Path> paths = sources.keySet().stream().map(srcDir::resolve).toList();
                Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromPaths(paths);

                boolean ok = JAVAC.getTask(null, fm, dc, options, null, units).call();
                dc.getDiagnostics().forEach(diagnostic -> {
                    diagnostic.getLineNumber();
                    diagnostic.getColumnNumber();
                    diagnostic.getMessage(null);
                });
                return new Result(ok, new ArrayList<>(dc.getDiagnostics()));
            }
        } finally {
            deleteDir(workspace);
        }
    }

    private static Path workspace(String prefix) throws IOException {
        return Files.createTempDirectory(Files.createDirectories(Path.of("target", "test-workspaces")), prefix);
    }

    private static void deleteDir(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path path : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot clean compiler test workspace " + dir, e);
        }
    }

    public record Result(boolean success, List<Diagnostic<? extends JavaFileObject>> diagnostics) {

        public void assertSuccess() {
            if (!success) {
                String errors = diagnostics.stream()
                        .map(Object::toString)
                        .collect(Collectors.joining("\n  "));
                fail("Compilation failed:\n  " + errors);
            }
        }

        public void assertFailure() {
            assertFalse(success, "Compilation should have failed but succeeded");
        }

        public boolean hasError(String fragment) {
            return diagnostics.stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .anyMatch(d -> d.getMessage(null).contains(fragment)
                            || d.toString().contains(fragment));
        }
    }
}