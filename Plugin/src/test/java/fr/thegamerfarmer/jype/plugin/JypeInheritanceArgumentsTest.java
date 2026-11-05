package fr.thegamerfarmer.jype.plugin;

import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;

/** Real Java daemon diagnostics for partial generic inheritance and invalid arguments. */
public class JypeInheritanceArgumentsTest extends JypeJavaFixture {
    private void parent() {
        objectSystem("managed", "TYPE");
        myFixture.addClass("package api; public class Parent<T, TYPE> {}");
    }

    private void clean(String source) {
        myFixture.configureByText("Child.java", source);
        myFixture.checkHighlighting(false, false, false);
    }

    private void error(String source, String fragment) {
        myFixture.configureByText("Child.java", source);
        var errors = myFixture.doHighlighting(HighlightSeverity.ERROR);
        assertTrue(errors.toString(), errors.stream().anyMatch(info ->
                info.getDescription() != null && info.getDescription().contains(fragment)));
    }

    public void testImplicitManagedArgument() {
        parent();
        clean("package managed; class Child extends api.Parent<String> {}");
    }

    public void testSharedOrdinaryArgumentAndInjectedManagedArgument() {
        parent();
        clean("package managed; class Child<T> extends api.Parent<T> {}");
    }

    public void testSharedNamesAreInsertedInDeclarationOrder() {
        parent();
        myFixture.addClass("package api; public class Interleaved<TYPE, T, OTHER> {}");
        clean("package managed; class Child<TYPE, OTHER> extends api.Interleaved<String> {}");
    }

    public void testInterfacesAndExplicitManagedParameter() {
        parent();
        myFixture.addClass("package api; public interface Contract<T, TYPE> {}");
        clean("package managed; class Child<TYPE> implements api.Contract<String> {}");
    }

    public void testValidExplicitBoundAndImplicitOverride() {
        objectSystem("managed", "TYPE");
        myFixture.addClass("package api; public class Parent<T extends Number, TYPE> {}");
        clean("package managed; class Child extends api.Parent<Integer> {}");
    }

    public void testInvalidExplicitBoundIsReportedWithCompletedArguments() {
        objectSystem("managed", "TYPE");
        myFixture.addClass("package api; public class Parent<T extends Number, TYPE> {}");
        error("package managed; class Child extends api.Parent<String> {}", "[Jype] Type argument String");
    }

    public void testBoundUsesOtherCompletedParameter() {
        define("managed", "{@fr.thegamerfarmer.jype.annotations.TypeDef(name=\"TYPE\", bound=Number.class)}");
        myFixture.addClass("package api; public class Parent<T extends TYPE, TYPE> {}");
        clean("package managed; class Child extends api.Parent<Integer> {}");
        error("package managed; class Child extends api.Parent<String> {}", "does not satisfy bound Number");
    }

    public void testInvalidImplicitBoundIsReported() {
        parent();
        myFixture.addClass("package api; public class Numeric<T, TYPE extends Number> {}");
        error("package managed; class Child extends api.Numeric<String> {}", "does not satisfy bound Number");
    }

    public void testMissingOrdinaryAndExtraArgumentsStayErrors() {
        parent();
        myFixture.addClass("package api; public class More<T, U, TYPE> {}");
        error("package managed; class Child extends api.More<String> {}", "Wrong number of type arguments");
        error("package managed; class Child extends api.Parent<String, Object, Integer> {}",
                "Wrong number of type arguments");
    }

    public void testNoSystemAndInvalidSystemStayErrors() {
        parent();
        error("package ordinary; class Child extends api.Parent<String> {}", "Wrong number of type arguments");
        define("invalid", "{@fr.thegamerfarmer.jype.annotations.TypeDef(name=\"bad.name\", bound=Object.class)}");
        error("package invalid; class Child extends api.Parent<String> {}", "Wrong number of type arguments");
    }

    public void testCompleteAndRawTypesKeepNormalJavaChecks() {
        parent();
        clean("package managed; class Child extends api.Parent {}");
        clean("package managed; class Child extends api.Parent<String, Object> {}");
        myFixture.addClass("package api; public class Numeric<T extends Number, TYPE> {}");
        error("package managed; class Child extends api.Numeric<String, Object> {}", "not within its bound");
    }

    public void testPartialFieldTypesAreNotExempt() {
        parent();
        error("package managed; class Child { api.Parent<String> value; }", "Wrong number of type arguments");
    }

    public void testUnresolvedSuperclassAndIndexingAreNotCompleted() {
        parent();
        myFixture.configureByText("Child.java", "package managed; class Child extends api.Unknown<String> {}");
        PsiJavaCodeReferenceElement reference = PsiTreeUtil.findChildrenOfType(myFixture.getFile(),
                PsiJavaCodeReferenceElement.class).stream()
                .filter(ref -> "Unknown".equals(ref.getReferenceName())).findFirst().orElseThrow();
        assertNull(JypeInheritanceArguments.complete(reference));
        myFixture.configureByText("Child.java", "package managed; class Child extends api.Parent<String> {}");
        PsiJavaCodeReferenceElement parent = PsiTreeUtil.findChildrenOfType(myFixture.getFile(),
                PsiJavaCodeReferenceElement.class).stream()
                .filter(ref -> "Parent".equals(ref.getReferenceName())).findFirst().orElseThrow();
        com.intellij.testFramework.DumbModeTestUtils.runInDumbModeSynchronously(getProject(),
                () -> assertNull(JypeInheritanceArguments.complete(parent)));
    }
}
