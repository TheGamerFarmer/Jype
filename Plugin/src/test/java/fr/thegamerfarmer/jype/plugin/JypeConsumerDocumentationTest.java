package fr.thegamerfarmer.jype.plugin;

import com.intellij.codeInsight.documentation.DocumentationManager;
import com.intellij.lang.documentation.DocumentationProvider;
import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.roots.libraries.LibraryTablesRegistrar;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.PsiTestUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * A consumer reading the documentation of a compiled Jype library with attached sources, as
 * BattleGame reads API UHC: members reached through the consumer's own classes, aliases and
 * their constructors show the public API declaration and its comment, never the technical
 * arguments ({@code Command<GameNode, COMMAND, Object, …>}) of the Jype types they mention.
 */
public class JypeConsumerDocumentationTest extends JypeJavaFixture {
    private static final List<String> NAMES = List.of("ENTITY", "COMMAND", "TYPE_0", "TYPE_1", "TYPE_2", "TYPE_3");
    private static final String ALIASES = String.join(", ", NAMES);
    private static final String BOUNDS = "ENTITY extends Node<" + ALIASES + ">, COMMAND extends Command<"
            + ALIASES + ">, " + String.join(", ", NAMES.subList(2, NAMES.size()));
    private static final String METADATA = "@fr.thegamerfarmer.jype.annotations.JypeClass(managedParameters={"
            + NAMES.stream().map(name -> "\"" + name + "\"").collect(Collectors.joining(", "))
            + "}, typeSystem=\"api\") ";
    private static final String SYSTEM = "@fr.thegamerfarmer.jype.annotations.TypeSystem({"
            + "@fr.thegamerfarmer.jype.annotations.TypeDef(name=\"ENTITY\", bound=api.Node.class), "
            + "@fr.thegamerfarmer.jype.annotations.TypeDef(name=\"COMMAND\", bound=api.Command.class), "
            + IntStream.range(0, 4).mapToObj(i -> "@fr.thegamerfarmer.jype.annotations.TypeDef(name=\"TYPE_" + i
            + "\", bound=Object.class)").collect(Collectors.joining(", ")) + "}) package api;";

