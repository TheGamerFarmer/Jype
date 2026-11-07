package fr.thegamerfarmer.jype.plugin;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.*;

/** Source import precedence and bound stability during editor changes. */
public class JypeTypeBoundsTest extends JypeJavaFixture {
    public void testEditingTypeNameKeepsImportedBoundResolvedDuringHighlighting() {
        myFixture.addClass("package managed.types; public class BoundClass {}");
        PsiFile definition = myFixture.addFileToProject("managed/package-info.java", """
                @TypeSystem({@TypeDef(name="J_TYPE", bound=BoundClass.class)})
                package managed;
                import fr.thegamerfarmer.jype.annotations.TypeSystem;
                import fr.thegamerfarmer.jype.annotations.TypeDef;
                import managed.types.BoundClass;
                """);
        myFixture.configureFromExistingVirtualFile(definition.getVirtualFile());
        myFixture.checkHighlighting(false, false, false);
        var document = myFixture.getEditor().getDocument();
        for (String name : java.util.List.of("J_X", "J_TYPE", "Type2")) {
            WriteCommandAction.runWriteCommandAction(getProject(), () -> {
                int start = document.getText().indexOf("name=\"") + 6;
                int end = document.getText().indexOf('"', start);
                document.replaceString(start, end, name);
                PsiDocumentManager.getInstance(getProject()).commitDocument(document);
            });
            PsiClassObjectAccessExpression bound = com.intellij.psi.util.PsiTreeUtil.findChildOfType(
                    definition, PsiClassObjectAccessExpression.class);
            assertNotNull(bound);
            assertNotNull(((PsiClassType) bound.getOperand().getType()).resolve());
            myFixture.checkHighlighting(false, false, false);
            var system = JypeTypeSystemReader.findTypeSystem(getProject(), "managed", definition.getResolveScope());
            assertNotNull(system);
            assertNull(system.error());
            assertEquals(name, system.typeDefs().get(0).name());
        }
    }

    public void testBoundResolutionHonorsStaticImportsAndRejectsAmbiguousWildcards() {
        myFixture.addClass("package first; public class Outer { public static class Nested {} }");
        myFixture.addClass("package second; public class Nested {}");
        myFixture.addFileToProject("managed/package-info.java", """
                @fr.thegamerfarmer.jype.annotations.TypeSystem({
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name="TYPE", bound=Nested.class)
                })
                package managed;
                import static first.Outer.Nested;
                """);
        var resolved = JypeTypeSystemReader.findTypeSystem(getProject(), "managed", scope());
        assertNotNull(resolved);
        assertNull(resolved.error());
        assertEquals("first.Outer.Nested", resolved.typeDefs().get(0).boundFqn());
        myFixture.addFileToProject("ambiguous/package-info.java", """
                @fr.thegamerfarmer.jype.annotations.TypeSystem({
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name="TYPE", bound=Nested.class)
                })
                package ambiguous;
                import static first.Outer.*;
                import second.*;
                """);
        var ambiguous = JypeTypeSystemReader.findTypeSystem(getProject(), "ambiguous", scope());
        assertNotNull(ambiguous);
        assertEquals("Cannot resolve @TypeDef bound for TYPE", ambiguous.error());
    }

    public void testConstantNamesAndNestedWildcardBounds() {
        myFixture.addClass("package bounds; public class Outer { public static class Nested {} }");
        myFixture.addClass("package names; public class Names { public static final String NAME = \"Item2\"; }");
        myFixture.addFileToProject("managed/package-info.java", """
                @fr.thegamerfarmer.jype.annotations.TypeSystem({
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name=names.Names.NAME, bound=Outer.Nested.class)
                })
                package managed;
                import bounds.*;
                """);
        PsiClass cls = myFixture.addClass("package managed; public class Consumer {}");
        assertEquals("bounds.Outer.Nested", JypeTypeSystemReader.getTypeDefMap(cls).get("Item2").boundFqn());
    }
}
