package fr.thegamerfarmer.jype.plugin;

import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static fr.thegamerfarmer.jype.plugin.JypePomConfigurator.*;
import static fr.thegamerfarmer.jype.plugin.JypePomModel.*;

/**
 * Adds missing Jype Maven declarations without rewriting existing configuration.
 *
 * <p>Instances are confined to one write command and one XML document. Existing versions,
 * processor paths, comments and unrelated plugins are preserved. Only newly inserted blocks
 * are reformatted; the caller owns undo support, document commits and write permissions.
 */
final class JypePomEditor {
    private static final String COMPILER_VERSION = "3.13.0";
    private final List<XmlTag> added = new ArrayList<>();

    private JypePomEditor() {}

    /**
     * Validates all known blockers before making any structural edit.
     *
     * @param file POM to modify inside an IntelliJ write command
     * @return whether any declaration was inserted
     * @throws IllegalStateException if syntax or a missing Lombok version makes editing unsafe
     */
    static boolean applyPatches(@NotNull XmlFile file) {
        String problem = findProblem(file);
        if (problem != null) throw new IllegalStateException("Malformed pom.xml: " + problem);
        String blocker = findBlocker(file);
        if (blocker != null) throw new IllegalStateException(blocker);
        JypePomEditor editor = new JypePomEditor();
        editor.patch(Objects.requireNonNull(file.getRootTag()));
        editor.reformat(file);
        return !editor.added.isEmpty();
    }

    /** Inserts dependency, compiler activation and processor path in dependency order. */
    private void patch(XmlTag project) {
        XmlTag plugin = findCompilerPlugin(project);
        XmlTag configuration = plugin == null ? null : child(plugin, "configuration");
        XmlTag processorPath = configuration == null ? null : findProcessorPath(configuration);
        addDependency(project, processorPath);
        if (plugin == null) plugin = addCompilerPlugin(project);
        if (configuration == null) configuration = getOrAdd(plugin, "configuration");
        if (!hasXPlugin(configuration)) {
            add(getOrAdd(configuration, "compilerArgs"), "arg", XPLUGIN_ARG);
        }
        if (processorPath == null) addProcessorPaths(project, configuration);
    }

    /** Uses an existing processor version or local dependency management before the fallback version. */
    private void addDependency(XmlTag project, XmlTag processorPath) {
        if (findDependency(project) != null) return;
        String version = processorPath != null ? childText(processorPath, "version")
                : findManagedDependency(project, JYPE_GROUP, JYPE_ARTIFACT) != null ? null : JYPE_VERSION;
        XmlTag dependency = add(getOrAdd(project, "dependencies"), "dependency", "");
        addCoordinates(dependency, JYPE_GROUP, JYPE_ARTIFACT, version);
        addChild(dependency, "scope", "provided");
    }

    /** Creates the compiler plugin only when no direct compiler plugin exists. */
    private XmlTag addCompilerPlugin(XmlTag project) {
        XmlTag plugins = getOrAdd(getOrAdd(project, "build"), "plugins");
        XmlTag plugin = add(plugins, "plugin", "");
        addCoordinates(plugin, COMPILER_GROUP, COMPILER_ARTIFACT, COMPILER_VERSION);
        return plugin;
    }

    /** Preserves automatic Lombok discovery when introducing a processor-path container. */
    private void addProcessorPaths(XmlTag project, XmlTag configuration) {
        String version = declaredVersion(project, JYPE_GROUP, JYPE_ARTIFACT);
        boolean newContainer = children(configuration, "annotationProcessorPaths").isEmpty();
        XmlTag paths = getOrAdd(configuration, "annotationProcessorPaths");
        if (newContainer && findDirectDependency(project, LOMBOK_GROUP, LOMBOK_ARTIFACT) != null) {
            XmlTag lombok = add(paths, "path", "");
            addCoordinates(lombok, LOMBOK_GROUP, LOMBOK_ARTIFACT,
                    Objects.requireNonNull(declaredVersion(project, LOMBOK_GROUP, LOMBOK_ARTIFACT)));
        }
        XmlTag path = add(paths, "path", "");
        addCoordinates(path, JYPE_GROUP, JYPE_ARTIFACT, version != null ? version : JYPE_VERSION);
    }

    /** Writes coordinates verbatim, omitting the version when dependency management supplies it. */
    private void addCoordinates(XmlTag parent, String group, String artifact, String version) {
        addChild(parent, "groupId", group);
        addChild(parent, "artifactId", artifact);
        if (version != null) addChild(parent, "version", version);
    }

    /** Reuses the first same-namespace child, tracking only newly created containers. */
    private XmlTag getOrAdd(XmlTag parent, String localName) {
        XmlTag existing = child(parent, localName);
        return existing != null ? existing : add(parent, localName, "");
    }

    /** Tracks formatting roots once; descendants of an already tracked block need no separate pass. */
    private XmlTag add(XmlTag parent, String localName, String text) {
        XmlTag tag = addChild(parent, localName, text);
        if (added.stream().noneMatch(root -> PsiTreeUtil.isAncestor(root, tag, false))) added.add(tag);
        return tag;
    }

    /** Escapes text while retaining the parent's namespace and prefix handling. */
    private XmlTag addChild(XmlTag parent, String localName, String text) {
        XmlTag created = parent.createChildTag(localName, parent.getNamespace(),
                StringUtil.escapeXmlEntities(text), false);
        return parent.addSubTag(created, false);
    }

    /** Formats inserted roots from right to left so earlier offsets remain stable. */
    private void reformat(XmlFile file) {
        if (added.isEmpty()) return;
        CodeStyleManager styles = CodeStyleManager.getInstance(file.getProject());
        List<XmlTag> roots = new ArrayList<>(added);
        roots.sort((a, b) -> Integer.compare(b.getTextRange().getStartOffset(), a.getTextRange().getStartOffset()));
        for (XmlTag tag : roots) {
            if (!tag.isValid()) continue;
            CharSequence text = file.getViewProvider().getContents();
            int start = tag.getTextRange().getStartOffset();
            while (start > 0 && Character.isWhitespace(text.charAt(start - 1))) start--;
            styles.reformatText(file, start, tag.getTextRange().getEndOffset());
        }
    }
}
