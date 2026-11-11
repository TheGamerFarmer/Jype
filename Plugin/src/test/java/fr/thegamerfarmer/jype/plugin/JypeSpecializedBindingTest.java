package fr.thegamerfarmer.jype.plugin;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInspection.uncheckedWarnings.UncheckedWarningLocalInspection;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.psi.PsiFile;
import com.intellij.testFramework.PsiTestUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * A binding of a specialized system stays a binding when its body names an alias, as in the engine:
 * its other aliases are their bindings, only template-valued (residual) aliases stay virtual, and
 * {@code new ALIAS(...)} takes the binding constructor's parameters with the same arguments.
 * The library is compiled, as a consumer sees it.
 */
public class JypeSpecializedBindingTest extends JypeJavaFixture {
    private static final String ALIASES = "J_A, J_B, J_C";
    private static final String BOUNDS = "J_A extends BaseNode<" + ALIASES + ">, J_B extends BaseCommands<"
            + ALIASES + ">, J_C extends BaseCommand<" + ALIASES + ">";
    private static final String METADATA = "@fr.thegamerfarmer.jype.annotations.JypeClass("
            + "managedParameters={\"J_A\", \"J_B\", \"J_C\"}, typeSystem=\"api\") ";

    private Path workspace;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        workspace = Files.createDirectories(Path.of("build", "test-fixtures",
                "jype-binding-" + UUID.randomUUID()).toAbsolutePath());
        Map<String, String> library = new LinkedHashMap<>();
        library.put("fr/thegamerfarmer/jype/annotations/TypeDef.java", """
                package fr.thegamerfarmer.jype.annotations;
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
                public @interface TypeDef { String name(); Class<?> bound(); }
                """);
        library.put("fr/thegamerfarmer/jype/annotations/TypeSystem.java", """
                package fr.thegamerfarmer.jype.annotations;
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
                public @interface TypeSystem {
                    TypeDef[] value() default {}; String inherit() default "";
                    TypeDef[] overrides() default {};
                }
                """);
        library.put("fr/thegamerfarmer/jype/annotations/JypeClass.java", """
                package fr.thegamerfarmer.jype.annotations;
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
                @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)
                public @interface JypeClass {
                    int version() default 1; String[] managedParameters();
                    String[] declaredParameters() default {}; String typeSystem();
                }
                """);
        library.put("api/package-info.java", """
                @fr.thegamerfarmer.jype.annotations.TypeSystem({
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name="J_A", bound=BaseNode.class),
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name="J_B", bound=BaseCommands.class),
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name="J_C", bound=BaseCommand.class)
                }) package api;
                """);
        library.put("api/BaseNode.java", "package api; " + METADATA + "public class BaseNode<" + BOUNDS + "> {}");
        library.put("api/BaseHandler.java", "package api; " + METADATA
                + "public abstract class BaseHandler<" + BOUNDS + "> {}");
        library.put("api/BaseCommand.java", "package api; " + METADATA + "public class BaseCommand<" + BOUNDS + "> {"
                + " public BaseCommand(BaseHandler<" + ALIASES + "> handler) {} }");
        library.put("api/BaseCommands.java", "package api; " + METADATA + "public class BaseCommands<" + BOUNDS + "> {"
                + " public J_C main; public void create() {} }");
        compile(library);
        PsiTestUtil.addLibrary(getTestRootDisposable(), getModule(), "jype-binding-api",
                workspace.toString(), "classes");
        define("game", """
                inherit="api", overrides={
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name="J_A", bound=GameNode.class),
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name="J_B", bound=GameCommands.class)}
                """);
        myFixture.addClass("package game; public class GameNode extends api.BaseNode {}");
        myFixture.addClass("package game; public abstract class GameHandler extends api.BaseHandler {}");
        myFixture.addClass("package game; public class ListHandler extends GameHandler {}");
    }

    public void testBindingNamingAResidualAliasKeepsItsBindings() {
        PsiFile file = myFixture.addFileToProject("game/GameCommands.java", """
                package game;
                public class GameCommands extends api.BaseCommands {
                    public api.BaseCommand list;
                    @Override public void create() {
                        list = new J_C(new ListHandler());
                        main = new J_C(new ListHandler());
                    }
                }
                """);
        assertEmpty(problems(file));
    }

    private List<String> problems(PsiFile file) {
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        myFixture.enableInspections(new UncheckedWarningLocalInspection());
        return myFixture.doHighlighting().stream()
                .filter(info -> info.getSeverity().compareTo(HighlightSeverity.WARNING) >= 0)
                .map(HighlightInfo::getDescription).toList();
    }

    private void compile(Map<String, String> sources) throws Exception {
        Path src = Files.createDirectories(workspace.resolve("src"));
        Path out = Files.createDirectories(workspace.resolve("classes"));
        List<String> arguments = new ArrayList<>(List.of(
                Path.of(System.getProperty("jype.test.jdk"), "bin",
                        System.getProperty("os.name").startsWith("Windows") ? "javac.exe" : "javac").toString(),
                "--release", "17", "-proc:none", "-d", out.toString()));
        for (var source : sources.entrySet()) {
            Path file = src.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            arguments.add(file.toString());
        }
        Path log = workspace.resolve("javac.log");
        Process javac = new ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!javac.waitFor(60, TimeUnit.SECONDS)) {
            javac.destroyForcibly();
            fail("Timed out compiling the library fixture");
        }
        assertEquals(Files.readString(log), 0, javac.exitValue());
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            super.tearDown();
        } finally {
            if (workspace != null) {
                try (var files = Files.walk(workspace)) {
                    for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                }
            }
        }
    }
}
