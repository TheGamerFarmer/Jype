package fr.thegamerfarmer.jype.plugin;

import com.intellij.psi.PsiFile;
import com.intellij.testFramework.DumbModeTestUtils;

/** Project-view presentation isolated to source type-system declarations. */
public class JypePackageInfoDecoratorTest extends JypeJavaFixture {
    public void testJypePackageInfoPresentationDoesNotChangeOrdinaryFiles() {
        PsiFile jype = define("managed", "{}");
        PsiFile plain = myFixture.addFileToProject("plain/package-info.java", "package plain;");
        PsiFile foreign = myFixture.addFileToProject("foreign/package-info.java",
                "@Deprecated package foreign;");
        var decorator = new JypePackageInfoDecorator();
        for (PsiFile file : java.util.List.of(jype, plain, foreign)) {
            var data = new com.intellij.ide.projectView.PresentationData();
            data.setPresentableText("package-info.java");
            var node = new com.intellij.ide.projectView.impl.nodes.PsiFileNode(
                    getProject(), file, com.intellij.ide.projectView.ViewSettings.DEFAULT);
            decorator.decorate(node, data);
            assertEquals(file == jype ? "package-info" : "package-info.java", data.getPresentableText());
            if (file == jype) assertSame(JypePackageInfoDecorator.ICON, data.getIcon(false));
        }
        assertEquals("package-info.java", jype.getName());
        DumbModeTestUtils.runInDumbModeSynchronously(getProject(), () -> {
            assertTrue(JypeTypeSystemReader.isTypeSystemFile(jype));
            assertFalse(JypeTypeSystemReader.isTypeSystemFile(foreign));
        });
    }
}
