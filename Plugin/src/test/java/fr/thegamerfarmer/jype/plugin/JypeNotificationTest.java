package fr.thegamerfarmer.jype.plugin;

import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.testFramework.DumbModeTestUtils;
import com.intellij.ui.EditorNotificationPanel;

/** Covers notification collection separately from EDT construction and action callbacks. */
public class JypeNotificationTest extends JypeJavaFixture {
    public void testConfigureAndDismissActions() {
        VirtualFile pom = myFixture.addFileToProject("pom.xml", "<project/>").getVirtualFile();
        VirtualFile source = managedSource();
        EditorNotificationPanel panel = panel(source);
        assertTrue(panel.getText().contains("dependency"));
        panel.findLabelByName("Configure").doClick();
        assertEquals(JypePomConfigurator.Status.OK, JypePomConfigurator.check(getProject(), pom));
        assertNull(new JypeNotificationProvider().collectNotificationData(getProject(), source));
        panel.findLabelByName("Ignore").doClick();
        assertEquals(Boolean.TRUE, source.getUserData(JypeNotificationProvider.DISMISSED));
        assertNull(new JypeNotificationProvider().collectNotificationData(getProject(), source));
    }

    public void testMalformedPomOpensWithoutOfferingConfiguration() {
        VirtualFile pom = myFixture.addFileToProject("pom.xml", "<wrong/>").getVirtualFile();
        EditorNotificationPanel panel = panel(managedSource());
        assertTrue(panel.getText().contains("invalid"));
        assertNull(panel.findLabelByName("Configure"));
        panel.findLabelByName("Open pom.xml").doClick();
        assertTrue(FileEditorManager.getInstance(getProject()).isFileOpen(pom));
    }

    public void testConfigurationBlockerRemainsVisible() {
        VirtualFile pom = myFixture.addFileToProject("pom.xml", """
                <project><dependencies><dependency>
                    <groupId>org.projectlombok</groupId><artifactId>lombok</artifactId>
                </dependency></dependencies></project>
                """).getVirtualFile();
        VirtualFile source = managedSource();
        EditorNotificationPanel panel = panel(source);
        panel.findLabelByName("Configure").doClick();
        assertTrue(panel.getText(), panel.getText().contains("org.projectlombok:lombok"));
        assertEquals(JypePomConfigurator.Status.MISSING_DEPENDENCY,
                JypePomConfigurator.check(getProject(), pom));
        assertNotNull(new JypeNotificationProvider().collectNotificationData(getProject(), source));
    }

    public void testIrrelevantAndIndexingFilesHaveNoBanner() {
        JypeNotificationProvider provider = new JypeNotificationProvider();
        VirtualFile source = managedSource();
        assertNull(provider.collectNotificationData(getProject(), source));
        myFixture.addFileToProject("pom.xml", "<project/>");
        VirtualFile plain = myFixture.addFileToProject("plain.txt", "text").getVirtualFile();
        assertNull(provider.collectNotificationData(getProject(), plain));
        assertNull(provider.collectNotificationData(getProject(), source.getParent()));
        VirtualFile ordinary = myFixture.addFileToProject("other/Plain.java",
                "package other; class Plain {}").getVirtualFile();
        assertNull(provider.collectNotificationData(getProject(), ordinary));
        VirtualFile defaultPackage = myFixture.addFileToProject("Default.java",
                "class Default {}").getVirtualFile();
        assertNull(provider.collectNotificationData(getProject(), defaultPackage));
        DumbModeTestUtils.runInDumbModeSynchronously(getProject(),
                () -> assertNull(provider.collectNotificationData(getProject(), source)));
    }

    public void testMessagesIdentifyEachMissingRequirement() {
        for (var status : JypePomConfigurator.Status.values()) {
            String message = JypeNotificationProvider.buildMessage(
                    new JypePomConfigurator.Inspection(status, "broken XML"));
            if (status == JypePomConfigurator.Status.OK) assertEquals("", message);
            else assertTrue(status.name(), message.startsWith("Jype: "));
            if (status == JypePomConfigurator.Status.MALFORMED_POM)
                assertTrue(message.contains("broken XML"));
            if (status == JypePomConfigurator.Status.MISSING_XPLUGIN)
                assertTrue(message.contains("-Xplugin:Jype"));
            if (status == JypePomConfigurator.Status.MISSING_PROCESSOR)
                assertTrue(message.contains("annotationProcessorPaths"));
        }
    }

    private VirtualFile managedSource() {
        objectSystem("managed", "Item");
        return myFixture.addFileToProject("managed/Example.java",
                "package managed; class Example { Item value; }").getVirtualFile();
    }

    private EditorNotificationPanel panel(VirtualFile source) {
        myFixture.configureFromExistingVirtualFile(source);
        var factory = new JypeNotificationProvider().collectNotificationData(getProject(), source);
        assertNotNull(factory);
        var editor = TextEditorProvider.getInstance().getTextEditor(myFixture.getEditor());
        var component = factory.apply(editor);
        assertInstanceOf(component, EditorNotificationPanel.class);
        return (EditorNotificationPanel) component;
    }
}
