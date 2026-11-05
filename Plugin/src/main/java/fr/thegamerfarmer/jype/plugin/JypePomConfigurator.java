package fr.thegamerfarmer.jype.plugin;

import com.intellij.ide.highlighter.XmlFileType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.vfs.ReadonlyStatusHandler;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiManager;
import com.intellij.psi.xml.XmlFile;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Coordinates editor-aware inspection and undoable configuration of a module's Maven POM.
 *
 * <p>Inspection requires a read action; configuration runs on the event dispatch thread and
 * creates its own write command. Unsaved editor text is respected and never forcibly saved.
 * Structural policy lives in {@link JypePomModel}; insertions live in {@link JypePomEditor}.
 * No Maven effective model, parent POM, profile or imported BOM is resolved.
 */
public final class JypePomConfigurator {
    private static final Logger LOG = Logger.getInstance(JypePomConfigurator.class);
    private static final String COMMAND_NAME = "Configure Jype in pom.xml";

    /** Published Maven group used for both the dependency and processor path. */
    public static final String JYPE_GROUP = "fr.thegamerfarmer.jype";
    /** Case-sensitive Maven artifact ID of the Jype compiler plugin. */
    public static final String JYPE_ARTIFACT = "Jype";
    /** Fallback version when this POM supplies no reusable Jype version. */
    public static final String JYPE_VERSION = "1.0-SNAPSHOT";
    static final String XPLUGIN_ARG = "-Xplugin:Jype";
    static final String LOMBOK_GROUP = "org.projectlombok";
    static final String LOMBOK_ARTIFACT = "lombok";

    private JypePomConfigurator() {}

    /** First missing requirement, or the reason a document cannot be inspected as a POM. */
    public enum Status {
        /** All required Jype declarations exist. */
        OK,
        /** No direct project dependency activates Jype. */
        MISSING_DEPENDENCY,
        /** The direct compiler plugin does not pass {@code -Xplugin:Jype}. */
        MISSING_XPLUGIN,
        /** The direct compiler plugin has no Jype processor path. */
        MISSING_PROCESSOR,
        /** XML is invalid or its root is not {@code project}. */
        MALFORMED_POM,
        /** The file is unavailable, invalid, a directory or not XML. */
        NOT_MAVEN
    }

    /**
     * Immutable inspection outcome; no PSI or document lifecycle is retained.
     *
     * @param status first unmet configuration requirement
     * @param detail syntax diagnostic for {@link Status#MALFORMED_POM}, otherwise {@code null}
     */
    public record Inspection(@NotNull Status status, @Nullable String detail) {}

    /**
     * Inspects the current editor state within the caller's read action.
     *
     * @param project owning, live IntelliJ project
     * @param pomFile candidate Maven file
     * @return the configuration status without its optional syntax diagnostic
     */
    public static @NotNull Status check(@NotNull Project project, @NotNull VirtualFile pomFile) {
        return inspect(project, pomFile).status();
    }

    /**
     * Inspects unsaved and uncommitted text without committing or changing the editor document.
     *
     * @param project owning project; disposed projects produce {@link Status#NOT_MAVEN}
     * @param pomFile candidate XML file
     * @return status plus an optional syntax diagnostic; requires a read action
     */
    public static @NotNull Inspection inspect(@NotNull Project project, @NotNull VirtualFile pomFile) {
        if (project.isDisposed() || !pomFile.isValid() || pomFile.isDirectory()) {
            return new Inspection(Status.NOT_MAVEN, null);
        }
        PsiFile psi = PsiManager.getInstance(project).findFile(pomFile);
        if (!(psi instanceof XmlFile xml)) return new Inspection(Status.NOT_MAVEN, null);
        PsiDocumentManager documents = PsiDocumentManager.getInstance(project);
        Document document = documents.getCachedDocument(psi);
        if (document != null && documents.isUncommited(document)) {
            PsiFile snapshot = PsiFileFactory.getInstance(project).createFileFromText(
                    pomFile.getName(), XmlFileType.INSTANCE, document.getImmutableCharSequence());
            if (!(snapshot instanceof XmlFile snapshotXml)) return new Inspection(Status.NOT_MAVEN, null);
            xml = snapshotXml;
        }
        return JypePomModel.inspect(xml);
    }

    /**
     * Walks toward the content root to locate the nearest module POM, inside a read action.
     *
     * @param sourceFile source file or directory from which to start
     * @param project project defining the content boundary
     * @return the nearest valid {@code pom.xml}, or {@code null}
     */
    public static @Nullable VirtualFile findPom(@NotNull VirtualFile sourceFile, @NotNull Project project) {
        if (project.isDisposed() || !sourceFile.isValid()) return null;
        ProjectFileIndex index = ProjectFileIndex.getInstance(project);
        VirtualFile contentRoot = index.getContentRootForFile(sourceFile);
        VirtualFile directory = sourceFile.isDirectory() ? sourceFile : sourceFile.getParent();
        while (directory != null && directory.isValid()) {
            VirtualFile pom = directory.findChild("pom.xml");
            if (pom != null && pom.isValid() && !pom.isDirectory()) return pom;
            if (directory.equals(contentRoot) || !index.isInContent(directory)) break;
            directory = directory.getParent();
        }
        return null;
    }

    /**
     * Commits editor changes, checks safety and inserts missing declarations in one undoable command.
     *
     * @param project owning project
     * @param pomFile POM to edit; write permissions are requested before modification
     * @return an actionable error, or {@code null} on success (including an already configured POM)
     */
    public static @Nullable String configure(@NotNull Project project, @NotNull VirtualFile pomFile) {
        ApplicationManager.getApplication().assertIsDispatchThread();
        if (project.isDisposed()) return "Project is closed.";
        if (!pomFile.isValid()) return pomFile.getPresentableUrl() + " no longer exists.";
        PsiFile psi = PsiManager.getInstance(project).findFile(pomFile);
        if (!(psi instanceof XmlFile xml)) {
            return pomFile.getPresentableUrl() + " is not recognized as an XML file.";
        }
        PsiDocumentManager documents = PsiDocumentManager.getInstance(project);
        Document document = documents.getDocument(xml);
        if (document == null) return "Unable to open the document for " + pomFile.getPresentableUrl() + ".";
        documents.commitDocument(document);
        String problem = JypePomModel.findProblem(xml);
        if (problem != null) return pomFile.getName() + ": " + problem + ". Fix the file and try again.";
        if (JypePomModel.inspect(xml).status() == Status.OK) return null;
        String blocker = JypePomModel.findBlocker(xml);
        if (blocker != null) return pomFile.getName() + ": " + blocker;
        return writeConfiguration(project, pomFile, xml, documents, document);
    }

    /** Requests writability and reports PSI editing failures without forcing a disk save. */
    private static @Nullable String writeConfiguration(Project project, VirtualFile pomFile, XmlFile xml,
                                                       PsiDocumentManager documents, Document document) {
        if (ReadonlyStatusHandler.getInstance(project)
                .ensureFilesWritable(List.of(pomFile)).hasReadonlyFiles()) {
            return pomFile.getPresentableUrl() + " is read-only.";
        }
        try {
            WriteCommandAction.writeCommandAction(project, xml).withName(COMMAND_NAME).run(() -> {
                JypePomEditor.applyPatches(xml);
                documents.doPostponedOperationsAndUnblockDocument(document);
            });
        } catch (IncorrectOperationException e) {
            LOG.warn("Jype: failed to modify " + pomFile.getPresentableUrl(), e);
            return "Unable to modify " + pomFile.getName() + ": " + e.getMessage();
        }
        return null;
    }
}
