package fr.thegamerfarmer.jype.plugin;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.PsiTestUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;

/** Inheritance and member resolution from compiled package annotations. */
public class JypeCompiledTypeSystemTest extends JypeJavaFixture {
    public void testInheritanceFromCompiledPackageInfo() throws Exception {
        Path workspace = Files.createDirectories(Path.of("build", "test-fixtures",
                "jype-plugin-library-" + java.util.UUID.randomUUID()).toAbsolutePath());
        try {
            Path src = Files.createDirectories(workspace.resolve("src"));
            Path out = Files.createDirectories(workspace.resolve("classes"));
            Map<String, String> sources = Map.of(
                    "TypeDef.java", """
                            package fr.thegamerfarmer.jype.annotations;
                            public @interface TypeDef { String name(); Class<?> bound(); }
                            """,
                    "TypeSystem.java", """
                            package fr.thegamerfarmer.jype.annotations;
                            public @interface TypeSystem {
                                TypeDef[] value() default {}; String inherit() default "";
                                TypeDef[] overrides() default {};
                            }
                            """,
                    "Bound.java", "package compiled; public class Bound { public int count() { return 1; } }",
                    "package-info.java", """
                            @fr.thegamerfarmer.jype.annotations.TypeSystem({
                                @fr.thegamerfarmer.jype.annotations.TypeDef(name="TYPE", bound=compiled.Bound.class)
                            })
                            package compiled;
                            """);
            for (var entry : sources.entrySet()) Files.writeString(src.resolve(entry.getKey()), entry.getValue());
            String compiler = Path.of(System.getProperty("jype.test.jdk"), "bin",
                    System.getProperty("os.name").startsWith("Windows") ? "javac.exe" : "javac").toString();
            var arguments = new java.util.ArrayList<>(java.util.List.of(compiler, "--release", "17", "-proc:none", "-d", out.toString()));
            sources.keySet().forEach(name -> arguments.add(src.resolve(name).toString()));
            Path log = workspace.resolve("javac.log");
            Process javac = new ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if (!javac.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
                javac.destroyForcibly();
                fail("Timed out compiling the library fixture");
            }
            assertEquals(Files.readString(log), 0, javac.exitValue());
            PsiTestUtil.addLibrary(getTestRootDisposable(), getModule(), "jype-test-library", workspace.toString(), "classes");
            define("consumer", "inherit=\"compiled\"");
            PsiClass cls = myFixture.addClass("package consumer; public class Consumer {}");
            assertEquals("compiled.Bound", JypeTypeSystemReader.getTypeDefMap(cls).get("TYPE").boundFqn());
            PsiFile file = myFixture.addFileToProject("compiled/deep/Consumer.java", """
                    package compiled.deep;
                    public class Consumer { TYPE value; public int count() { return value.count(); } }
                    """);
            myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
            myFixture.checkHighlighting(false, false, false);
        } finally {
            try (var files = Files.walk(workspace)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}
