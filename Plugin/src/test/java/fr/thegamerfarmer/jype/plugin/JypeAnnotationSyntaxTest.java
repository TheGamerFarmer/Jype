package fr.thegamerfarmer.jype.plugin;

import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.DumbModeTestUtils;

/** Lexical annotation identity and invalid attribute syntax use actual, potentially incomplete PSI. */
public class JypeAnnotationSyntaxTest extends JypeJavaFixture {
    public void testWildcardIdentityRespectsExplicitImportsAndLocalShadowing() {
        myFixture.addClass("package other; public @interface TypeSystem {}");
        assertIdentity("wildcard", "import fr.thegamerfarmer.jype.annotations.*;", true);
        assertIdentity("missing", "import java.util.*;", false);
        assertIdentity("ambiguous",
                "import fr.thegamerfarmer.jype.annotations.*; import other.*;", false);
        assertIdentity("explicit",
                "import fr.thegamerfarmer.jype.annotations.*; import other.TypeSystem;", false);
        assertIdentity("precedence",
                "import fr.thegamerfarmer.jype.annotations.TypeSystem; import other.*;", true);
        myFixture.addClass("package shadow; public @interface TypeSystem {}");
        assertIdentity("shadow", "import fr.thegamerfarmer.jype.annotations.*;", false);
        assertIdentity("fr.thegamerfarmer.jype.annotations", "", true);
    }

    public void testUnrelatedSimpleAnnotationAndDumbWildcardAreNotMistakenForJype() {
        PsiAnnotation unrelated = packageAnnotation("unrelated", "@Deprecated", "");
        assertFalse(JypeAnnotationSyntax.hasAnnotationName(unrelated, JypeAnnotationSyntax.SYSTEM));
        PsiAnnotation wildcard = packageAnnotation("indexing", "@TypeSystem",
                "import fr.thegamerfarmer.jype.annotations.*;");
        DumbModeTestUtils.runInDumbModeSynchronously(getProject(),
                () -> assertFalse(JypeAnnotationSyntax.hasAnnotationName(wildcard, JypeAnnotationSyntax.SYSTEM)));
        assertTrue(JypeAnnotationSyntax.hasAnnotationName(wildcard, JypeAnnotationSyntax.SYSTEM));
    }

    public void testMalformedDefinitionNamesAndNonClassBoundsAreRejected() {
        assertDefinitionError("keyword", "name=\"class\", bound=Object.class", "identifier");
        assertDefinitionError("number", "name=42, bound=Object.class", "identifier");
        assertDefinitionError("missingname", "bound=Object.class", "identifier");
        assertDefinitionError("primitive", "name=\"Item\", bound=int.class", "class or interface");
        assertDefinitionError("array", "name=\"Item\", bound=Object[].class", "class or interface");
        assertDefinitionError("expression", "name=\"Item\", bound=\"java.lang.Object\"", "class or interface");
    }

    public void testNonDefinitionArrayEntriesAndNonStringConstantsAreRejected() {
        PsiJavaFile foreign = define("foreignentry", "{@Deprecated}");
        assertEquals("Expected a Jype @TypeDef", definitions(foreign).error());
        PsiJavaFile literal = define("literalentry", "{42}");
        assertEquals("Expected a Jype @TypeDef", definitions(literal).error());
        myFixture.addClass("package values; public class Constants { public static final int VALUE = 7; }");
        assertDefinitionError("constantnumber", "name=values.Constants.VALUE, bound=Object.class", "identifier");
        assertFalse("Constant evaluation must release its discovery guard", JypeAnnotationSyntax.isEvaluatingConstant());
        myFixture.addClass("package values; public class Names { public static final String VALUE = \"Item\"; }");
        PsiJavaFile valid = define("constantstring",
                "{@fr.thegamerfarmer.jype.annotations.TypeDef(name=values.Names.VALUE, bound=Object.class)}");
        assertNull(definitions(valid).error());
        assertFalse(JypeAnnotationSyntax.isEvaluatingConstant());
        assertNotNull(new JypePsiElementFinder(getProject()).findClass("constantstring.Item", scope()));
    }

    private void assertIdentity(String pkg, String imports, boolean expected) {
        PsiAnnotation annotation = packageAnnotation(pkg, "@TypeSystem", imports);
        assertEquals(pkg, expected, JypeAnnotationSyntax.hasAnnotationName(annotation, JypeAnnotationSyntax.SYSTEM));
    }

    private PsiAnnotation packageAnnotation(String pkg, String annotation, String imports) {
        PsiJavaFile file = (PsiJavaFile) myFixture.addFileToProject(
                pkg.replace('.', '/') + "/package-info.java", annotation + " package " + pkg + "; " + imports);
        return file.getPackageStatement().getAnnotationList().getAnnotations()[0];
    }

    private void assertDefinitionError(String pkg, String attributes, String expected) {
        var result = definitions(define(pkg, "{@fr.thegamerfarmer.jype.annotations.TypeDef(" + attributes + ")}"));
        assertNotNull(pkg, result.error());
        assertTrue(result.error(), result.error().contains(expected));
        assertTrue(result.values().isEmpty());
    }

    private JypeAnnotationSyntax.Definitions definitions(PsiJavaFile file) {
        PsiAnnotation annotation = PsiTreeUtil.findChildOfType(file, PsiAnnotation.class);
        assertNotNull(annotation);
        return JypeAnnotationSyntax.readDefinitions(annotation.findDeclaredAttributeValue("value"));
    }
}
