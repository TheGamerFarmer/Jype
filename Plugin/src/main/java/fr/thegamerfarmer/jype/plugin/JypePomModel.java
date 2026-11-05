package fr.thegamerfarmer.jype.plugin;

import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiErrorElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static fr.thegamerfarmer.jype.plugin.JypePomConfigurator.*;

/**
 * Read-only, namespace-aware view of the declarations written in one Maven POM.
 *
 * <p>Callers must hold a PSI read action (or a write action). Parent POMs, imported BOMs,
 * profiles and effective Maven models are deliberately not resolved. Dependency and compiler
 * lookups inspect only direct project dependencies and {@code build/plugins}, respectively.
 */
final class JypePomModel {
    static final String COMPILER_GROUP = "org.apache.maven.plugins";
    static final String COMPILER_ARTIFACT = "maven-compiler-plugin";

    private JypePomModel() {}

    /**
     * Checks syntax before reporting the first missing Jype declaration.
     *
     * @param file current XML PSI, including any committed editor changes
     * @return configuration status and, for malformed XML, an actionable diagnostic
     */
    static @NotNull Inspection inspect(@NotNull XmlFile file) {
        String problem = findProblem(file);
        if (problem != null) return new Inspection(Status.MALFORMED_POM, problem);
        XmlTag project = Objects.requireNonNull(file.getRootTag());
        if (findDependency(project) == null) return new Inspection(Status.MISSING_DEPENDENCY, null);
        XmlTag plugin = findCompilerPlugin(project);
        XmlTag configuration = plugin == null ? null : child(plugin, "configuration");
        if (configuration == null || !hasXPlugin(configuration)) {
            return new Inspection(Status.MISSING_XPLUGIN, null);
        }
        return new Inspection(findProcessorPath(configuration) == null
                ? Status.MISSING_PROCESSOR : Status.OK, null);
    }

    /**
     * Rejects XML syntax errors and documents without a Maven project root.
     *
     * @param file XML document to examine
     * @return the first diagnostic, or {@code null} if structural editing is safe
     */
    static @Nullable String findProblem(@NotNull XmlFile file) {
        PsiErrorElement error = PsiTreeUtil.findChildOfType(file, PsiErrorElement.class);
        if (error != null) {
            int line = StringUtil.offsetToLineNumber(file.getText(), error.getTextRange().getStartOffset()) + 1;
            return "Invalid XML at line " + line + ": " + error.getErrorDescription();
        }
        XmlTag root = file.getRootTag();
        return root == null || !"project".equals(root.getLocalName())
                ? "The <project> root element is missing" : null;
    }

    /**
     * Protects Lombok discovery when introducing an explicit processor-path container.
     *
     * @param file structurally valid POM
     * @return a refusal message if a direct Lombok dependency has no locally known version,
     *         or {@code null} when no processor would be silently disabled
     */
    static @Nullable String findBlocker(@NotNull XmlFile file) {
        XmlTag project = file.getRootTag();
        if (project == null) return null;
        XmlTag plugin = findCompilerPlugin(project);
        XmlTag configuration = plugin == null ? null : child(plugin, "configuration");
        boolean createsContainer = configuration == null
                || children(configuration, "annotationProcessorPaths").isEmpty();
        if (createsContainer
                && findDirectDependency(project, LOMBOK_GROUP, LOMBOK_ARTIFACT) != null
                && declaredVersion(project, LOMBOK_GROUP, LOMBOK_ARTIFACT) == null) {
            return "The version of " + LOMBOK_GROUP + ":" + LOMBOK_ARTIFACT + " cannot be found in this pom.xml"
                    + " (it may be inherited from a parent POM or BOM). Add a <version> to the"
                    + " Lombok dependency, or declare annotationProcessorPaths with Lombok yourself,"
                    + " then try again; otherwise Lombok will no longer run.";
        }
        return null;
    }

    /**
     * Selects direct children without crossing namespace boundaries.
     *
     * @param parent container whose namespace defines the Maven vocabulary
     * @param localName unprefixed tag name
     * @return matching children in document order
     */
    static @NotNull List<XmlTag> children(@NotNull XmlTag parent, @NotNull String localName) {
        List<XmlTag> result = new ArrayList<>();
        for (XmlTag tag : parent.getSubTags()) {
            if (localName.equals(tag.getLocalName()) && parent.getNamespace().equals(tag.getNamespace())) {
                result.add(tag);
            }
        }
        return result;
    }

    /**
     * @param parent container to inspect
     * @param localName unprefixed tag name in the parent's namespace
     * @return the first direct matching child, or {@code null}
     */
    static @Nullable XmlTag child(@NotNull XmlTag parent, @NotNull String localName) {
        List<XmlTag> found = children(parent, localName);
        return found.isEmpty() ? null : found.get(0);
    }

