package fr.thegamerfarmer.jype.plugin;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.PsiDocumentManager;

/** Public Maven entrypoints must respect editor state and module boundaries. */
public class JypePomActionsTest extends JypePomFixture {
    public void testInspectionUsesUncommittedTextWithoutCommittingIt() {
        var file = pom("");
        var documents = PsiDocumentManager.getInstance(getProject());
        var document = documents.getDocument(file);
        assertNotNull(document);
        WriteCommandAction.runWriteCommandAction(getProject(), () -> document.setText("<wrong/>"));
        assertTrue(documents.isUncommited(document));
        var result = JypePomConfigurator.inspect(getProject(), file.getVirtualFile());
        assertEquals(JypePomConfigurator.Status.MALFORMED_POM, result.status());
        assertNotNull(result.detail());
        assertTrue("Inspection must not commit an editor snapshot", documents.isUncommited(document));
        assertNotNull(JypePomConfigurator.configure(getProject(), file.getVirtualFile()));
        assertEquals("<wrong/>", document.getText());
    }

    public void testNonXmlAndDirectoriesAreNotMavenDocuments() {
        var text = myFixture.addFileToProject("plain.txt", "text").getVirtualFile();
        assertEquals(JypePomConfigurator.Status.NOT_MAVEN, JypePomConfigurator.check(getProject(), text));
        assertNotNull(JypePomConfigurator.configure(getProject(), text));
        assertEquals(JypePomConfigurator.Status.NOT_MAVEN,
                JypePomConfigurator.check(getProject(), text.getParent()));
    }

    public void testNearestPomAndMissingPom() {
        var source = myFixture.addFileToProject("module/src/Example.java", "class Example {}").getVirtualFile();
        assertNull(JypePomConfigurator.findPom(source, getProject()));
        var rootPom = myFixture.addFileToProject("pom.xml", "<project/>").getVirtualFile();
        assertEquals(rootPom, JypePomConfigurator.findPom(source, getProject()));
        var modulePom = myFixture.addFileToProject("module/pom.xml", "<project/>").getVirtualFile();
        assertEquals(modulePom, JypePomConfigurator.findPom(source, getProject()));
        assertEquals(modulePom, JypePomConfigurator.findPom(source.getParent(), getProject()));
    }

    public void testAlreadyConfiguredActionIsIdempotent() {
        var file = pom("");
        assertNull(JypePomConfigurator.configure(getProject(), file.getVirtualFile()));
        String configured = file.getText();
        assertNull(JypePomConfigurator.configure(getProject(), file.getVirtualFile()));
        assertEquals(configured, file.getText());
    }

    public void testDeletedFilesAreRejected() throws Exception {
        var file = pom("").getVirtualFile();
        WriteCommandAction.writeCommandAction(getProject()).run(() -> file.delete(this));
        assertFalse(file.isValid());
        assertEquals(JypePomConfigurator.Status.NOT_MAVEN, JypePomConfigurator.check(getProject(), file));
        assertNull(JypePomConfigurator.findPom(file, getProject()));
        assertNotNull(JypePomConfigurator.configure(getProject(), file));
        assertNull(new JypeNotificationProvider().collectNotificationData(getProject(), file));
    }
}
