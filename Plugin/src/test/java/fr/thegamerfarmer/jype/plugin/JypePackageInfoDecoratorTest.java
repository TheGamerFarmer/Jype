package fr.thegamerfarmer.jype.plugin;

import com.intellij.psi.PsiFile;
import com.intellij.testFramework.DumbModeTestUtils;

/** Project-view presentation isolated to source type-system declarations. */
public class JypePackageInfoDecoratorTest extends JypeJavaFixture {
    public void testTabPresentationFromBackgroundThreadWithoutReadAction() throws Exception {
        var file = define("managed", "{}").getVirtualFile();
        var tabs = new JypePackageInfoPresentation();
        com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread(() -> {
            assertFalse(com.intellij.openapi.application.ApplicationManager.getApplication().isReadAccessAllowed());
            assertEquals("package-info", tabs.getEditorTabTitle(getProject(), file));
            assertSame(JypePackageInfoDecorator.ICON, tabs.getIcon(file, 0, getProject()));
        }).get(15, java.util.concurrent.TimeUnit.SECONDS);
    }

    public void testJypePackageInfoPresentationDoesNotChangeOrdinaryFiles() {
        PsiFile jype = define("managed", "{}");
        PsiFile plain = myFixture.addFileToProject("plain/package-info.java", "package plain;");
        PsiFile foreign = myFixture.addFileToProject("foreign/package-info.java",
                "@Deprecated package foreign;");
        var decorator = new JypePackageInfoDecorator();
        var tabs = new JypePackageInfoPresentation();
        for (PsiFile file : java.util.List.of(jype, plain, foreign)) {
            var data = new com.intellij.ide.projectView.PresentationData();
            data.setPresentableText("package-info.java");
            var node = new com.intellij.ide.projectView.impl.nodes.PsiFileNode(
                    getProject(), file, com.intellij.ide.projectView.ViewSettings.DEFAULT);
            decorator.decorate(node, data);
            assertEquals(file == jype ? "package-info" : "package-info.java", data.getPresentableText());
            if (file == jype) assertSame(JypePackageInfoDecorator.ICON, data.getIcon(false));
            assertEquals(file == jype ? "package-info" : null,
                    tabs.getEditorTabTitle(getProject(), file.getVirtualFile()));
            assertEquals(file == jype ? "package-info" : "package-info.java",
                    com.intellij.openapi.fileEditor.impl.EditorTabPresentationUtil
                            .getEditorTabTitle(getProject(), file.getVirtualFile()));
            assertSame(file == jype ? JypePackageInfoDecorator.ICON : null,
                    tabs.getIcon(file.getVirtualFile(), 0, getProject()));
        }
        assertEquals("package-info.java", jype.getName());
        DumbModeTestUtils.runInDumbModeSynchronously(getProject(), () -> {
            assertTrue(JypeTypeSystemReader.isTypeSystemFile(jype));
            assertFalse(JypeTypeSystemReader.isTypeSystemFile(foreign));
            assertEquals("package-info", tabs.getEditorTabTitle(getProject(), jype.getVirtualFile()));
            assertSame(JypePackageInfoDecorator.ICON, tabs.getIcon(jype.getVirtualFile(), 0, getProject()));
        });
        assertNull(tabs.getIcon(jype.getVirtualFile(), 0, null));
        PsiFile ordinary = myFixture.addFileToProject("Other.java", "class Other {}");
        assertNull(tabs.getEditorTabTitle(getProject(), ordinary.getVirtualFile()));
    }
}
