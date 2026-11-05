package fr.thegamerfarmer.jype.plugin;

import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.psi.*;

/** Bound-safe alias casts without suppressing real Java data-flow failures. */
public class JypeCastInspectionTest extends JypeJavaFixture {
    public void testConcreteAliasCastInPlayerConstructorLambda() {
        myFixture.addClass("package bounds; public class APIPlayer {}");
        define("managed",
                "{@fr.thegamerfarmer.jype.annotations.TypeDef(name=\"API_PLAYER\", bound=bounds.APIPlayer.class)}");
        myFixture.configureByText("APIUHC.java", """
                package managed;
                import bounds.APIPlayer;
                class APIUHC {
                    static class PlayerManager {
                        void setPlayerConstructor(java.util.function.Supplier<API_PLAYER> constructor) {}
                    }
                    void activeAPIUHC(PlayerManager manager) {
                        manager.setPlayerConstructor(() -> (API_PLAYER) new APIPlayer());
                    }
                }
                """);
        myFixture.enableInspections(new com.intellij.codeInspection.dataFlow.DataFlowInspection());
        var cast = com.intellij.psi.util.PsiTreeUtil.findChildOfType(myFixture.getFile(), PsiTypeCastExpression.class);
        assertNotNull(cast);
        var suppressor = new JypeCastInspectionSuppressor();
        assertTrue(suppressor.isSuppressedFor(cast.getCastType(), "DataFlowIssue"));
        assertFalse(suppressor.isSuppressedFor(cast.getCastType(), "RedundantCast"));
        assertFalse(suppressor.isSuppressedFor(cast.getOperand(), "DataFlowIssue"));
        myFixture.checkHighlighting(true, false, false);
    }

    public void testDangerousCastsAndNullDereferencesAreStillReported() {
        myFixture.addClass("package bounds; public class APIPlayer {}");
        myFixture.addClass("package bounds; public class Child extends APIPlayer {}");
        define("managed",
                "{@fr.thegamerfarmer.jype.annotations.TypeDef(name=\"API_PLAYER\", bound=bounds.Child.class)}");
        myFixture.configureByText("Consumer.java", """
                package managed;
                import bounds.APIPlayer;
                class Consumer {
                    Object alias() { return (API_PLAYER) new APIPlayer(); }
                    Object real() { return (bounds.Child) new APIPlayer(); }
                    <API_PLAYER extends APIPlayer> Object parameter() {
                        return (API_PLAYER) new APIPlayer();
                    }
                    int nullValue() {
                        API_PLAYER player = null;
                        return player.hashCode();
                    }
                }
                """);
        myFixture.enableInspections(new com.intellij.codeInspection.dataFlow.DataFlowInspection());
        var suppressor = new JypeCastInspectionSuppressor();
        for (var cast : com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(myFixture.getFile(), PsiTypeCastExpression.class)) {
            assertFalse(cast.getText(), suppressor.isSuppressedFor(cast.getCastType(), "DataFlowIssue"));
        }
        var warnings = myFixture.doHighlighting(HighlightSeverity.WARNING);
        long casts = warnings.stream().filter(info -> info.getDescription() != null
                && info.getDescription().contains("ClassCastException")).count();
        assertEquals(warnings.toString(), 2L, casts);
        assertTrue(warnings.toString(), warnings.stream().anyMatch(info -> info.getDescription() != null
                && info.getDescription().contains("NullPointerException")));
    }

    public void testAliasCastsRespectOverridesAndUnknownOperandTypes() {
        myFixture.addClass("package bounds; public class Parent {}");
        myFixture.addClass("package bounds; public class Child extends Parent {}");
        define("library",
                "{@fr.thegamerfarmer.jype.annotations.TypeDef(name=\"TYPE\", bound=bounds.Parent.class)}");
        define("consumer", """
                inherit="library",
                overrides={@fr.thegamerfarmer.jype.annotations.TypeDef(name="TYPE", bound=bounds.Child.class)}
                """);
        PsiFile file = myFixture.addFileToProject("consumer/deep/Consumer.java", """
                package consumer.deep;
                class Consumer {
                    Object safe() { return (consumer.TYPE) new bounds.Child(); }
                    Object unsafe() { return (TYPE) new bounds.Parent(); }
                    Object unknown(Object value) { return (TYPE) value; }
                }
                """);
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        var casts = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(myFixture.getFile(), PsiTypeCastExpression.class)
                .stream().toList();
        assertEquals(3, casts.size());
        var suppressor = new JypeCastInspectionSuppressor();
        assertTrue(suppressor.isSuppressedFor(casts.get(0).getCastType(), "DataFlowIssue"));
        assertFalse(suppressor.isSuppressedFor(casts.get(1).getCastType(), "DataFlowIssue"));
        assertFalse(suppressor.isSuppressedFor(casts.get(2).getCastType(), "DataFlowIssue"));
        myFixture.enableInspections(new com.intellij.codeInspection.dataFlow.DataFlowInspection());
        var warnings = myFixture.doHighlighting(HighlightSeverity.WARNING);
        assertEquals(warnings.toString(), 1L, warnings.stream().filter(info -> info.getDescription() != null
                && info.getDescription().contains("ClassCastException")).count());
    }

    public void testInterfaceAliasCastsAndUnrelatedSameNamedClass() {
        myFixture.addClass("package bounds; public interface Contract {}");
        myFixture.addClass("package bounds; public class Implementation implements Contract {}");
        myFixture.addClass("package plain; public class TYPE extends bounds.Implementation {}");
        define("managed",
                "{@fr.thegamerfarmer.jype.annotations.TypeDef(name=\"TYPE\", bound=bounds.Contract.class)}");
        myFixture.configureByText("Consumer.java", """
                package managed;
                class Consumer {
                    Object alias() { return (TYPE) new bounds.Implementation(); }
                    Object real() { return (plain.TYPE) new bounds.Implementation(); }
                }
                """);
        var casts = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(myFixture.getFile(), PsiTypeCastExpression.class)
                .stream().toList();
        assertEquals(2, casts.size());
        var suppressor = new JypeCastInspectionSuppressor();
        assertTrue(suppressor.isSuppressedFor(casts.get(0).getCastType(), "DataFlowIssue"));
        assertFalse(suppressor.isSuppressedFor(casts.get(1).getCastType(), "DataFlowIssue"));
        myFixture.enableInspections(new com.intellij.codeInspection.dataFlow.DataFlowInspection());
        var warnings = myFixture.doHighlighting(HighlightSeverity.WARNING);
        assertEquals(warnings.toString(), 1L, warnings.stream().filter(info -> info.getDescription() != null
                && info.getDescription().contains("ClassCastException")).count());
    }
}
