package fr.thegamerfarmer.jype.plugin;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.command.undo.UndoManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.xml.XmlFile;
import fr.thegamerfarmer.jype.plugin.JypePomConfigurator.Status;

/** Editor-aware configuration and a single undoable write command. */
public class JypePomConfiguratorTest extends JypePomFixture {
    public void testConfigureUsesUnsavedDocumentAndIsUndoable() {
        XmlFile file = pom("");
        Document document = PsiDocumentManager.getInstance(getProject()).getDocument(file);
        assertNotNull(document);
        WriteCommandAction.runWriteCommandAction(getProject(), () ->
                document.insertString(document.getText().indexOf("</project>"), "    <!-- unsaved -->\n"));
        String before = document.getText();
        assertEquals(Status.MISSING_DEPENDENCY, JypePomConfigurator.check(getProject(), file.getVirtualFile()));

        assertNull(JypePomConfigurator.configure(getProject(), file.getVirtualFile()));
        assertTrue(document.getText().contains("<!-- unsaved -->"));
        assertEquals(Status.OK, JypePomConfigurator.check(getProject(), file.getVirtualFile()));
        assertNull("configure without changes should succeed",
                JypePomConfigurator.configure(getProject(), file.getVirtualFile()));

        FileEditor editor = TextEditorProvider.getInstance().getTextEditor(myFixture.getEditor());
        UndoManager undo = UndoManager.getInstance(getProject());
        assertTrue(undo.isUndoAvailable(editor));
        undo.undo(editor);
        assertEquals(before, document.getText());
    }
}
