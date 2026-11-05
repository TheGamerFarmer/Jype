package fr.thegamerfarmer.jype.plugin;

import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import fr.thegamerfarmer.jype.plugin.JypePomConfigurator.Status;

/** Namespace handling and isolation from managed or unrelated plugins. */
public class JypePomStructureTest extends JypePomFixture {
    public void testOnlyBuildPluginsCompilerPluginIsEdited() {
        String management = """
                        <pluginManagement>
                            <plugins>
                                <plugin>
                                    <groupId>org.apache.maven.plugins</groupId>
                                    <artifactId>maven-compiler-plugin</artifactId>
                                    <version>3.11.0</version>
                                    <configuration>
                                        <release>8</release>
                                    </configuration>
                                </plugin>
                            </plugins>
                        </pluginManagement>
                """;
        String surefire = """
                            <plugin>
                                <artifactId>maven-surefire-plugin</artifactId>
                                <configuration>
                                    <compilerArgs><arg>-Xother</arg></compilerArgs>
                                </configuration>
                            </plugin>
                """;
        XmlFile file = pom("""
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>x</groupId>
                                <artifactId>y</artifactId>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                    <build>
                """ + management + """
                        <plugins>
                """ + surefire + """
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-compiler-plugin</artifactId>
                                <version>3.12.1</version>
                                <configuration>
                                    <release>8</release>
                                </configuration>
                            </plugin>
                        </plugins>
                    </build>
                """);
        assertTrue(apply(file));
        assertEquals(Status.OK, status(file));
        String result = file.getText();
        assertTrue(result.contains(management));
        assertTrue(result.contains(surefire));
        assertTrue(result.contains("<version>3.12.1</version>"));
        assertFalse(result.contains("--add-exports"));
        assertEquals(1, count(result, "-Xplugin:Jype"));

        XmlTag dependencyManagement = JypePomModel.child(root(file), "dependencyManagement");
        XmlTag managed = JypePomModel.child(dependencyManagement, "dependencies");
        assertEquals(1, JypePomModel.children(managed, "dependency").size());
    }

    public void testPrefixedNamespace() {
        XmlFile file = (XmlFile) myFixture.configureByText("pom.xml",
                "<m:project xmlns:m=\"" + NS + "\">\n"
                        + "    <m:modelVersion>4.0.0</m:modelVersion>\n"
                        + "    <m:build><m:plugins/></m:build>\n"
                        + "</m:project>\n");
        assertTrue(apply(file));
        assertEquals(Status.OK, status(file));
        assertFalse(file.getText().contains("<dependencies>"));
        assertTrue(file.getText().contains("<m:dependencies>"));
        assertTrue(file.getText().contains("<m:arg>-Xplugin:Jype</m:arg>"));
        assertEquals(1, JypePomModel.children(root(file), "build").size());
        for (XmlTag tag : root(file).getSubTags()) assertEquals(NS, tag.getNamespace());
    }

    public void testForeignNamespaceTagsAreIgnored() {
        XmlFile file = pom("""
                    <x:dependencies xmlns:x="urn:other">
                        <x:dependency>
                            <x:groupId>fr.thegamerfarmer.jype</x:groupId>
                            <x:artifactId>Jype</x:artifactId>
                        </x:dependency>
                    </x:dependencies>
                """);
        assertEquals(Status.MISSING_DEPENDENCY, status(file));
    }
}
