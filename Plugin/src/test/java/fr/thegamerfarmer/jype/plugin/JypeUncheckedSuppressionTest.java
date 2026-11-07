package fr.thegamerfarmer.jype.plugin;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInspection.miscGenerics.RawUseOfParameterizedTypeInspection;
import com.intellij.codeInspection.uncheckedWarnings.UncheckedWarningLocalInspection;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;

import java.util.List;

/**
 * Unchecked warnings caused only by raw Jype parents (javac completes them) are hidden; genuine
 * unchecked operations keep their warnings.
 */
public class JypeUncheckedSuppressionTest extends JypeJavaFixture {
    private static final String ALIASES = "J_A, J_B, J_C";

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        define("api", """
                {@fr.thegamerfarmer.jype.annotations.TypeDef(name="J_A", bound=BaseNode.class),
                 @fr.thegamerfarmer.jype.annotations.TypeDef(name="J_B", bound=BaseKind.class),
                 @fr.thegamerfarmer.jype.annotations.TypeDef(name="J_C", bound=BaseRegistry.class)}
                """);
        String bounds = "J_A extends BaseNode<" + ALIASES + ">, J_B extends BaseKind<" + ALIASES
                + ">, J_C extends BaseRegistry<" + ALIASES + ">";
        myFixture.addClass("package api; public class AbstractBase<" + bounds + "> { protected J_C itemManager; }");
        myFixture.addClass("package api; public class BaseNode<" + bounds + "> extends AbstractBase<" + ALIASES + "> {}");
        myFixture.addClass("package api; public abstract class BaseKind<" + bounds + "> extends AbstractBase<" + ALIASES + "> {}");
        myFixture.addClass("package api; public class BaseRegistry<" + bounds + "> extends AbstractBase<" + ALIASES + "> {"
                + " public void give(J_A item) {} public java.util.List<J_A> items() { return null; } }");
        myFixture.addClass("package api; public abstract class AbstractEffect<" + bounds + "> { public AbstractEffect(J_A item) {} }");
        myFixture.addClass("package api; public class Dispatcher<" + bounds + "> extends AbstractBase<" + ALIASES + "> {"
                + " public void register(J_A item) {} public java.util.List<String> labels() { return null; }"
                + " public void send(J_A... items) {} public void each(java.util.function.Consumer<J_A> action) {}"
                + " public <T> T pick(T value, J_A item) { return value; }"
                + " public static void broadcast(java.util.List<String> lines) {} }");
        myFixture.addClass("package api; public abstract class CommandBase<" + bounds + "> {}");
        myFixture.addClass("package api; public class CommandHub<" + bounds + "> { protected java.util.function.BiFunction<"
                + "CommandBase<" + ALIASES + ">, CommandHub<" + ALIASES + ">, String> constructor; }");
        myFixture.addClass("package api; public abstract class AbstractPrimary<" + bounds + "> {}");
        myFixture.addClass("package api; public abstract class AbstractSecondary<MASTER extends AbstractPrimary<" + ALIASES + ">, "
                + bounds + "> { public AbstractSecondary(MASTER master) {} }");
        define("game", """
                inherit="api", overrides={
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name="J_A", bound=ConcreteNode.class),
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name="J_B", bound=ConcreteKind.class)}
                """);
        myFixture.addClass("package game; public class ConcreteNode extends api.BaseNode {}");
        myFixture.addClass("package game; public abstract class ConcreteKind extends api.BaseKind {}");
        myFixture.addClass("package game; public abstract class ChildCommand extends api.CommandBase {}");
        myFixture.addClass("package game; public class ListCmd extends ChildCommand {}");
        myFixture.addClass("package game; public abstract class ConcretePrimary extends api.AbstractPrimary {}");
    }

    public void testWarningsCausedOnlyByRawJypeParentsAreHidden() {
        assertNoUncheckedWarnings(myFixture.addFileToProject("game/ChildEffect.java", """
                package game;
                public abstract class ChildEffect extends api.AbstractEffect {
                    ChildEffect(ConcreteNode item) { super(item); }
                }
                """));
        assertNoUncheckedWarnings(myFixture.addFileToProject("game/ChildDispatcher.java", """
                package game;
                public class ChildDispatcher extends api.Dispatcher {
                    @Override public void register(ConcreteNode item) { super.register(item); }
                    java.util.List<String> all() { java.util.List<String> labels = super.labels(); return labels; }
                }
                """));
        assertNoUncheckedWarnings(myFixture.addFileToProject("game/ChildCommandHub.java", """
                package game;
                public class ChildCommandHub extends api.CommandHub {
                    public String LIST = constructor.apply(new ListCmd(), this);
                }
                """));
        assertNoUncheckedWarnings(myFixture.addFileToProject("game/ChildSecondary.java", """
                package game;
                public abstract class ChildSecondary<MASTER extends ConcretePrimary> extends api.AbstractSecondary<MASTER> {
                    ChildSecondary(MASTER master) { super(master); }
                }
                """));
    }

    public void testTemplateAliasMembersKeepTheirGenericTypes() {
        assertNoUncheckedWarnings(myFixture.addFileToProject("game/Use.java", """
                package game;
                public class Use extends api.AbstractBase {
                    java.util.List<ConcreteNode> run(ConcreteNode item) {
                        itemManager.give(item);
                        return itemManager.items();
                    }
                }
                """));
    }

    public void testRawJypeCastIsNotUnchecked() {
        myFixture.addClass("package api; public class Box<T, " + "J_A extends BaseNode<" + ALIASES
                + ">, J_B extends BaseKind<" + ALIASES + ">, J_C extends BaseRegistry<" + ALIASES + ">> {}");
        PsiFile file = myFixture.addFileToProject("game/Main.java", """
                package game;
                class Main {
                    api.Dispatcher manager(Object plugin) { return (api.Dispatcher) plugin; }
                    api.Box<String> box(Object value) { return (api.Box<String>) value; }
                }
                """);
        List<String> warnings = uncheckedWarnings(file);
        assertEquals(warnings.toString(), 1, warnings.size());
        assertTrue(warnings.toString(), warnings.get(0).contains("'api.Box<java.lang.String"));
    }

    public void testGenuineUncheckedOperationsAreStillReported() {
        PsiFile file = myFixture.addFileToProject("game/Genuine.java", """
                package game;
                @SuppressWarnings("rawtypes")
                class Genuine extends java.util.ArrayList {
                    void add() { add("value"); }
                    java.util.List<String> cast(Object value) { return (java.util.List<String>) value; }
                    void raw(java.util.List values, ChildDispatcher manager) { take(values); }
                    void take(java.util.List<ConcreteNode> items) {}
                }
                """);
        myFixture.addClass("package game; public class ChildDispatcher extends api.Dispatcher {}");
        List<String> warnings = uncheckedWarnings(file);
        assertTrue(warnings.toString(), warnings.stream().anyMatch(w -> w.startsWith("Unchecked call to 'add(E)'")));
        assertTrue(warnings.toString(), warnings.stream().anyMatch(w -> w.startsWith("Unchecked cast")));
        assertTrue(warnings.toString(), warnings.stream().anyMatch(w -> w.startsWith("Unchecked assignment: 'java.util.List'")));
    }

    public void testCallsAreReplayedWithCompletedTypes() {
        myFixture.addClass("package game; public class ChildDispatcher extends api.Dispatcher {}");
        PsiFile file = myFixture.addFileToProject("game/Calls.java", """
                package game;
                class Calls extends ChildDispatcher {
                    void run(ConcreteNode item, ChildDispatcher manager, ConcreteNode[] items) {
                        send(item); send(item, item); send(items); send();
                        each(value -> value.hashCode());
                        each(() -> {});
                        String picked = pick("value", item);
                        manager.register(item);
                        api.Dispatcher.broadcast(java.util.List.of("line"));
                        Object anonymous = new Object() {};
                        new Thread();
                        missing(item);
                    }
                    class Inner {
                        void run(ConcreteNode item) {
                            Calls.this.register(item);
                            Calls.super.register(item);
                            register(item);
                        }
                    }
                }
                """);
        assertNotNull(check(file, "send(item)"));
        assertNotNull(check(file, "send(item, item)"));
        assertNotNull(check(file, "send(items)"));
        assertNotNull(check(file, "send()"));
        assertNotNull(check(file, "each(value"));
        assertNull(check(file, "each(()"));
        JypeCompletedTypes.Call picked = check(file, "pick(");
        assertNotNull(picked);
        assertEquals("java.lang.String", picked.returnType().getCanonicalText());
        assertNotNull(check(file, "manager.register"));
        assertTrue(check(file, "api.Dispatcher.broadcast").jype());
        assertFalse(check(file, "new Object()").jype());
        assertFalse(check(file, "new Thread()").jype());
        assertNull(check(file, "missing("));
        assertNotNull(check(file, "Calls.this.register"));
        assertNotNull(check(file, "Calls.super.register"));
        assertNotNull(check(file, "register(item);\n"));
    }

    public void testCompletedAssignabilityAndSupertypes() {
        PsiElementFactory factory = JavaPsiFacade.getElementFactory(getProject());
        PsiClass item = JavaPsiFacade.getInstance(getProject()).findClass("game.ConcreteNode", scope());
        PsiClass baseNode = JavaPsiFacade.getInstance(getProject()).findClass("api.BaseNode", scope());
        PsiClass kind = JavaPsiFacade.getInstance(getProject()).findClass("game.ConcreteKind", scope());
        assertNotNull(item);
        PsiClassType itemType = factory.createType(item);
        PsiClassType completed = JypeCompletedTypes.supertype(itemType, baseNode);
        assertNotNull(completed);
        assertFalse(completed.isRaw());
        assertTrue(JypeCompletedTypes.isAssignable(completed, itemType));
        assertFalse(JypeCompletedTypes.isAssignable(completed, null));
        assertFalse(JypeCompletedTypes.isAssignable(PsiTypes.intType(), itemType));
        assertFalse(JypeCompletedTypes.isAssignable(factory.createType(baseNode), factory.createType(kind)));
        assertFalse(JypeCompletedTypes.isAssignable(completed, factory.createType(kind)));
        assertNull(JypeCompletedTypes.supertype(itemType, kind));
        assertTrue(JypeCompletedTypes.mentionsJype(completed.createArrayType()));
        assertTrue(JypeCompletedTypes.mentionsJype(PsiWildcardType.createExtends(getPsiManager(), completed)));
        assertFalse(JypeCompletedTypes.mentionsJype(PsiTypes.intType()));
        assertFalse(JypeCompletedTypes.mentionsJype(itemType));
        PsiClass runnable = myFixture.addClass("package game; class Task implements Runnable { public void run() {} }");
        PsiClass object = JavaPsiFacade.getInstance(getProject()).findClass("java.lang.Object", scope());
        assertNotNull(JypeCompletedTypes.supertype(factory.createType(runnable), object));
    }

    public void testSuppressorIgnoresOtherToolsAndNonExpressions() {
        PsiFile file = myFixture.addFileToProject("game/Other.java", "package game; class Other { int x = 1; }");
        JypeUncheckedInspectionSuppressor suppressor = new JypeUncheckedInspectionSuppressor();
        assertFalse(suppressor.isSuppressedFor(file, "rawtypes"));
        assertFalse(suppressor.isSuppressedFor(file, "unchecked"));
        PsiLiteralExpression literal = PsiTreeUtil.findChildOfType(file, PsiLiteralExpression.class);
        assertFalse(suppressor.isSuppressedFor(literal, "unchecked"));
        assertEmpty(suppressor.getSuppressActions(literal, "unchecked"));
    }

    private JypeCompletedTypes.Call check(PsiFile file, String prefix) {
        PsiCallExpression call = PsiTreeUtil.findChildrenOfType(file, PsiCallExpression.class).stream()
                .filter(item -> file.getText().startsWith(prefix, item.getTextRange().getStartOffset()))
                .findFirst().orElseThrow(() -> new AssertionError(prefix));
        return JypeCompletedTypes.check(call);
    }

    private void assertNoUncheckedWarnings(PsiFile file) {
        assertEmpty(uncheckedWarnings(file));
    }

    private List<String> uncheckedWarnings(PsiFile file) {
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        myFixture.enableInspections(new UncheckedWarningLocalInspection(), new RawUseOfParameterizedTypeInspection());
        return myFixture.doHighlighting().stream().map(HighlightInfo::getDescription)
                .filter(description -> description != null
                        && (description.contains("nchecked") || description.contains("Raw use")
                        || description.contains("Cannot resolve") || description.contains("cannot be applied")))
                .toList();
    }
}
