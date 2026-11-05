package fr.thegamerfarmer.jype.plugin;

import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import fr.thegamerfarmer.jype.plugin.JypePomConfigurator.Status;

import java.util.List;

/** Idempotent insertion and preservation of existing Maven declarations. */
public class JypePomEditorTest extends JypePomFixture {
    public void testEmptyPomGetsCompleteConfiguration() {
        XmlFile file = pom("");
        assertEquals(Status.MISSING_DEPENDENCY, status(file));
        assertTrue(apply(file));
        assertEquals(Status.OK, status(file));

        XmlTag dependency = JypePomModel.findDependency(root(file));
        assertNotNull(dependency);
        assertEquals("provided", text(dependency, "scope"));
        assertEquals(JypePomConfigurator.JYPE_VERSION, text(dependency, "version"));
        assertFalse(file.getText().contains("--add-exports"));
        assertNull(JypePomModel.findProblem(file));
    }

    public void testIdempotent() {
        XmlFile file = pom("");
        assertTrue(apply(file));
        String once = file.getText();
        assertFalse(apply(file));
        assertEquals(once, file.getText());
        assertEquals(1, count(once, "-Xplugin:Jype"));
    }

    public void testCommentsAndArtifactMentionsAreNotFalseMatches() {
        XmlFile file = pom("""
                    <!-- fr.thegamerfarmer.jype Jype -Xplugin:Jype annotationProcessorPaths -->
                    <dependencies>
                        <dependency>
                            <groupId>other</groupId>
                            <artifactId>Jype-extra</artifactId>
                            <version>1</version>
                        </dependency>
                    </dependencies>
                """);
        assertEquals(Status.MISSING_DEPENDENCY, status(file));
        assertTrue(apply(file));
        assertEquals(Status.OK, status(file));
        assertTrue(file.getText().contains("<!-- fr.thegamerfarmer.jype Jype -Xplugin:Jype annotationProcessorPaths -->"));
        assertTrue(file.getText().contains("<artifactId>Jype-extra</artifactId>"));
        List<XmlTag> containers = JypePomModel.children(root(file), "dependencies");
        assertEquals(1, containers.size());
        assertEquals(2, JypePomModel.children(containers.get(0), "dependency").size());
    }

    public void testExistingDependencyStillGetsProcessorPathAndLombokIsPreserved() {
        XmlFile file = pom("""
                    <dependencies>
                        <dependency>
                            <groupId>fr.thegamerfarmer.jype</groupId>
                            <artifactId>Jype</artifactId>
                            <version>${jype.version}</version>
                        </dependency>
                    </dependencies>
                    <build>
                        <plugins>
                            <plugin>
                                <artifactId>maven-compiler-plugin</artifactId>
                                <configuration>
                                    <compilerArgs>
                                        <arg>-Xlint:all</arg>
                                    </compilerArgs>
                                    <annotationProcessorPaths>
                                        <path>
                                            <groupId>org.projectlombok</groupId>
                                            <artifactId>lombok</artifactId>
                                            <version>1.18.34</version>
                                        </path>
                                    </annotationProcessorPaths>
                                </configuration>
                            </plugin>
                        </plugins>
                    </build>
                """);
        assertEquals(Status.MISSING_XPLUGIN, status(file));
        assertTrue(apply(file));
        assertEquals(Status.OK, status(file));

        XmlTag configuration = JypePomModel.child(JypePomModel.findCompilerPlugin(root(file)), "configuration");
        XmlTag paths = JypePomModel.child(configuration, "annotationProcessorPaths");
        List<XmlTag> entries = JypePomModel.children(paths, "path");
        assertEquals(2, entries.size());
        assertEquals("lombok", text(entries.get(0), "artifactId"));
        assertEquals("${jype.version}", text(entries.get(1), "version"));
        List<XmlTag> args = JypePomModel.children(JypePomModel.child(configuration, "compilerArgs"), "arg");
        assertEquals(2, args.size());
        assertEquals("-Xlint:all", args.get(0).getValue().getTrimmedText());
        assertEquals(1, JypePomModel.children(configuration, "annotationProcessorPaths").size());
    }

    public void testDependencyInSecondContainerIsDetected() {
        XmlFile file = pom("""
                    <dependencies>
                        <dependency><groupId>a</groupId><artifactId>b</artifactId></dependency>
                    </dependencies>
                    <dependencies>
                        <dependency>
                            <groupId>fr.thegamerfarmer.jype</groupId>
                            <artifactId>Jype</artifactId>
                            <version>2.0</version>
                        </dependency>
                    </dependencies>
                """);
        assertEquals(Status.MISSING_XPLUGIN, status(file));
        assertTrue(apply(file));
        assertEquals(Status.OK, status(file));
        assertEquals(1, count(file.getText(), "<artifactId>Jype</artifactId>") - 1);
        XmlTag configuration = JypePomModel.child(JypePomModel.findCompilerPlugin(root(file)), "configuration");
        assertEquals("2.0", text(JypePomModel.findProcessorPath(configuration), "version"));
    }

    public void testNewDependencyReusesProcessorPathVersion() {
        XmlFile file = pom("""
                    <build>
                        <plugins>
                            <plugin>
                                <artifactId>maven-compiler-plugin</artifactId>
                                <configuration>
                                    <compilerArgs><arg>-Xplugin:Jype</arg></compilerArgs>
                                    <annotationProcessorPaths>
                                        <path>
                                            <groupId>fr.thegamerfarmer.jype</groupId>
                                            <artifactId>Jype</artifactId>
                                            <version>3.1</version>
                                        </path>
                                    </annotationProcessorPaths>
                                </configuration>
                            </plugin>
                        </plugins>
                    </build>
                """);
        assertEquals(Status.MISSING_DEPENDENCY, status(file));
        assertTrue(apply(file));
        assertEquals(Status.OK, status(file));
        assertEquals("3.1", text(JypePomModel.findDependency(root(file)), "version"));
        assertEquals(1, count(file.getText(), "-Xplugin:Jype"));
    }
}
