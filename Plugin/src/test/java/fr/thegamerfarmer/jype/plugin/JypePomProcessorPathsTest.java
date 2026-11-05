package fr.thegamerfarmer.jype.plugin;

import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import fr.thegamerfarmer.jype.plugin.JypePomConfigurator.Status;

import java.util.List;

/** Processor discovery and Lombok version safety when introducing explicit paths. */
public class JypePomProcessorPathsTest extends JypePomFixture {
    public void testDirectLombokIsPreservedWhenCreatingProcessorPaths() {
        XmlFile file = pom("""
                    <dependencies>
                        <dependency>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                            <version>${lombok.version}</version>
                            <scope>provided</scope>
                        </dependency>
                    </dependencies>
                """);
        assertNull(JypePomModel.findBlocker(file));
        assertTrue(apply(file));
        assertEquals(Status.OK, status(file));
        List<XmlTag> paths = processorPaths(file);
        assertEquals(2, paths.size());
        assertEquals("lombok", text(paths.get(0), "artifactId"));
        assertEquals("org.projectlombok", text(paths.get(0), "groupId"));
        assertEquals("${lombok.version}", text(paths.get(0), "version"));
        assertEquals("Jype", text(paths.get(1), "artifactId"));
        assertFalse(apply(file));
        assertEquals(2, processorPaths(file).size());
    }

    public void testLombokVersionFromDependencyManagementInExistingCompilerPlugin() {
        XmlFile file = pom("""
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>org.projectlombok</groupId>
                                <artifactId>lombok</artifactId>
                                <version>1.18.34</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                    <dependencies>
                        <dependency><groupId>a</groupId><artifactId>b</artifactId></dependency>
                    </dependencies>
                    <dependencies>
                        <dependency>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </dependency>
                    </dependencies>
                    <build>
                        <plugins>
                            <plugin>
                                <artifactId>maven-compiler-plugin</artifactId>
                                <configuration>
                                    <release>17</release>
                                </configuration>
                            </plugin>
                        </plugins>
                    </build>
                """);
        assertTrue(apply(file));
        assertEquals(Status.OK, status(file));
        List<XmlTag> paths = processorPaths(file);
        assertEquals(2, paths.size());
        assertEquals("1.18.34", text(paths.get(0), "version"));
        assertTrue(file.getText().contains("<release>17</release>"));
    }

    public void testUnknownLombokVersionFailsBeforeAnyEdit() {
        XmlFile file = pom("""
                    <dependencies>
                        <dependency>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </dependency>
                    </dependencies>
                """);
        String before = file.getText();
        assertEquals(Status.MISSING_DEPENDENCY, status(file));
        assertNotNull(JypePomModel.findBlocker(file));

        String error = JypePomConfigurator.configure(getProject(), file.getVirtualFile());
        assertNotNull(error);
        assertTrue(error.contains("org.projectlombok:lombok"));
        assertEquals(before, file.getText());
        try {
            apply(file);
            fail("applyPatches must reject without partial writes");
        } catch (IllegalStateException expected) {
            assertEquals(before, file.getText());
        }
    }

    public void testExistingProcessorPathsAreNotReorganized() {
        XmlFile file = pom("""
                    <dependencies>
                        <dependency>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </dependency>
                    </dependencies>
                    <build>
                        <plugins>
                            <plugin>
                                <artifactId>maven-compiler-plugin</artifactId>
                                <configuration>
                                    <annotationProcessorPaths>
                                        <path>
                                            <groupId>org.mapstruct</groupId>
                                            <artifactId>mapstruct-processor</artifactId>
                                            <version>1.5.5.Final</version>
                                        </path>
                                    </annotationProcessorPaths>
                                </configuration>
                            </plugin>
                        </plugins>
                    </build>
                """);
        assertNull("existing container: a Lombok version is not required",
                JypePomModel.findBlocker(file));
        assertTrue(apply(file));
        assertEquals(Status.OK, status(file));
        List<XmlTag> paths = processorPaths(file);
        assertEquals(2, paths.size());
        assertEquals("mapstruct-processor", text(paths.get(0), "artifactId"));
        assertEquals("Jype", text(paths.get(1), "artifactId"));
    }

    public void testLombokOnlyInDependencyManagementIsNotAdded() {
        XmlFile file = pom("""
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>org.projectlombok</groupId>
                                <artifactId>lombok</artifactId>
                                <version>1.18.34</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                """);
        assertTrue(apply(file));
        List<XmlTag> paths = processorPaths(file);
        assertEquals(1, paths.size());
        assertEquals("Jype", text(paths.get(0), "artifactId"));
    }
}
