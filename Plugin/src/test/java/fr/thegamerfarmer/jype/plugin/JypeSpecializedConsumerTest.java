package fr.thegamerfarmer.jype.plugin;

import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;

/**
 * A consumer overriding aliases with its own classes and inheriting raw
 * Jype parents must show no error that javac, with Jype, does not report.
 */
public class JypeSpecializedConsumerTest extends JypeJavaFixture {
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        define("api", """
                {@fr.thegamerfarmer.jype.annotations.TypeDef(name="J_A", bound=BaseNode.class),
                 @fr.thegamerfarmer.jype.annotations.TypeDef(name="J_B", bound=BaseKind.class)}
                """);
        myFixture.addClass("""
                package api;
                public class BaseNode<J_A, J_B> {
                    protected J_B kind;
                    public J_B getKind() { return kind; }
                    public void accept(J_A item) {}
                    public java.util.List<J_B> kinds() { return null; }
                    public void addAction(String id, java.util.function.Function<String, Boolean> action) {}
                    public String getLabel() { return ""; }
                }
                """);
        myFixture.addClass("package api; public abstract class BaseKind<J_A, J_B> {}");
        myFixture.addClass("""
                package api;
                public interface BaseContract<J_A, J_B> { void dispatch(J_A item); }
                """);
        myFixture.addClass("""
                package api;
                public class BaseRegistry<J_A, J_B> { public void register(J_A item) {} }
                """);
        define("game", """
                inherit="api", overrides={
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name="J_A", bound=ConcreteNode.class),
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name="J_B", bound=ConcreteKind.class)}
                """);
        myFixture.addClass("package game; public class ConcreteNode extends api.BaseNode {}");
        myFixture.addClass("package game; public abstract class ConcreteKind extends api.BaseKind {}");
        myFixture.addClass("package game; public class LeafKind extends ConcreteKind {}");
    }

    public void testAliasesAreTheirBindingsInConsumerClasses() {
        PsiFile file = myFixture.addFileToProject("game/Use.java", """
                package game;
                class Use {
                    java.util.Map<String, ConcreteKind> kinds = new java.util.HashMap<>();
                    boolean test(ConcreteNode item) {
                        if (item.getKind() instanceof LeafKind) {
                            LeafKind leaf = (LeafKind) item.getKind();
                            kinds.put("leaf", leaf);
                        }
                        item.accept(item);
                        java.util.List<ConcreteKind> all = item.kinds();
                        return all.isEmpty();
                    }
                }
                """);
        assertNoErrors(file);
        PsiMethodCallExpression call = PsiTreeUtil.findChildrenOfType(file, PsiMethodCallExpression.class).stream()
                .filter(c -> "getKind".equals(c.getMethodExpression().getReferenceName())).findFirst().orElseThrow();
        assertEquals("game.ConcreteKind", call.getType().getCanonicalText());
    }

    public void testDirectImplementationsOfRawJypeParentsOverrideCompletedMembers() {
        PsiFile variants = myFixture.addFileToProject("game/Variants.java", """
                package game;
                public enum Variants implements api.BaseContract {
                    ACTIVE;
                    @Override public void dispatch(ConcreteNode item) {}
                }
                """);
        PsiFile registry = myFixture.addFileToProject("game/ChildRegistry.java", """
                package game;
                public class ChildRegistry extends api.BaseRegistry {
                    @Override public void register(ConcreteNode item) { super.register(item); }
                }
                """);
        assertNoErrors(variants);
        assertNoErrors(registry);
    }

    public void testWrongOverridesAreStillReported() {
        PsiFile file = myFixture.addFileToProject("game/Wrong.java", """
                package game;
                public class Wrong implements api.BaseContract {
                    @Override public void dispatch(String item) {}
                }
                """);
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        var errors = myFixture.doHighlighting(HighlightSeverity.ERROR).stream()
                .map(info -> String.valueOf(info.getDescription())).toList();
        assertTrue(errors.toString(), errors.stream().anyMatch(error -> error.contains("must either be declared abstract")));
        assertTrue(errors.toString(), errors.contains("Method does not override method from its superclass"));
    }

    public void testRawInheritanceKeepsOrdinaryMembersWithoutClashes() {
        PsiFile file = myFixture.addFileToProject("game/Custom.java", """
                package game;
                class Custom extends ConcreteNode {
                    Class<? extends Custom> type() { return getClass(); }
                    void use(java.util.List<ConcreteNode> items) {
                        addAction("empty", id -> id.isEmpty());
                        items.stream().map(ConcreteNode::getLabel).forEach(String::length);
                    }
                }
                """);
        assertNoErrors(file);
        PsiClass item = JavaPsiFacade.getInstance(getProject()).findClass("game.ConcreteNode", scope());
        assertNotNull(item);
        assertEmpty(JypeInheritedMembers.methods(item, "getLabel"));
        assertEmpty(JypeInheritedMembers.methods(item, "getClass"));
        assertSize(1, JypeInheritedMembers.methods(item, "addAction"));
    }

    public void testCompletedLocalWithoutInitializerIsNotInferred() {
        PsiFile file = myFixture.addFileToProject("game/Locals.java", """
                package game;
                class Locals {
                    boolean run(ConcreteNode item) {
                        api.BaseRegistry hub;
                        hub = new ChildRegistry();
                        hub.register(item);
                        return hub != null;
                    }
                }
                """);
        myFixture.addClass("package game; public class ChildRegistry extends api.BaseRegistry {}");
        assertNoErrors(file);
        PsiLocalVariable local = PsiTreeUtil.findChildOfType(file, PsiLocalVariable.class);
        assertNotNull(local);
        assertFalse(local.getTypeElement().isInferredType());
        assertEquals("api.BaseRegistry<game.ConcreteNode,game.ConcreteKind>", local.getType().getCanonicalText());
    }

    public void testClassNamingAliasesKeepsVirtualAliases() {
        PsiFile file = myFixture.addFileToProject("game/Template.java", """
                package game;
                class Template extends api.BaseNode {
                    J_B current() { return getKind(); }
                }
                """);
        assertNoErrors(file);
        PsiMethodCallExpression call = PsiTreeUtil.findChildOfType(file, PsiMethodCallExpression.class);
        assertEquals("game.J_B", call.getType().getCanonicalText());
    }

    private void assertNoErrors(PsiFile file) {
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        var errors = myFixture.doHighlighting(HighlightSeverity.ERROR);
        assertEmpty(errors.stream().map(info -> info.getDescription()).toList());
    }
}
