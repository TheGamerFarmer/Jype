package fr.thegamerfarmer.jype.plugin;

import com.intellij.codeInsight.documentation.DocumentationManager;
import com.intellij.codeInspection.miscGenerics.RawUseOfParameterizedTypeInspection;
import com.intellij.codeInspection.uncheckedWarnings.UncheckedWarningLocalInspection;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.PsiTestUtil;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;

/** A compiled API inheriting a compiled Jype library must retain its public alias view. */
public class JypeTransitiveLibraryTest extends JypeJavaFixture {
    private Path fixture;

    public void testThreeStageLibraryConsumption() throws Exception {
        Path workspace = Files.createDirectories(Path.of("build", "test-fixtures",
                "jype-transitive-" + UUID.randomUUID()).toAbsolutePath());
        fixture = workspace;
        try {
            String engine = System.getProperty("jype.test.engine");
            boolean transformed = engine != null;
            List<String> names = new ArrayList<>(List.of("PLAYER"));
            for (int i = 0; i < 19; i++) names.add("TYPE_" + i);
            String order = String.join(", ", names);
            String parameters = "PLAYER extends api.Player, " + String.join(", ", names.subList(1, names.size()));
            String definitions = names.stream().map(name ->
                    "@fr.thegamerfarmer.jype.annotations.TypeDef(name=\"" + name
                            + "\", bound=" + (name.equals("PLAYER") ? "api.Player" : "Object") + ".class)")
                    .collect(Collectors.joining(", "));
            String managed = names.stream().map(name -> "\"" + name + "\"").collect(Collectors.joining(", "));
            Map<String, String> base = new LinkedHashMap<>();
            if (!transformed) {
                base.put("fr/thegamerfarmer/jype/annotations/TypeDef.java", """
                        package fr.thegamerfarmer.jype.annotations;
                        @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
                        public @interface TypeDef { String name(); Class<?> bound(); }
                        """);
                base.put("fr/thegamerfarmer/jype/annotations/TypeSystem.java", """
                        package fr.thegamerfarmer.jype.annotations;
                        @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
                        public @interface TypeSystem {
                            TypeDef[] value() default {}; String inherit() default "";
                            TypeDef[] overrides() default {};
                        }
                        """);
                base.put("fr/thegamerfarmer/jype/annotations/JypeClass.java", """
                        package fr.thegamerfarmer.jype.annotations;
                        @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
                        @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)
                        public @interface JypeClass {
                            int version() default 1; String[] managedParameters();
                            String[] declaredParameters() default {}; String typeSystem();
                        }
                        """);
                base.put("fr/thegamerfarmer/jype/annotations/JypeMember.java", """
                        package fr.thegamerfarmer.jype.annotations;
                        @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
                        @java.lang.annotation.Target({java.lang.annotation.ElementType.METHOD,
                            java.lang.annotation.ElementType.FIELD})
                        public @interface JypeMember {
                            int version() default 1; String sourceType() default "";
                            String[] declaredParameters() default {}; boolean generated() default false;
                        }
                        """);
            }
            base.put("api/package-info.java",
                    "@fr.thegamerfarmer.jype.annotations.TypeSystem({" + definitions + "}) package api;");
            base.put("api/Player.java", "package api; public class Player { public int score() { return 1; } }");
            String fields = names.stream().map(name -> (transformed ? ""
                            : "@fr.thegamerfarmer.jype.annotations.JypeMember(sourceType=\"" + name + "\") ")
                            + "public " + name + " " + name.toLowerCase(Locale.ROOT) + ";")
                    .collect(Collectors.joining("\n"));
            base.put("api/Service.java", "package api; "
                    + (transformed ? "" : "@fr.thegamerfarmer.jype.annotations.JypeClass("
                    + "managedParameters={" + managed + "}, typeSystem=\"api\") ")
                    + "public class Service" + (transformed ? "" : "<" + parameters + ">")
                    + " { " + fields + " public PLAYER player() { return player; } "
                    + "public <T> T identity(T value) { return value; } "
                    + "public void accept(PLAYER value) {} public java.util.List<PLAYER> players() { return null; } "
                    + (transformed ? "public static PLAYER staticPlayer() { return null; }"
                    : "@fr.thegamerfarmer.jype.annotations.JypeMember(sourceType=\"PLAYER\") public static <"
                    + parameters + "> PLAYER staticPlayer() { return null; }")
                    + " }");
            List<Path> classpath = transformed ? new ArrayList<>(List.of(Path.of(engine))) : new ArrayList<>();
            Path baseJar = compile(workspace, "base", base, classpath, transformed);
            classpath.add(baseJar);
            Map<String, String> api = Map.of(
                    "uhc/package-info.java", """
                            @fr.thegamerfarmer.jype.annotations.TypeSystem(inherit="api") package uhc;
                            """,
                    "uhc/APIUHC.java", "package uhc; "
                            + (transformed ? "" : "@fr.thegamerfarmer.jype.annotations.JypeClass("
                            + "managedParameters={" + managed + "}, typeSystem=\"uhc\") ")
                            + "public class APIUHC" + (transformed ? "" : "<" + parameters + ">")
                            + " extends api.Service" + (transformed ? "" : "<" + order + ">")
                            + " { public PLAYER current() { return player(); } }");
            Path apiJar = compile(workspace, "uhc", api, classpath, transformed);
            classpath.add(apiJar);
            String bindings = "game.BattlePlayer" + ", java.lang.Object".repeat(19);
            Map<String, String> consumer = Map.of(
                    "game/package-info.java", """
                            @fr.thegamerfarmer.jype.annotations.TypeSystem(inherit="uhc",
                                overrides=@fr.thegamerfarmer.jype.annotations.TypeDef(
                                    name="PLAYER", bound=game.BattlePlayer.class)) package game;
                            """,
                    "game/BattlePlayer.java", "package game; public class BattlePlayer extends api.Player {}",
                    "game/CompiledConsumer.java", "package game; public class CompiledConsumer { uhc.APIUHC"
                            + (transformed ? "" : "<" + bindings + ">")
                            + " service; int score() { service.accept(service.player()); return service.player().score(); } }");
            compile(workspace, "consumer", consumer, classpath, transformed);
            PsiTestUtil.addLibrary(getTestRootDisposable(), getModule(), "jype-base", workspace.toString(), baseJar.getFileName().toString());
            PsiTestUtil.addLibrary(getTestRootDisposable(), getModule(), "jype-uhc", workspace.toString(), apiJar.getFileName().toString());
            myFixture.addClass("package game; public class BattlePlayer extends api.Player {}");
            define("game", """
                    inherit="uhc", overrides=@fr.thegamerfarmer.jype.annotations.TypeDef(
                        name="PLAYER", bound=game.BattlePlayer.class)
                    """);
            PsiFile source = myFixture.addFileToProject("game/Consumer.java", """
                    package game;
                    class Consumer {
                        uhc.APIUHC service;
                        PLAYER player() { service.accept(service.player()); return service.player(); }
                        java.util.List<PLAYER> players() { return service.players(); }
                        int score() { return service.player().score(); }
                    }
                    """);
            myFixture.configureFromExistingVirtualFile(source.getVirtualFile());
            myFixture.enableInspections(new RawUseOfParameterizedTypeInspection(), new UncheckedWarningLocalInspection());
            myFixture.checkHighlighting(true, false, false);
            PsiClass cls = ((PsiJavaFile) source).getClasses()[0];
            PsiClassType type = (PsiClassType) cls.getFields()[0].getType();
            assertEquals("APIUHC", type.getPresentableText());
            assertEquals(20, type.getParameters().length);
            PsiClass libraryClass = type.resolve();
            assertNotNull(JypeClassMetadata.read(libraryClass));
            String doc = DocumentationManager.getProviderFromElement(libraryClass).generateDoc(libraryClass, null);
            assertTrue(doc, doc.contains("class APIUHC extends Service"));
            assertFalse(doc, doc.contains("TYPE_18"));
            PsiMethodCallExpression call = PsiTreeUtil.findChildrenOfType(source, PsiMethodCallExpression.class)
                    .stream().filter(item -> "player".equals(item.getMethodExpression().getReferenceName()))
                    .findFirst().orElseThrow();
            assertEquals("game.PLAYER", call.getType().getCanonicalText());
            PsiMethod method = call.resolveMethod();
            doc = DocumentationManager.getProviderFromElement(method).generateDoc(method, call);
            assertTrue(doc, doc.contains("PLAYER player()"));
            assertFalse(doc, doc.contains("TYPE_18"));
            PsiClass baseClass = JavaPsiFacade.getInstance(getProject()).findClass("api.Service", source.getResolveScope());
            assertNotNull(baseClass);
            PsiField playerField = baseClass.findFieldByName("player", false);
            assertNotNull(playerField);
            assertNotNull(JypeMemberMetadata.read(playerField));
            assertEquals("PLAYER", JypePublicPresentation.memberType(playerField, playerField.getType()));
            PsiMethod staticPlayer = baseClass.findMethodsByName("staticPlayer", false)[0];
            doc = DocumentationManager.getProviderFromElement(staticPlayer).generateDoc(staticPlayer, null);
            assertTrue(doc, doc.contains("public static PLAYER staticPlayer()"));
            assertTrue(doc, doc.contains("psi_element://api.PLAYER"));
            assertFalse(doc, doc.contains("TYPE_18"));
        } finally {
            myFixture.configureByText("Cleanup.java", "class Cleanup {}");
        }
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            super.tearDown();
        } finally {
            com.intellij.openapi.vfs.impl.ZipHandler.clearFileAccessorCache();
            if (fixture != null) {
                try (var files = Files.walk(fixture)) {
                    for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                }
            }
        }
    }

    private Path compile(Path workspace, String name, Map<String, String> sources,
                         List<Path> classpath, boolean transformed) throws Exception {
        Path src = Files.createDirectories(workspace.resolve(name).resolve("src"));
        Path out = Files.createDirectories(workspace.resolve(name).resolve("classes"));
        List<String> arguments = new ArrayList<>(List.of(
                Path.of(System.getProperty("jype.test.jdk"), "bin",
                        System.getProperty("os.name").startsWith("Windows") ? "javac.exe" : "javac").toString(),
                "--release", "17", "-Xlint:rawtypes,unchecked", "-Werror", "-d", out.toString()));
        if (!classpath.isEmpty()) arguments.addAll(List.of("-classpath",
                classpath.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator))));
        if (transformed) arguments.addAll(List.of("-Xplugin:Jype", "-processor", "fr.thegamerfarmer.jype.JypeProcessorShim"));
        else arguments.add("-proc:none");
        for (var source : sources.entrySet()) {
            Path file = src.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            arguments.add(file.toString());
        }
        Path log = workspace.resolve(name + ".log");
        Process compiler = new ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!compiler.waitFor(90, TimeUnit.SECONDS)) {
            compiler.destroyForcibly();
            fail("Timed out compiling " + name);
        }
        assertEquals(Files.readString(log), 0, compiler.exitValue());
        Path jar = workspace.resolve(name + ".jar");
        try (JarOutputStream archive = new JarOutputStream(Files.newOutputStream(jar)); var files = Files.walk(out)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                archive.putNextEntry(new JarEntry(out.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, archive);
                archive.closeEntry();
            }
        }
        return jar;
    }
}