    private Path workspace;
    private Library library;
    private PsiFile consumer;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        workspace = Files.createDirectories(Path.of("build", "test-fixtures",
                "jype-consumer-doc-" + UUID.randomUUID()).toAbsolutePath());
        Map<String, String> compiled = new LinkedHashMap<>(annotations());
        compiled.put("api/package-info.java", SYSTEM);
        compiled.put("api/Node.java", "package api; " + METADATA + "public class Node<" + BOUNDS + "> {"
                + " @fr.thegamerfarmer.jype.annotations.JypeMember(sourceType=\"Command\")"
                + " public Command<" + ALIASES + "> command() { return null; }"
                + " public void kill(ENTITY killer) {}"
                + " @fr.thegamerfarmer.jype.annotations.JypeMember(sourceType=\"java.util.List<Command>\")"
                + " public java.util.List<Command<" + ALIASES + ">> commands; }");
        compiled.put("api/Command.java", "package api; " + METADATA + "public class Command<" + BOUNDS + "> {"
                + " public Command(Node<" + ALIASES + "> owner) {}"
                + " public Command(Command<" + ALIASES + "> other) {} }");
        Path classes = compile(compiled);
        renameSourceFileAttributes(classes.resolve("api"));
        // The attached sources are the library's Jype sources, as its -sources.jar publishes them.
        Path sources = write("sources", Map.of(
                "api/package-info.java", SYSTEM,
                "api/Node.java", """
                        package api;
                        /** A node of the game. */
                        public class Node {
                            /** The command of the node. */
                            public Command command() { return null; }
                            /**
                             * Kills the node.
                             * @param killer the node that kills
                             */
                            public void kill(ENTITY killer) {}
                            /** Every command of the node. */
                            public java.util.List<Command> commands;
                        }
                        """,
                "api/Command.java", """
                        package api;
                        /** A command. */
                        public class Command {
                            /**
                             * Creates a command of a node.
                             * @param owner the node owning it
                             */
                            public Command(Node owner) {}
                            /**
                             * Copies a command.
                             * @param other the copied command
                             */
                            public Command(Command other) {}
                        }
                        """));
        LocalFileSystem files = LocalFileSystem.getInstance();
        VirtualFile classRoot = files.refreshAndFindFileByNioFile(classes);
        VirtualFile sourceRoot = files.refreshAndFindFileByNioFile(sources);
        VfsUtil.markDirtyAndRefresh(false, true, true, classRoot, sourceRoot);
        library = PsiTestUtil.addProjectLibrary(getModule(), "jype-consumer-doc", List.of(classRoot), List.of(sourceRoot));
        myFixture.addClass("package game; public class GameNode extends api.Node {}");
        define("game", """
                inherit="api", overrides=@fr.thegamerfarmer.jype.annotations.TypeDef(
                    name="ENTITY", bound=game.GameNode.class)
                """);
        consumer = myFixture.addFileToProject("game/Use.java", """
                package game;
                class Use {
                    api.Node direct;
                    void use(GameNode node) {
                        node.command();
                        Object commands = node.commands;
                        node.kill(node);
                        direct.kill(null);
                        COMMAND command = new COMMAND(node);
                    }
                }
                """);
        myFixture.configureFromExistingVirtualFile(consumer.getVirtualFile());
    }

    public void testInheritedMembersShowTheApiDeclaration() {
        String doc = document(call("command"));
        assertClean(doc);
        assertTrue(doc, doc.contains("public Command command()"));
        assertTrue(doc, doc.contains("The command of the node."));
        assertTrue(doc, doc.contains("psi_element://api.Node"));
        assertTrue(quick(call("command")), quick(call("command")).contains("public Command command()"));

        PsiReferenceExpression field = PsiTreeUtil.findChildrenOfType(consumer, PsiReferenceExpression.class).stream()
                .filter(reference -> "commands".equals(reference.getReferenceName())).findFirst().orElseThrow();
        doc = document(field.resolve(), field.getReferenceNameElement());
        assertClean(doc);
        assertTrue(doc, doc.contains("public List&lt;Command&gt; commands"));
        assertTrue(doc, doc.contains("Every command of the node."));
    }

    public void testCompiledMethodNamingAnAliasKeepsItsComment() {
        for (PsiMethodCallExpression call : calls("kill")) {
            String doc = document(call.resolveMethod(), call.getMethodExpression().getReferenceNameElement());
            assertClean(doc);
            assertTrue(doc, doc.contains("public void kill(ENTITY killer)"));
            assertTrue(doc, doc.contains("Kills the node."));
            assertTrue(doc, doc.contains("the node that kills"));
        }
    }

    public void testAliasClassAndConstructorsShowPublicSignatures() {
        PsiNewExpression creation = PsiTreeUtil.findChildOfType(consumer, PsiNewExpression.class);
        assertNotNull(creation);
        PsiJavaCodeReferenceElement name = Objects.requireNonNull(creation.getClassReference());
        PsiClass alias = (PsiClass) name.resolve();
        assertNotNull(alias);
        assertTrue(JypeTypeSystemReader.isVirtualClass(alias));

        PsiLocalVariable variable = PsiTreeUtil.findChildrenOfType(consumer, PsiLocalVariable.class).stream()
                .filter(local -> "command".equals(local.getName())).findFirst().orElseThrow();
        PsiJavaCodeReferenceElement type = variable.getTypeElement().getInnermostComponentReferenceElement();
        String doc = document(alias, Objects.requireNonNull(type).getReferenceNameElement());
        assertClean(doc);
        assertTrue(doc, doc.contains("public class COMMAND extends Command"));
        assertEquals("public class COMMAND extends Command", quick(alias));

        doc = document(creation.resolveConstructor(), name.getReferenceNameElement());
        assertClean(doc);
        assertTrue(doc, doc.contains("public Command(Node owner)"));
        assertTrue(doc, doc.contains("Creates a command of a node."));

        // Hovering the class name after `new`: Java lists the candidate constructors.
        doc = document(alias, name.getReferenceNameElement());
        assertClean(doc);
        assertTrue(doc, doc.contains("public Command(Node owner)"));
        assertTrue(doc, doc.contains("public Command(Command other)"));
        assertTrue(doc, doc.contains("psi_element://api.Command#Command(api.Node)"));
    }

    /** IntelliJ may document the attached source declaration instead of the class file it resolved to. */
    public void testAttachedSourceDeclarationsShowThePublicSignature() {
        PsiClass node = (PsiClass) library("api.Node").getNavigationElement();
        assertFalse(node instanceof PsiCompiledElement);
        String doc = document(node);
        assertClean(doc);
        assertTrue(doc, doc.contains("A node of the game."));

        doc = document(node.findMethodsByName("kill", false)[0]);
        assertClean(doc);
        assertTrue(doc, doc.contains("public void kill(ENTITY killer)"));
        assertTrue(doc, doc.contains("Kills the node."));
        assertTrue(doc, doc.contains("psi_element://api.Node"));

        doc = document(node.findMethodsByName("command", false)[0]);
        assertClean(doc);
        assertTrue(doc, doc.contains("public Command command()"));
        assertTrue(quick(node.findMethodsByName("command", false)[0]),
                quick(node.findMethodsByName("command", false)[0]).contains("public Command command()"));

        doc = document(Objects.requireNonNull(node.findFieldByName("commands", false)));
        assertClean(doc);
        assertTrue(doc, doc.contains("public List&lt;Command&gt; commands"));

        PsiClass command = (PsiClass) library("api.Command").getNavigationElement();
        for (PsiMethod constructor : command.getConstructors()) {
            doc = document(constructor);
            assertClean(doc);
            assertTrue(doc, doc.contains("public Command(Node owner)") || doc.contains("public Command(Command other)"));
        }
    }

    /** The class files name no source (see {@link #renameSourceFileAttributes}); their members still reach it. */
    public void testCompiledDeclarationsNavigateToTheirSource() {
        PsiClass node = library("api.Node");
        assertFalse(node.getNavigationElement() instanceof PsiCompiledElement);
        for (PsiMethod method : node.getMethods()) {
            if (!method.isConstructor()) assertFalse(method.getName(), method.getNavigationElement() instanceof PsiCompiledElement);
        }
        assertFalse(Objects.requireNonNull(node.findFieldByName("commands", false))
                .getNavigationElement() instanceof PsiCompiledElement);
        for (PsiMethod constructor : library("api.Command").getConstructors()) {
            assertFalse(constructor.getNavigationElement() instanceof PsiCompiledElement);
        }
        // The source comment is documented, not the library's HTML javadoc of the expanded declaration.
        assertEquals(List.of(), provider(call("command"), null).getUrlFor(call("command"), null));
        PsiMethod kill = calls("kill").get(1).resolveMethod();
        assertEquals(List.of(), provider(kill, null).getUrlFor(kill, null));
    }

    private PsiClass library(String name) {
        PsiClass cls = JavaPsiFacade.getInstance(getProject()).findClass(name,
                com.intellij.psi.search.GlobalSearchScope.allScope(getProject()));
        assertTrue(String.valueOf(cls), cls instanceof PsiCompiledElement);
        return cls;
    }

    private void assertClean(String doc) {
        assertNotNull(doc);
        for (String technical : List.of("TYPE_0", "&lt;GameNode", "&lt;ENTITY", "Object&gt;", "p0")) {
            assertFalse(technical + " in " + doc, doc.contains(technical));
        }
    }

    private PsiMethod call(String name) {
        return calls(name).get(0).resolveMethod();
    }

    private List<PsiMethodCallExpression> calls(String name) {
        List<PsiMethodCallExpression> calls = PsiTreeUtil.findChildrenOfType(consumer, PsiMethodCallExpression.class)
                .stream().filter(call -> name.equals(call.getMethodExpression().getReferenceName())).toList();
        assertFalse(calls.isEmpty());
        return calls;
    }

    private static String document(PsiElement element) {
        return document(element, null);
    }

    private static String document(PsiElement element, PsiElement original) {
        assertNotNull(element);
        return provider(element, original).generateDoc(element, original);
    }

    private static String quick(PsiElement element) {
        return provider(element, null).getQuickNavigateInfo(element, null);
    }

    @SuppressWarnings("removal")
    private static DocumentationProvider provider(PsiElement element, PsiElement original) {
        return DocumentationManager.getProviderFromElement(element, original);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            if (library != null) {
                PsiTestUtil.removeLibrary(getModule(), library);
                WriteAction.runAndWait(() -> LibraryTablesRegistrar.getInstance()
                        .getLibraryTable(getProject()).removeLibrary(library));
            }
            super.tearDown();
        } finally {
            if (workspace != null) {
                try (var files = Files.walk(workspace)) {
                    for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                }
            }
        }
    }

    private static Map<String, String> annotations() {
        return Map.of(
                "fr/thegamerfarmer/jype/annotations/TypeDef.java", """
                        package fr.thegamerfarmer.jype.annotations;
                        @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
                        public @interface TypeDef { String name(); Class<?> bound(); }
                        """,
                "fr/thegamerfarmer/jype/annotations/TypeSystem.java", """
                        package fr.thegamerfarmer.jype.annotations;
                        @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
                        public @interface TypeSystem {
                            TypeDef[] value() default {}; String inherit() default "";
                            TypeDef[] overrides() default {};
                        }
                        """,
                "fr/thegamerfarmer/jype/annotations/JypeClass.java", """
                        package fr.thegamerfarmer.jype.annotations;
                        @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
                        @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)
                        public @interface JypeClass {
                            int version() default 1; String[] managedParameters();
                            String[] declaredParameters() default {}; String typeSystem();
                        }
                        """,
                "fr/thegamerfarmer/jype/annotations/JypeMember.java", """
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

    /**
     * Rewrites the {@code SourceFile} attribute of each class file as ProGuard's
     * {@code -renamesourcefileattribute SourceFile} does for API UHC: IntelliJ no longer finds the
     * attached source through it.
     */
    private static void renameSourceFileAttributes(Path classes) throws Exception {
        try (var files = Files.list(classes)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                String name = file.getFileName().toString();
                byte[] from = utf8Constant(name.substring(0, name.length() - ".class".length()) + ".java");
                byte[] to = utf8Constant("SourceFile");
                byte[] bytes = Files.readAllBytes(file);
                int at = -1;
                for (int i = 0; at < 0 && i + from.length <= bytes.length; i++) {
                    if (Arrays.equals(bytes, i, i + from.length, from, 0, from.length)) at = i;
                }
                assertTrue(name, at >= 0);
                byte[] patched = new byte[bytes.length - from.length + to.length];
                System.arraycopy(bytes, 0, patched, 0, at);
                System.arraycopy(to, 0, patched, at, to.length);
                System.arraycopy(bytes, at + from.length, patched, at + to.length, bytes.length - at - from.length);
                Files.write(file, patched);
            }
        }
    }

    private static byte[] utf8Constant(String value) {
        byte[] text = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] constant = new byte[text.length + 3];
        constant[0] = 1;
        constant[1] = (byte) (text.length >> 8);
        constant[2] = (byte) text.length;
        System.arraycopy(text, 0, constant, 3, text.length);
        return constant;
    }

    private Path write(String name, Map<String, String> sources) throws Exception {
        Path root = Files.createDirectories(workspace.resolve(name));
        for (var source : sources.entrySet()) {
            Path file = root.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
        }
        return root;
    }

    private Path compile(Map<String, String> sources) throws Exception {
        Path src = write("compiled-src", sources);
        Path out = Files.createDirectories(workspace.resolve("classes"));
        List<String> arguments = new ArrayList<>(List.of(
                Path.of(System.getProperty("jype.test.jdk"), "bin",
                        System.getProperty("os.name").startsWith("Windows") ? "javac.exe" : "javac").toString(),
                "--release", "17", "-proc:none", "-d", out.toString()));
        for (String file : sources.keySet()) arguments.add(src.resolve(file).toString());
        Path log = workspace.resolve("compile.log");
        Process compiler = new ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!compiler.waitFor(90, TimeUnit.SECONDS)) {
            compiler.destroyForcibly();
            fail("Timed out compiling the library");
        }
        assertEquals(Files.readString(log), 0, compiler.exitValue());
        return out;
    }
}
