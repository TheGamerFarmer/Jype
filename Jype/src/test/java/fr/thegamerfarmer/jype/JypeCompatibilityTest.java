package fr.thegamerfarmer.jype;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class JypeCompatibilityTest {
    private static final List<String> PACKAGES = List.of("api", "code", "model", "processing", "tree", "util");

    @Test
    void automaticOpeningWorksWithoutJvmExports() throws Exception {
        runProbe("automatic", List.of(), 0);
    }

    @Test
    void explicitExportsAndOpensDoNotNeedUnsafe() throws Exception {
        List<String> options = new ArrayList<>();
        for (String pkg : PACKAGES) {
            options.add("--add-exports=jdk.compiler/com.sun.tools.javac." + pkg + "=ALL-UNNAMED");
        }
        options.add("--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED");
        if (Runtime.version().feature() >= 25) {
            options.add("--sun-misc-unsafe-memory-access=deny");
        }
        runProbe("explicit", options, 0);
    }

    @Test
    void disabledAutomaticOpeningGivesActionableJvmOptions() throws Exception {
        assumeTrue(Runtime.version().feature() >= 25);
        String output = runProbe("denied", List.of("--sun-misc-unsafe-memory-access=deny"), 1);
        assertTrue(output.contains("--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED"), output);
        assertTrue(output.contains("--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED"), output);
        assertTrue(output.contains("Runtime JDK:"), output);
    }

    private String runProbe(String name, List<String> options, int expectedExit) throws Exception {
        Path directory = Files.createDirectories(Path.of("target", "compatibility-tests"));
        Path output = directory.resolve(name + ".log");
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", executable).toString());
        command.add("-Dstdout.encoding=UTF-8");
        command.add("-Dstderr.encoding=UTF-8");
        command.addAll(options);
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(Probe.class.getName());
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Module-opening probe timed out");
            String text = Files.readString(output);
            assertEquals(expectedExit, process.exitValue(), text);
            return text;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    public static class Probe {
        public static void main(String[] args) {
            JypeModuleOpener.open();
            JypeModuleOpener.open();
            Module compiler = ModuleLayer.boot().findModule("jdk.compiler").orElseThrow();
            Module target = Probe.class.getModule();
            for (String pkg : PACKAGES) {
                if (!compiler.isExported("com.sun.tools.javac." + pkg, target)) {
                    throw new AssertionError("Missing export: " + pkg);
                }
            }
            if (!compiler.isOpen("com.sun.tools.javac.code", target)) {
                throw new AssertionError("Missing reflective access to javac.code");
            }
        }
    }
}
