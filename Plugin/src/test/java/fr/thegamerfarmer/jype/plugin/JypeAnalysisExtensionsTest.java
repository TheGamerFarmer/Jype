package fr.thegamerfarmer.jype.plugin;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.daemon.impl.HighlightInfoType;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiTypeCastExpression;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.DumbModeTestUtils;

/** Diagnostic adapters must preserve ordinary Java errors and unsafe casts. */
public class JypeAnalysisExtensionsTest extends JypeJavaFixture {
    public void testHighlightFilterPreservesNonJavaAndOrdinaryErrors() {
        JypeHighlightFilter filter = new JypeHighlightFilter();
        HighlightInfo unresolved = highlight("Cannot resolve symbol 'Missing'", 0, 1);
        assertTrue(filter.accept(unresolved, null));
        assertTrue(filter.accept(unresolved, myFixture.configureByText("plain.txt", "Missing")));
        PsiFile java = myFixture.configureByText("Example.java",
                "class Example { Missing field; }");
        int offset = java.getText().indexOf("Missing");
        assertTrue(filter.accept(highlight("Cannot resolve symbol 'Missing'", offset, offset + 7), java));
        assertTrue(filter.accept(highlight("Incompatible types", offset, offset + 7), java));
        assertTrue(filter.accept(highlight(null, offset, offset + 7), java));
        assertTrue(filter.accept(highlight("Cannot resolve symbol 'class'", 0, 5), java));
        DumbModeTestUtils.runInDumbModeSynchronously(getProject(),
                () -> assertTrue(filter.accept(unresolved, java)));
    }

    public void testQualifiedAndAlreadyResolvedReferencesAreNotHidden() {
        objectSystem("managed", "Item");
        PsiFile file = myFixture.configureByText("Example.java", """
                package managed;
                class Example { Item item; unknown.Missing missing; }
                """);
        JypeHighlightFilter filter = new JypeHighlightFilter();
        int known = file.getText().indexOf("Item");
        assertTrue(filter.accept(highlight("Cannot resolve symbol 'Item'", known, known + 4), file));
        int qualified = file.getText().indexOf("Missing");
        assertTrue(filter.accept(highlight("Cannot resolve symbol 'Missing'", qualified, qualified + 7), file));
    }

    public void testCastSuppressionIsRestrictedToItsInspectionAndTypeElement() {
        objectSystem("managed", "Item");
        PsiTypeCastExpression cast = cast("(Item) new Object()");
        JypeCastInspectionSuppressor suppressor = new JypeCastInspectionSuppressor();
        assertTrue(suppressor.isSuppressedFor(cast.getCastType(), "DataFlowIssue"));
        assertTrue(suppressor.isSuppressedFor(cast.getCastType(), "ConstantConditions"));
        assertFalse(suppressor.isSuppressedFor(cast.getCastType(), "CastConflictsWithInstanceof"));
        assertFalse(suppressor.isSuppressedFor(cast, "DataFlowIssue"));
        assertEmpty(suppressor.getSuppressActions(cast, "DataFlowIssue"));
        DumbModeTestUtils.runInDumbModeSynchronously(getProject(),
                () -> assertFalse(suppressor.isSuppressedFor(cast.getCastType(), "DataFlowIssue")));
    }

    public void testOrdinaryUnknownAndPrimitiveCastTargetsAreNeverSuppressed() {
        objectSystem("managed", "Item");
        JypeCastInspectionSuppressor suppressor = new JypeCastInspectionSuppressor();
        for (String expression : new String[]{
                "(String) new Object()", "(Missing) new Object()", "(int) 1",
                "(Item) unknown"
        }) {
            PsiTypeCastExpression cast = cast(expression);
            assertFalse(expression, suppressor.isSuppressedFor(cast.getCastType(), "DataFlowIssue"));
        }
    }

    public void testCastFromAnIncompatibleSourceIsNotSuppressed() {
        define("managed",
                "{@fr.thegamerfarmer.jype.annotations.TypeDef(name=\"Item\", bound=Number.class)}");
        PsiTypeCastExpression cast = cast("(Item) new String()");
        assertFalse(new JypeCastInspectionSuppressor()
                .isSuppressedFor(cast.getCastType(), "DataFlowIssue"));
    }

    private PsiTypeCastExpression cast(String expression) {
        PsiFile file = myFixture.configureByText("Example.java",
                "package managed; class Example { Object value = " + expression + "; }");
        PsiTypeCastExpression cast = PsiTreeUtil.findChildOfType(file, PsiTypeCastExpression.class);
        assertNotNull(expression, cast);
        assertNotNull(expression, cast.getCastType());
        return cast;
    }

    private static HighlightInfo highlight(String message, int start, int end) {
        var builder = HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR).range(start, end);
        if (message != null) builder.descriptionAndTooltip(message);
        return builder.create();
    }
}
