package fr.thegamerfarmer.jype.plugin;

import com.intellij.psi.xml.XmlFile;
import fr.thegamerfarmer.jype.plugin.JypePomConfigurator.Status;

/** Malformed POM refusal without partial document edits. */
public class JypePomValidationTest extends JypePomFixture {
    public void testMalformedXmlFailsActionably() {
        XmlFile file = pom("    <dependencies>\n        <dependency>\n    </dependencies>\n");
        assertEquals(Status.MALFORMED_POM, status(file));
        assertNotNull(JypePomModel.inspect(file).detail());
        String before = file.getText();
        String error = JypePomConfigurator.configure(getProject(), file.getVirtualFile());
        assertNotNull(error);
        assertEquals(before, file.getText());
        try {
            apply(file);
            fail("applyPatches must reject a malformed POM");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("Malformed"));
        }
    }

    public void testWrongRootIsMalformed() {
        XmlFile file = (XmlFile) myFixture.configureByText("pom.xml", "<settings/>\n");
        assertEquals(Status.MALFORMED_POM, status(file));
    }
}
