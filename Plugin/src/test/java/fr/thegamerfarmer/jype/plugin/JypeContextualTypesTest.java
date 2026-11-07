package fr.thegamerfarmer.jype.plugin;

import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;

/** Library member substitution must work, not merely hide Java warnings. */
public class JypeContextualTypesTest extends JypeJavaFixture {
    public void testRawServiceKeepsManagedReturnAndOrdinaryArguments() {
        objectSystem("api", "ENTITY");
        define("game", "inherit=\"api\"");
        myFixture.addClass("""
                package api;
                public class Service<ENTITY> {
                    public ENTITY entity() { return null; }
                    public void accept(ENTITY value) {}
                    public java.util.List<ENTITY> entities() { return null; }
                }
                """);
        PsiJavaFile file = (PsiJavaFile) myFixture.addFileToProject("game/Consumer.java", """
                package game;
                class Consumer {
                    api.Service service;
                    ENTITY entity() { service.accept(service.entity()); return service.entity(); }
                    java.util.List<ENTITY> entities() { return service.entities(); }
                }
                """);
        PsiClass cls = file.getClasses()[0];
        PsiClassType type = (PsiClassType) cls.getFields()[0].getType();
        assertEquals("Service", type.getPresentableText());
        assertEquals("api.Service<game.ENTITY>", type.getCanonicalText());
        PsiMethodCallExpression call = PsiTreeUtil.findChildrenOfType(file, PsiMethodCallExpression.class)
                .stream().filter(item -> item.getMethodExpression().getReferenceName().equals("entity")).findFirst().orElseThrow();
        assertEquals("ENTITY", call.getType().getPresentableText());
        assertEquals("game.ENTITY", call.getType().getCanonicalText());
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        myFixture.checkHighlighting(false, false, false);
    }

    public void testOrdinaryArgumentIsPreservedAndNotInvented() {
        objectSystem("api", "ENTITY");
        define("game", "inherit=\"api\"");
        myFixture.addClass("package api; public class Box<T, ENTITY> { public T value() { return null; } }");
        PsiClass cls = myFixture.addClass("""
                package game;
                class Consumer { api.Box<String> box; api.Box ordinaryRaw; }
                """);
        assertEquals("Box<String>", cls.getFields()[0].getType().getPresentableText());
        // As in the engine, a parameterless binding (here Object) is used directly by a class naming no alias.
        assertEquals("api.Box<java.lang.String,java.lang.Object>", cls.getFields()[0].getType().getCanonicalText());
        assertTrue(((PsiClassType) cls.getFields()[1].getType()).isRaw());
        myFixture.configureFromExistingVirtualFile(cls.getContainingFile().getVirtualFile());
        myFixture.checkHighlighting(false, false, false);
    }

    public void testNoContextDoesNotCompleteOrdinaryJava() {
        myFixture.addClass("package api; public class Service<ENTITY> {}");
        PsiClass cls = myFixture.addClass("package plain; class Consumer { api.Service service; }");
        assertTrue(((PsiClassType) cls.getFields()[0].getType()).isRaw());
    }

    public void testRecursiveInferenceYieldsToPlatformResolution() {
        objectSystem("api", "ENTITY");
        define("game", "inherit=\"api\"");
        myFixture.addClass("package api; public class Service<ENTITY> {}");
        PsiJavaFile file = (PsiJavaFile) myFixture.addFileToProject("game/Consumer.java", """
                package game;
                class Consumer {
                    api.Service service;
                    api.Service method() { return null; }
                }
                """);
        PsiTypeElement typeElement = PsiTreeUtil.findChildrenOfType(file, PsiTypeElement.class).stream()
                .filter(element -> element.getInnermostComponentReferenceElement() != null
                        && "Service".equals(element.getInnermostComponentReferenceElement().getReferenceName()))
                .findFirst().orElseThrow();
        PsiTypeElement anotherTypeElement = PsiTreeUtil.findChildrenOfType(file, PsiTypeElement.class).stream()
                .filter(element -> element != typeElement
                        && element.getInnermostComponentReferenceElement() != null
                        && "Service".equals(element.getInnermostComponentReferenceElement().getReferenceName()))
                .findFirst().orElseThrow();
        assertNotNull(JypeContextualTypes.infer(typeElement));

        PsiType recursive = JypeContextualTypes.withInferenceGuard(
                () -> JypeContextualTypes.infer(anotherTypeElement));
        assertNull(recursive);
    }

