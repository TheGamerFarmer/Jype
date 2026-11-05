package fr.thegamerfarmer.jype.plugin;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;
import fr.thegamerfarmer.jype.plugin.JypePomConfigurator.Status;

import java.util.List;

/** Namespace-aware XML setup and write-command support shared by focused Maven tests. */
public abstract class JypePomFixture extends BasePlatformTestCase {
    protected static final String NS = "http://maven.apache.org/POM/4.0.0";

    protected XmlFile pom(String body) {
        return (XmlFile) myFixture.configureByText("pom.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<project xmlns=\"" + NS + "\">\n"
                        + "    <modelVersion>4.0.0</modelVersion>\n" + body + "</project>\n");
    }

    protected boolean apply(XmlFile file) {
        return WriteCommandAction.runWriteCommandAction(getProject(),
                (Computable<Boolean>) () -> JypePomEditor.applyPatches(file));
    }

    protected static Status status(XmlFile file) {
        return JypePomModel.inspect(file).status();
    }

    protected static XmlTag root(XmlFile file) {
        return file.getRootTag();
    }

    protected static int count(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) count++;
        return count;
    }

    protected static String text(XmlTag parent, String name) {
        XmlTag tag = JypePomModel.child(parent, name);
        return tag == null ? null : tag.getValue().getTrimmedText();
    }

    protected static List<XmlTag> processorPaths(XmlFile file) {
        XmlTag configuration = JypePomModel.child(JypePomModel.findCompilerPlugin(root(file)), "configuration");
        List<XmlTag> containers = JypePomModel.children(configuration, "annotationProcessorPaths");
        assertEquals(1, containers.size());
        return JypePomModel.children(containers.get(0), "path");
    }
}
