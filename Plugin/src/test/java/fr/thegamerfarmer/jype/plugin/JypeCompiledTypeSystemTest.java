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
    /** A dependency declaring a type system activates the plugin without any local package-info. */
    public void testDependencyTypeSystemActivatesWithoutLocalDeclaration() throws Exception {
        Path workspace = Files.createDirectories(Path.of("build", "test-fixtures",
                "jype-plugin-activation-" + java.util.UUID.randomUUID()).toAbsolutePath());
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
                    "package-info.java", """
                            @fr.thegamerfarmer.jype.annotations.TypeSystem({
                                @fr.thegamerfarmer.jype.annotations.TypeDef(name="TYPE", bound=Object.class)
                            })
                            package activation.lib;
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
            var file = myFixture.addFileToProject("pom.xml", "<project/>");
            var consumer = myFixture.addFileToProject("app/Plain.java", "package app; class Plain {}").getVirtualFile();
            var provider = new JypeNotificationProvider();
            assertNull("No dependency and no local system: inactive",
                    provider.collectNotificationData(getProject(), consumer));
            PsiTestUtil.addLibrary(getTestRootDisposable(), getModule(), "jype-activation-library",
                    workspace.toString(), "classes");
            assertNotNull("A dependency using Jype activates the plugin",
                    provider.collectNotificationData(getProject(), consumer));
            assertNotNull(file);
        } finally {
            try (var files = Files.walk(workspace)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    public void testInheritanceFromCompiledPackageInfo() throws Exception {
        Path workspace = Files.createDirectories(Path.of("build", "test-fixtures",
                "jype-plugin-library-" + java.util.UUID.randomUUID()).toAbsolutePath());
        try {
            Path src = Files.createDirectories(workspace.resolve("src"));
            Path out = Files.createDirectories(workspace.resolve("classes"));
            String extraParameters = java.util.stream.IntStream.range(0, 18)
                    .mapToObj(i -> "P" + i).collect(java.util.stream.Collectors.joining(", "));
            String extraDefinitions = java.util.stream.IntStream.range(0, 18)
                    .mapToObj(i -> ", @fr.thegamerfarmer.jype.annotations.TypeDef(name=\"P" + i
                            + "\", bound=Object.class)").collect(java.util.stream.Collectors.joining());
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
                    "Primary.java", "package compiled; public class Primary<TYPE extends Bound> {}",
                    "Secondary.java", "package compiled; public abstract class Secondary<PARENT extends Primary<TYPE>, "
                            + "TYPE extends Bound, " + extraParameters + "> { public PARENT primary() { return null; } }",
                    "package-info.java", """
                            @fr.thegamerfarmer.jype.annotations.TypeSystem({
                                @fr.thegamerfarmer.jype.annotations.TypeDef(name="TYPE", bound=compiled.Bound.class)
                            %s
                            })
                            package compiled;
                            """.formatted(extraDefinitions));
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
            PsiFile completion = define("suggestions", "inherit=\"<caret>\"");
            myFixture.configureFromExistingVirtualFile(completion.getVirtualFile());
            var variants = myFixture.complete(com.intellij.codeInsight.completion.CompletionType.BASIC);
            assertTrue(variants == null
                    ? myFixture.getFile().getText().contains("inherit=\"compiled\"")
                    : java.util.Arrays.stream(variants).anyMatch(item -> item.getLookupString().equals("compiled")));
            completion = define("overrideSuggestions", """
                    inherit="compiled", overrides=
                        @fr.thegamerfarmer.jype.annotations.TypeDef(name="<caret>", bound=Object.class)
                    """);
            myFixture.configureFromExistingVirtualFile(completion.getVirtualFile());
            variants = myFixture.complete(com.intellij.codeInsight.completion.CompletionType.BASIC);
            assertTrue(variants == null
                    ? myFixture.getFile().getText().contains("name=\"TYPE\"")
                    : java.util.Arrays.stream(variants).anyMatch(item -> item.getLookupString().equals("TYPE")));
            define("consumer", "inherit=\"compiled\"");
            myFixture.addClass("package consumer; public class ImplPrimary extends compiled.Primary {}");
            PsiFile inheritance = myFixture.addFileToProject("consumer/ImplSecondary.java", """
                    package consumer;
                    public abstract class ImplSecondary<PARENT extends ImplPrimary>
                            extends compiled.Secondary<PARENT> {
                        PARENT linked() { return primary(); }
                    }
                    """);
            myFixture.configureFromExistingVirtualFile(inheritance.getVirtualFile());
            myFixture.checkHighlighting(false, false, false);
            inheritance = myFixture.addFileToProject("consumer/WrongSecondary.java", """
                    package consumer;
                    public abstract class WrongSecondary extends compiled.Secondary<String> {}
                    """);
            myFixture.configureFromExistingVirtualFile(inheritance.getVirtualFile());
            var errors = myFixture.doHighlighting(com.intellij.lang.annotation.HighlightSeverity.ERROR);
            assertTrue(errors.toString(), errors.stream().anyMatch(info -> info.getDescription() != null
                    && info.getDescription().startsWith("[Jype] Type argument String does not satisfy bound")));
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