    public void testInheritedMethodIsReallySubstituted() {
        objectSystem("api", "ENTITY");
        define("game", "inherit=\"api\"");
        myFixture.addClass("package api; public class Service<ENTITY> { public ENTITY entity() { return null; } }");
        PsiJavaFile file = (PsiJavaFile) myFixture.addFileToProject("game/Consumer.java", """
                package game;
                class Consumer extends api.Service {
                    ENTITY value() { return entity(); }
                }
                """);
        PsiMethodCallExpression call = PsiTreeUtil.findChildOfType(file, PsiMethodCallExpression.class);
        assertEquals("game.ENTITY", call.getType().getCanonicalText());
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        myFixture.enableInspections(new com.intellij.codeInspection.miscGenerics.RawUseOfParameterizedTypeInspection(),
                new com.intellij.codeInspection.uncheckedWarnings.UncheckedWarningLocalInspection());
        myFixture.checkHighlighting(true, false, false);
    }

    public void testInheritedOrdinaryMethodGenericsAndUserOverrideRemainValid() {
        objectSystem("api", "ENTITY");
        define("game", "inherit=\"api\"");
        myFixture.addClass("""
                package api;
                public class Service<ENTITY> {
                    public <T> T identity(T value) { return value; }
                    public <ENTITY extends Number> ENTITY shadow(ENTITY value) { return value; }
                    public ENTITY entity() { return null; }
                }
                """);
        PsiFile file = myFixture.addFileToProject("game/Consumer.java", """
                package game;
                class Consumer extends api.Service {
                    api.Service service;
                    @Override public ENTITY entity() { return null; }
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
        objectSystem("api", "ENTITY");
        define("game", "inherit=\"api\"");
        myFixture.addClass("package api; public class Service<ENTITY extends Number> {}");
        myFixture.configureByText("Consumer.java", "package game; class Consumer { api.Service service; }");
        var errors = myFixture.doHighlighting(com.intellij.lang.annotation.HighlightSeverity.ERROR);
        assertTrue(errors.toString(), errors.stream().anyMatch(info -> info.getDescription() != null
                && info.getDescription().contains("[Jype] Type argument Object does not satisfy bound Number")));
        PsiJavaCodeReferenceElement reference = PsiTreeUtil.findChildrenOfType(myFixture.getFile(), PsiJavaCodeReferenceElement.class)
                .stream().filter(ref -> "Service".equals(ref.getReferenceName())).findFirst().orElseThrow();
        assertFalse(new JypeRawTypeInspectionSuppressor().isSuppressedFor(reference, "rawtypes"));
        assertFalse(new JypeRawTypeInspectionSuppressor().isSuppressedFor(reference, "unchecked"));
    }

    public void testInheritedFieldsKeepTheirTypesVisibilityAndNavigation() {
        objectSystem("api", "ENTITY");
        define("game", "inherit=\"api\"");
        PsiClass original = myFixture.addClass("""
                package api;
                public class Service<ENTITY> {
                    public ENTITY entity;
                    protected java.util.List<ENTITY> entities;
                    private ENTITY hidden;
                }
                """);
        PsiFile file = myFixture.addFileToProject("game/Consumer.java", """
                package game;
                class Consumer extends api.Service {
                    ENTITY value() { return entity; }
                    java.util.List<ENTITY> values() { return entities; }
                }
                """);
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        myFixture.checkHighlighting(false, false, false);
        PsiClass owner = ((PsiJavaFile) file).getClasses()[0];
        var fields = JypeInheritedMembers.fields(owner, "entity");
        assertEquals(1, fields.size());
        assertEquals("game.ENTITY", fields.get(0).getType().getCanonicalText());
        assertSame(original.getFields()[0], fields.get(0).getNavigationElement());
        assertEmpty(JypeInheritedMembers.fields(owner, "hidden"));
    }
}
