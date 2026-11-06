package fr.thegamerfarmer.jype.util;

import org.junit.jupiter.api.extension.AnnotatedElementContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDirFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Keeps compiler and Javadoc fixtures inside the module's disposable build output. */
public final class TargetTempDirFactory implements TempDirFactory {
    @Override
    public Path createTempDirectory(AnnotatedElementContext context, ExtensionContext extension) throws IOException {
        return Files.createTempDirectory(Files.createDirectories(Path.of("target", "test-workspaces")), "junit-");
    }
}
