package fr.thegamerfarmer.jype.plugin;

import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;

/** Library member substitution must work, not merely hide Java warnings. */
public class JypeContextualTypesTest extends JypeJavaFixture {
    public void testRawServiceKeepsManagedReturnAndOrdinaryArguments() {
        objectSystem("api", "PLAYER");
        define("game", "inherit=\"api\"");
        myFixture.addClass("""
                package api;
                public class Service<PLAYER> {
                    public PLAYER player() { return null; }
                    public void accept(PLAYER player) {}
                    public java.util.List<PLAYER> players() { return null; }
                }
                """);
        PsiJavaFile file = (PsiJavaFile) myFixture.addFileToProject("game/Consumer.java", """
                package game;
                class Consumer {
                    api.Service service;
                    PLAYER player() { service.accept(service.player()); return service.player(); }
                    java.util.List<PLAYER> players() { return service.players(); }
                }
                """);
        PsiClass cls = file.getClasses()[0];
        PsiClassType type = (PsiClassType) cls.getFields()[0].getType();
        assertEquals("Service", type.getPresentableText());
        assertEquals("api.Service<game.PLAYER>", type.getCanonicalText());
        PsiMethodCallExpression call = PsiTreeUtil.findChildrenOfType(file, PsiMethodCallExpression.class)
                .stream().filter(item -> item.getMethodExpression().getReferenceName().equals("player")).findFirst().orElseThrow();
        assertEquals("PLAYER", call.getType().getPresentableText());
        assertEquals("game.PLAYER", call.getType().getCanonicalText());
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        myFixture.checkHighlighting(false, false, false);
    }

    public void testOrdinaryArgumentIsPreservedAndNotInvented() {
        objectSystem("api", "PLAYER");
        define("game", "inherit=\"api\"");
        myFixture.addClass("package api; public class Box<T, PLAYER> { public T value() { return null; } }");
        PsiClass cls = myFixture.addClass("""
                package game;
                class Consumer { api.Box<String> box; api.Box ordinaryRaw; }
                """);
        assertEquals("Box<String>", cls.getFields()[0].getType().getPresentableText());
        assertEquals("api.Box<java.lang.String,game.PLAYER>", cls.getFields()[0].getType().getCanonicalText());
        assertTrue(((PsiClassType) cls.getFields()[1].getType()).isRaw());
        myFixture.configureFromExistingVirtualFile(cls.getContainingFile().getVirtualFile());
        myFixture.checkHighlighting(false, false, false);
    }

    public void testNoContextDoesNotCompleteOrdinaryJava() {
        myFixture.addClass("package api; public class Service<PLAYER> {}");
        PsiClass cls = myFixture.addClass("package plain; class Consumer { api.Service service; }");
        assertTrue(((PsiClassType) cls.getFields()[0].getType()).isRaw());
    }

    public void testInheritedMethodIsReallySubstituted() {
        objectSystem("api", "PLAYER");
        define("game", "inherit=\"api\"");
        myFixture.addClass("package api; public class Service<PLAYER> { public PLAYER player() { return null; } }");
        PsiJavaFile file = (PsiJavaFile) myFixture.addFileToProject("game/Consumer.java", """
                package game;
                class Consumer extends api.Service {
                    PLAYER value() { return player(); }
                }
                """);
        PsiMethodCallExpression call = PsiTreeUtil.findChildOfType(file, PsiMethodCallExpression.class);
        assertEquals("game.PLAYER", call.getType().getCanonicalText());
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        myFixture.enableInspections(new com.intellij.codeInspection.miscGenerics.RawUseOfParameterizedTypeInspection(),
                new com.intellij.codeInspection.uncheckedWarnings.UncheckedWarningLocalInspection());
        myFixture.checkHighlighting(true, false, false);
    }

    public void testInheritedOrdinaryMethodGenericsAndUserOverrideRemainValid() {
        objectSystem("api", "PLAYER");
        define("game", "inherit=\"api\"");
        myFixture.addClass("""
                package api;
                public class Service<PLAYER> {
                    public <T> T identity(T value) { return value; }
                    public <PLAYER extends Number> PLAYER shadow(PLAYER value) { return value; }
                    public PLAYER player() { return null; }
                }
                """);
        PsiFile file = myFixture.addFileToProject("game/Consumer.java", """
                package game;
                class Consumer extends api.Service {
                    api.Service service;
                    @Override public PLAYER player() { return null; }
                    String value() { return service.identity("value"); }
                    Integer count() { return service.shadow(1); }
                }
                """);
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        myFixture.checkHighlighting(false, false, false);
        var calls = PsiTreeUtil.findChildrenOfType(file, PsiMethodCallExpression.class);
        for (var call : calls) {
            assertEquals(call.getMethodExpression().getReferenceName().equals("identity")
                    ? "java.lang.String" : "java.lang.Integer", call.getType().getCanonicalText());
        }
    }

    public void testInvalidManagedBoundIsNotHidden() {
        objectSystem("api", "PLAYER");
        define("game", "inherit=\"api\"");
        myFixture.addClass("package api; public class Service<PLAYER extends Number> {}");
        myFixture.configureByText("Consumer.java", "package game; class Consumer { api.Service service; }");
        var errors = myFixture.doHighlighting(com.intellij.lang.annotation.HighlightSeverity.ERROR);
        assertTrue(errors.toString(), errors.stream().anyMatch(info -> info.getDescription() != null
                && info.getDescription().contains("[Jype] Type argument PLAYER does not satisfy bound Number")));
        PsiJavaCodeReferenceElement reference = PsiTreeUtil.findChildrenOfType(myFixture.getFile(), PsiJavaCodeReferenceElement.class)
                .stream().filter(ref -> "Service".equals(ref.getReferenceName())).findFirst().orElseThrow();
        assertFalse(new JypeRawTypeInspectionSuppressor().isSuppressedFor(reference, "rawtypes"));
        assertFalse(new JypeRawTypeInspectionSuppressor().isSuppressedFor(reference, "unchecked"));
    }

    public void testInheritedFieldsKeepTheirTypesVisibilityAndNavigation() {
        objectSystem("api", "PLAYER");
        define("game", "inherit=\"api\"");
        PsiClass original = myFixture.addClass("""
                package api;
                public class Service<PLAYER> {
                    public PLAYER player;
                    protected java.util.List<PLAYER> players;
                    private PLAYER hidden;
                }
                """);
        PsiFile file = myFixture.addFileToProject("game/Consumer.java", """
                package game;
                class Consumer extends api.Service {
                    PLAYER value() { return player; }
                    java.util.List<PLAYER> values() { return players; }
                }
                """);
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        myFixture.checkHighlighting(false, false, false);
        PsiClass owner = ((PsiJavaFile) file).getClasses()[0];
        var fields = JypeInheritedMembers.fields(owner, "player");
        assertEquals(1, fields.size());
        assertEquals("game.PLAYER", fields.get(0).getType().getCanonicalText());
        assertSame(original.getFields()[0], fields.get(0).getNavigationElement());
        assertEmpty(JypeInheritedMembers.fields(owner, "hidden"));
    }
}
