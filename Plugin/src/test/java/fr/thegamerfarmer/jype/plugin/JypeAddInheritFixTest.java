package fr.thegamerfarmer.jype.plugin;

import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ui.UIUtil;

import java.util.Arrays;

/** Real annotation diagnostics, intention invocation, completion and undo. */
public class JypeAddInheritFixTest extends JypeJavaFixture {
    private void configure(String body) {
        var file = define("consumer", body);
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        myFixture.getEditor().getCaretModel().moveToOffset(file.getText().indexOf("overrides"));
    }

    private void applyFix() {
        myFixture.launchAction(myFixture.findSingleIntention("Add inherit to @TypeSystem"));
        UIUtil.dispatchAllInvocationEvents();
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
    }

    public void testMissingInheritanceHighlightsAndQuickFixOpensCompletion() {
        objectSystem("library", "Item");
        objectSystem("other", "Role");
        configure("overrides={}");
        String before = myFixture.getFile().getText();
        assertTrue(myFixture.doHighlighting(HighlightSeverity.ERROR).stream()
                .anyMatch(info -> JypeTypeSystemResolver.MISSING_INHERIT.equals(info.getDescription())));
        applyFix();
        String after = myFixture.getFile().getText();
        assertTrue(after, after.contains("inherit = \"\""));
        assertTrue(after, after.contains("overrides={}"));
        assertEquals(after.indexOf("\"\"") + 1, myFixture.getEditor().getCaretModel().getOffset());
        var lookup = myFixture.getLookup();
        assertNotNull("Quick fix must open completion", lookup);
        assertSameElements(Arrays.stream(lookup.getItems().toArray(LookupElement[]::new))
                .map(LookupElement::getLookupString).toList(), "library", "other");
        com.intellij.codeInsight.lookup.LookupManager.hideActiveLookup(getProject());
        var editor = TextEditorProvider.getInstance().getTextEditor(myFixture.getEditor());
        var undo = UndoManager.getInstance(getProject());
        assertTrue(undo.isUndoAvailable(editor));
        undo.undo(editor);
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        assertEquals(before, myFixture.getFile().getText());
    }

    public void testEmptyInheritanceIsReusedAndDefinitionsArePreserved() {
        configure("""
                value={@fr.thegamerfarmer.jype.annotations.TypeDef(name="Local", bound=Object.class)},
                inherit="", overrides={@fr.thegamerfarmer.jype.annotations.TypeDef(name="Item", bound=Object.class)}
                """);
        applyFix();
        PsiAnnotation annotation = PsiTreeUtil.findChildOfType(myFixture.getFile(), PsiAnnotation.class);
        assertNotNull(annotation);
        assertEquals(1, Arrays.stream(annotation.getParameterList().getAttributes())
                .filter(pair -> "inherit".equals(pair.getName())).count());
        assertTrue(annotation.getText(), annotation.getText().contains("name=\"Local\""));
        assertTrue(annotation.getText(), annotation.getText().contains("name=\"Item\""));
        assertEquals("", JypeAnnotationSyntax.stringValue(annotation.findDeclaredAttributeValue("inherit")));
    }

    public void testQuickFixIsNotOfferedForUnknownOverrideOrValidSystem() {
        objectSystem("library", "Item");
        configure("""
                inherit="library",
                overrides=@fr.thegamerfarmer.jype.annotations.TypeDef(name="Other", bound=Object.class)
                """);
        assertTrue(myFixture.doHighlighting(HighlightSeverity.ERROR).stream()
                .anyMatch(info -> "Unknown @TypeSystem override: Other".equals(info.getDescription())));
        assertEmpty(myFixture.filterAvailableIntentions("Add inherit to @TypeSystem"));
    }

    public void testQuickFixIsNotOfferedForValidSystem() {
        objectSystem("library", "Item");
        configure("inherit=\"library\", overrides={}");
        assertFalse(myFixture.doHighlighting(HighlightSeverity.ERROR).stream()
                .anyMatch(info -> JypeTypeSystemResolver.MISSING_INHERIT.equals(info.getDescription())));
        assertEmpty(myFixture.filterAvailableIntentions("Add inherit to @TypeSystem"));
    }

    public void testQuickFixIsNotAvailableForStandaloneOrDifferentFile() {
        configure("overrides={}");
        PsiAnnotation annotation = PsiTreeUtil.findChildOfType(myFixture.getFile(), PsiAnnotation.class);
        assertNotNull(annotation);
        var fix = new JypeAddInheritFix(annotation);
        assertEquals("Configure @TypeSystem inheritance", fix.getFamilyName());
        assertTrue(fix.startInWriteAction());
        assertFalse(fix.isAvailable(getProject(), null, myFixture.getFile()));
        var other = define("standalone", "");
        assertFalse(fix.isAvailable(getProject(), myFixture.getEditor(), other));
        PsiAnnotation standalone = PsiTreeUtil.findChildOfType(other, PsiAnnotation.class);
        assertNotNull(standalone);
        assertFalse(new JypeAddInheritFix(standalone).isAvailable(getProject(), myFixture.getEditor(), other));
    }
}
