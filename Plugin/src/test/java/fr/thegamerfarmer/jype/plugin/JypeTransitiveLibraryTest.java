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
            List<String> names = new ArrayList<>(List.of("ENTITY"));
            for (int i = 0; i < 19; i++) names.add("TYPE_" + i);
            String order = String.join(", ", names);
            String parameters = "ENTITY extends api.BaseNode, " + String.join(", ", names.subList(1, names.size()));
            String definitions = names.stream().map(name ->
                    "@fr.thegamerfarmer.jype.annotations.TypeDef(name=\"" + name
                            + "\", bound=" + (name.equals("ENTITY") ? "api.BaseNode" : "Object") + ".class)")
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
            base.put("api/BaseNode.java", "package api; public class BaseNode { public int score() { return 1; } }");
            String fields = names.stream().map(name -> (transformed ? ""
                            : "@fr.thegamerfarmer.jype.annotations.JypeMember(sourceType=\"" + name + "\") ")
                            + "public " + name + " " + name.toLowerCase(Locale.ROOT) + ";")
                    .collect(Collectors.joining("\n"));
            base.put("api/Service.java", "package api; "
                    + (transformed ? "" : "@fr.thegamerfarmer.jype.annotations.JypeClass("
                    + "managedParameters={" + managed + "}, typeSystem=\"api\") ")
                    + "public class Service" + (transformed ? "" : "<" + parameters + ">")
                    + " { " + fields + " public ENTITY entity() { return entity; } "
                    + "public <T> T identity(T value) { return value; } "
                    + "public void accept(ENTITY value) {} public java.util.List<ENTITY> entities() { return null; } "
                    + (transformed ? "public static ENTITY staticEntity() { return null; }"
                    : "@fr.thegamerfarmer.jype.annotations.JypeMember(sourceType=\"ENTITY\") public static <"
                    + parameters + "> ENTITY staticEntity() { return null; }")
                    + " }");
            List<Path> classpath = transformed ? new ArrayList<>(List.of(Path.of(engine))) : new ArrayList<>();
            Path baseJar = compile(workspace, "base", base, classpath, transformed);
            classpath.add(baseJar);
            Map<String, String> api = Map.of(
                    "lib/package-info.java", """
                            @fr.thegamerfarmer.jype.annotations.TypeSystem(inherit="api") package lib;
                            """,
                    "lib/LibCore.java", "package lib; "
                            + (transformed ? "" : "@fr.thegamerfarmer.jype.annotations.JypeClass("
                            + "managedParameters={" + managed + "}, typeSystem=\"lib\") ")
                            + "public class LibCore" + (transformed ? "" : "<" + parameters + ">")
                            + " extends api.Service" + (transformed ? "" : "<" + order + ">")
                            + " { public ENTITY current() { return entity(); } }");
            Path apiJar = compile(workspace, "lib", api, classpath, transformed);
            classpath.add(apiJar);
            String bindings = "game.ConcreteNode" + ", java.lang.Object".repeat(19);
            Map<String, String> consumer = Map.of(
                    "game/package-info.java", """
                            @fr.thegamerfarmer.jype.annotations.TypeSystem(inherit="lib",
                                overrides=@fr.thegamerfarmer.jype.annotations.TypeDef(
                                    name="ENTITY", bound=game.ConcreteNode.class)) package game;
                            """,
                    "game/ConcreteNode.java", "package game; public class ConcreteNode extends api.BaseNode {}",
                    "game/CompiledConsumer.java", "package game; public class CompiledConsumer { lib.LibCore"
                            + (transformed ? "" : "<" + bindings + ">")
                            + " service; int score() { service.accept(service.entity()); return service.entity().score(); } }");
            compile(workspace, "consumer", consumer, classpath, transformed);
            PsiTestUtil.addLibrary(getTestRootDisposable(), getModule(), "jype-base", workspace.toString(), baseJar.getFileName().toString());
            PsiTestUtil.addLibrary(getTestRootDisposable(), getModule(), "jype-lib", workspace.toString(), apiJar.getFileName().toString());
            myFixture.addClass("package game; public class ConcreteNode extends api.BaseNode {}");
            define("game", """
                    inherit="lib", overrides=@fr.thegamerfarmer.jype.annotations.TypeDef(
                        name="ENTITY", bound=game.ConcreteNode.class)
                    """);
            PsiFile source = myFixture.addFileToProject("game/Consumer.java", """
                    package game;
                    class Consumer {
                        lib.LibCore service;
                        ENTITY entity() { service.accept(service.entity()); return service.entity(); }
                        java.util.List<ENTITY> entities() { return service.entities(); }
                        int score() { return service.entity().score(); }
                    }
                    """);
            myFixture.configureFromExistingVirtualFile(source.getVirtualFile());
            myFixture.enableInspections(new RawUseOfParameterizedTypeInspection(), new UncheckedWarningLocalInspection());
            myFixture.checkHighlighting(true, false, false);
            PsiClass cls = ((PsiJavaFile) source).getClasses()[0];
            PsiClassType type = (PsiClassType) cls.getFields()[0].getType();
            assertEquals("LibCore", type.getPresentableText());
            assertEquals(20, type.getParameters().length);
            PsiClass libraryClass = type.resolve();
            assertNotNull(JypeClassMetadata.read(libraryClass));
            String doc = DocumentationManager.getProviderFromElement(libraryClass).generateDoc(libraryClass, null);
            assertTrue(doc, doc.contains("class LibCore extends Service"));
            assertFalse(doc, doc.contains("TYPE_18"));
            PsiMethodCallExpression call = PsiTreeUtil.findChildrenOfType(source, PsiMethodCallExpression.class)
                    .stream().filter(item -> "entity".equals(item.getMethodExpression().getReferenceName()))
                    .findFirst().orElseThrow();
            assertEquals("game.ENTITY", call.getType().getCanonicalText());
            PsiMethod method = call.resolveMethod();
            doc = DocumentationManager.getProviderFromElement(method).generateDoc(method, call);
            assertTrue(doc, doc.contains("ENTITY entity()"));
            assertFalse(doc, doc.contains("TYPE_18"));
            PsiClass baseClass = JavaPsiFacade.getInstance(getProject()).findClass("api.Service", source.getResolveScope());
            assertNotNull(baseClass);
            PsiField entityField = baseClass.findFieldByName("entity", false);
            assertNotNull(entityField);
            assertNotNull(JypeMemberMetadata.read(entityField));
            assertEquals("ENTITY", JypePublicPresentation.memberType(entityField, entityField.getType()));
            PsiMethod staticEntity = baseClass.findMethodsByName("staticEntity", false)[0];
            doc = DocumentationManager.getProviderFromElement(staticEntity).generateDoc(staticEntity, null);
            assertTrue(doc, doc.contains("public static ENTITY staticEntity()"));
            assertTrue(doc, doc.contains("psi_element://api.ENTITY"));
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
