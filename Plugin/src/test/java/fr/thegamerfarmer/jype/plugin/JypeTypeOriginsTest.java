package fr.thegamerfarmer.jype.plugin;

import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;

/** Only package-level Jype TypeDef names form rename origins; ordinary string literals do not. */
public class JypeTypeOriginsTest extends JypeJavaFixture {
    public void testFileRecognitionRejectsNonJavaMissingPackageAndUnannotatedPackages() {
        assertFalse(JypeTypeOrigins.isTypeSystemFile(myFixture.addFileToProject("notes.txt", "package plain;")));
        assertFalse(JypeTypeOrigins.isTypeSystemFile(myFixture.addFileToProject("empty/package-info.java", "")));
        assertFalse(JypeTypeOrigins.isTypeSystemFile(myFixture.addFileToProject("plain/package-info.java", "package plain;")));
        assertFalse(JypeTypeOrigins.isTypeSystemFile(myFixture.addClass("class Ordinary {}").getContainingFile()));
    }

    public void testNameLiteralMustBeStringInsideJypeDefinitionAndSystem() {
        PsiFile ordinary = myFixture.addFileToProject("ordinary/Consumer.java",
                "package ordinary; class Consumer { String name = \"Item\"; }");
        assertRejected(ordinary);
        assertRejected(define("parentname", "inherit=\"Item\""));
        assertRejected(define("nonstring",
                "{@fr.thegamerfarmer.jype.annotations.TypeDef(name=42, bound=Object.class)}"));
        assertRejected(myFixture.addFileToProject("foreign/package-info.java",
                "@Deprecated(since=\"Item\") package foreign;"));
        assertRejected(myFixture.addFileToProject("standalone/package-info.java", """
                @fr.thegamerfarmer.jype.annotations.TypeSystem
                @fr.thegamerfarmer.jype.annotations.TypeDef(name="Item", bound=Object.class)
                package standalone;
                """));
        assertRejected(myFixture.addFileToProject("ordinary/Annotated.java", """
                package ordinary;
                @fr.thegamerfarmer.jype.annotations.TypeSystem(
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name="Item", bound=Object.class))
                class Annotated {}
                """));
    }

    public void testForeignNestedAnnotationWithNameCannotBecomeAliasOrigin() {
        myFixture.addClass("package foreign; public @interface Definition { String name(); }");
        PsiFile file = define("foreignnested", "{@foreign.Definition(name=\"Item\")}");
        assertRejected(file);
    }

    public void testMissingOriginsAndUnmatchedNamesReturnNullWithoutInventingDeclarations() {
        PsiClass real = myFixture.addClass("package managed; public class Missing {}");
        assertNull(JypeTypeSystemReader.renameOrigin(real));
        objectSystem("managed", "Item");
        var system = JypeTypeSystemReader.findTypeSystem(getProject(), "managed", scope());
        assertNotNull(system);
        assertNull(JypeTypeOrigins.renameOrigin(real, system));
        PsiClass alias = new JypePsiElementFinder(getProject()).findClass("managed.Item", scope());
        assertNotNull(alias);
        assertEquals("Item", ((PsiLiteralExpression) JypeTypeSystemReader.renameOrigin(alias)).getValue());
    }

    private void assertRejected(PsiFile file) {
        var literals = PsiTreeUtil.findChildrenOfType(file, PsiLiteralExpression.class);
        assertFalse("Fixture must contain a literal: " + file.getText(), literals.isEmpty());
        for (PsiLiteralExpression literal : literals) {
            assertFalse(file.getText(), JypeTypeOrigins.isTypeNameLiteral(literal));
            assertNull(JypeTypeSystemReader.typeDeclaredBy(literal));
        }
    }
}
