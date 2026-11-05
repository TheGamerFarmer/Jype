package fr.thegamerfarmer.jype.plugin;

import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;

import java.util.List;

/** Resolves inheritance only from valid, unique declarations inside the supplied visibility boundary. */
public class JypeTypeSystemResolverTest extends JypeJavaFixture {
    public void testInvalidOverrideSyntaxAndIncompatibleAttributesDoNotPublishPartialDefinitions() {
        assertError("badOverride", "overrides={42}", "Expected a Jype @TypeDef");
        assertError("noParent", """
                overrides=@fr.thegamerfarmer.jype.annotations.TypeDef(name="Item", bound=Object.class)
                """, "Overrides require inherit");
        assertError("combined", """
                value=@fr.thegamerfarmer.jype.annotations.TypeDef(name="Item", bound=Object.class),
                inherit="parent"
                """, "Do not combine value and inherit");
        PsiJavaFile empty = define("emptyParent", "inherit=\"\"");
        var result = resolver(annotation(empty)).resolve(annotation(empty), "emptyParent");
        assertNull(result.error());
        assertTrue(result.typeDefs().isEmpty());
    }

    public void testDuplicateInheritedPackagesAreRejectedRatherThanSelectingOne() {
        PsiJavaFile first = define("parent", "{}");
        PsiJavaFile second = (PsiJavaFile) myFixture.addFileToProject("other-root/package-info.java",
                "@fr.thegamerfarmer.jype.annotations.TypeSystem package parent;");
        PsiJavaFile child = define("child", "inherit=\"parent\"");
        var result = resolver(annotation(first), annotation(second), annotation(child))
                .resolve(annotation(child), "child");
        assertEquals("Multiple @TypeSystem declarations in resolve scope: parent", result.error());
        assertTrue(result.typeDefs().isEmpty());
    }

    public void testInheritedDeclarationsMustBeCanonicalAndInsideResolveScope() {
        PsiJavaFile parent = define("parent", "{}");
        PsiJavaFile child = define("child", "inherit=\"parent\"");
        GlobalSearchScope excluded = scope().intersectWith(GlobalSearchScope.notScope(GlobalSearchScope.fileScope(parent)));
        var result = new JypeTypeSystemResolver(List.of(annotation(parent), annotation(child)), excluded)
                .resolve(annotation(child), "child");
        assertEquals("Cannot find inherited @TypeSystem: parent", result.error());
        PsiJavaFile foreign = (PsiJavaFile) myFixture.addFileToProject("foreign/package-info.java",
                "@Deprecated package parent;");
        result = resolver(annotation(foreign), annotation(child)).resolve(annotation(child), "child");
        assertEquals("Cannot find inherited @TypeSystem: parent", result.error());
    }

    public void testInvalidInheritedDefinitionsPropagateAndGuardIsReleasedAfterFailure() {
        PsiJavaFile parent = define("parent", "{@fr.thegamerfarmer.jype.annotations.TypeDef(name=\"class\", bound=Object.class)}");
        PsiJavaFile child = define("child", "inherit=\"parent\"");
        var resolver = resolver(annotation(parent), annotation(child));
        for (int attempt = 0; attempt < 2; attempt++) {
            var result = resolver.resolve(annotation(child), "child");
            assertEquals("@TypeDef name must be a Java identifier", result.error());
            assertTrue(result.typeDefs().isEmpty());
        }
    }

    private void assertError(String pkg, String attributes, String expected) {
        PsiAnnotation annotation = annotation(define(pkg, attributes));
        var result = resolver(annotation).resolve(annotation, pkg);
        assertEquals(expected, result.error());
        assertTrue(result.typeDefs().isEmpty());
    }

    private JypeTypeSystemResolver resolver(PsiAnnotation... annotations) {
        return new JypeTypeSystemResolver(List.of(annotations), scope());
    }

    private PsiAnnotation annotation(PsiJavaFile file) {
        return file.getPackageStatement().getAnnotationList().getAnnotations()[0];
    }
}
