package fr.thegamerfarmer.jype.plugin;

import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;

/** Source class-literal lookup follows Java package, explicit import and wildcard precedence. */
public class JypeSourceBoundResolverTest extends JypeJavaFixture {
    public void testSamePackageBoundTakesPrecedenceOverWildcardImports() {
        myFixture.addClass("package local; public class Bound {}");
        myFixture.addClass("package imported; public class Bound {}");
        assertBound("local", "Bound", "import imported.*;", "local.Bound");
    }

    public void testExplicitNestedImportTakesPrecedenceOverPackageAndOnDemandCandidates() {
        myFixture.addClass("package imported; public class Outer { public static class Nested {} }");
        myFixture.addClass("package local; public class Nested {}");
        assertBound("local", "Nested", "import imported.Outer.Nested; import java.util.*;",
                "imported.Outer.Nested");
        assertBound("qualified", "imported.Outer.Nested", "", "imported.Outer.Nested");
    }

    public void testJavaLangAndWildcardBoundsRejectAmbiguityAndMissingExplicitImports() {
        assertBound("language", "String", "import java.util.List;", "java.lang.String");
        assertBound("wildcard", "List", "import java.util.*;", "java.util.List");
        myFixture.addClass("package competing; public class String {}");
        assertBound("ambiguous", "String", "import competing.*;", null);
        assertBound("missing", "Absent", "import unavailable.Absent;", null);
    }

    private void assertBound(String pkg, String bound, String imports, String expected) {
        PsiFile file = myFixture.addFileToProject(pkg + "/package-info.java",
                "@fr.thegamerfarmer.jype.annotations.TypeSystem("
                        + "@fr.thegamerfarmer.jype.annotations.TypeDef(name=\"Item\", bound=" + bound + ".class))"
                        + " package " + pkg + "; " + imports);
        PsiClassObjectAccessExpression literal = PsiTreeUtil.findChildOfType(file, PsiClassObjectAccessExpression.class);
        assertNotNull(literal);
        PsiClass resolved = JypeSourceBoundResolver.resolve(literal);
        assertEquals(pkg, expected, resolved == null ? null : resolved.getQualifiedName());
    }
}