    /**
     * @param parent container to inspect
     * @param localName direct child name
     * @return trimmed, nonempty child text, preserving Maven property expressions, or {@code null}
     */
    static @Nullable String childText(@NotNull XmlTag parent, @NotNull String localName) {
        XmlTag tag = child(parent, localName);
        if (tag == null) return null;
        String text = tag.getValue().getTrimmedText();
        return text.isEmpty() ? null : text;
    }

    /**
     * Matches literal Maven coordinates; property-based coordinates are not evaluated.
     *
     * @param coordinates dependency, plugin or processor path containing coordinate children
     * @param group expected literal group ID
     * @param artifact expected literal artifact ID
     * @return whether both coordinate fields match exactly
     */
    private static boolean matches(XmlTag coordinates, String group, String artifact) {
        return group.equals(childText(coordinates, "groupId"))
                && artifact.equals(childText(coordinates, "artifactId"));
    }

    /**
     * @param project Maven project root
     * @return its first direct Jype dependency, excluding management and profiles, or {@code null}
     */
    static @Nullable XmlTag findDependency(@NotNull XmlTag project) {
        return findDirectDependency(project, JYPE_GROUP, JYPE_ARTIFACT);
    }

    /**
     * @param project Maven project root
     * @param group literal group ID
     * @param artifact literal artifact ID
     * @return the first matching direct dependency, or {@code null}
     */
    static @Nullable XmlTag findDirectDependency(XmlTag project, String group, String artifact) {
        for (XmlTag dependencies : children(project, "dependencies")) {
            for (XmlTag dependency : children(dependencies, "dependency")) {
                if (matches(dependency, group, artifact)) return dependency;
            }
        }
        return null;
    }

    /**
     * Finds a dependency in this POM's direct dependency-management section only.
     *
     * @param project Maven project root
     * @param group expected literal group ID
     * @param artifact expected literal artifact ID
     * @return the first matching locally managed dependency, or {@code null}
     */
    static @Nullable XmlTag findManagedDependency(XmlTag project, String group, String artifact) {
        for (XmlTag management : children(project, "dependencyManagement")) {
            for (XmlTag dependencies : children(management, "dependencies")) {
                for (XmlTag dependency : children(dependencies, "dependency")) {
                    if (matches(dependency, group, artifact)) return dependency;
                }
            }
        }
        return null;
    }

    /**
     * @param project Maven project root
     * @param group literal group ID
     * @param artifact literal artifact ID
     * @return direct version text, otherwise local managed version text, or {@code null};
     *         property expressions are retained rather than evaluated
     */
    static @Nullable String declaredVersion(XmlTag project, String group, String artifact) {
        XmlTag direct = findDirectDependency(project, group, artifact);
        String version = direct == null ? null : childText(direct, "version");
        if (version != null) return version;
        XmlTag managed = findManagedDependency(project, group, artifact);
        return managed == null ? null : childText(managed, "version");
    }

    /**
     * @param project Maven project root
     * @return the first compiler plugin in {@code build/plugins}, accepting Maven's default
     *         plugin group but excluding plugin management, or {@code null}
     */
    static @Nullable XmlTag findCompilerPlugin(@NotNull XmlTag project) {
        for (XmlTag build : children(project, "build")) {
            for (XmlTag plugins : children(build, "plugins")) {
                for (XmlTag plugin : children(plugins, "plugin")) {
                    String group = childText(plugin, "groupId");
                    if (COMPILER_ARTIFACT.equals(childText(plugin, "artifactId"))
                            && (group == null || COMPILER_GROUP.equals(group))) return plugin;
                }
            }
        }
        return null;
    }

    /**
     * @param configuration compiler plugin configuration
     * @return whether plural compiler arguments or the legacy singular argument activate Jype
     */
    static boolean hasXPlugin(@NotNull XmlTag configuration) {
        for (XmlTag compilerArgs : children(configuration, "compilerArgs")) {
            for (XmlTag arg : compilerArgs.getSubTags()) {
                if (isXPlugin(arg.getValue().getTrimmedText())) return true;
            }
        }
        for (XmlTag compilerArgument : children(configuration, "compilerArgument")) {
            for (String token : compilerArgument.getValue().getTrimmedText().split("\\s+")) {
                if (isXPlugin(token)) return true;
            }
        }
        return false;
    }

    /**
     * Accepts Jype's exact activation token, optionally followed by plugin options.
     *
     * @param argument trimmed compiler argument or a token from the legacy argument string
     * @return whether the argument activates Jype rather than another similarly named plugin
     */
    private static boolean isXPlugin(String argument) {
        return argument.equals(XPLUGIN_ARG) || argument.startsWith(XPLUGIN_ARG + " ");
    }

    /**
     * @param configuration compiler plugin configuration
     * @return the first Jype annotation processor path, or {@code null}
     */
    static @Nullable XmlTag findProcessorPath(@NotNull XmlTag configuration) {
        for (XmlTag paths : children(configuration, "annotationProcessorPaths")) {
            for (XmlTag path : children(paths, "path")) {
                if (matches(path, JYPE_GROUP, JYPE_ARTIFACT)) return path;
            }
        }
        return null;
    }
}
