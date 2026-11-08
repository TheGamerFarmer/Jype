package fr.thegamerfarmer.jype.plugin;

import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiManager;
import com.intellij.ui.EditorNotificationPanel;
import com.intellij.ui.EditorNotificationProvider;
import com.intellij.ui.EditorNotifications;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import java.util.function.Function;

/**
 * Offers Maven configuration for Java files governed by a visible Jype type system.
 *
 * <p>Notification data is collected under the platform's read action; the returned factory
 * creates Swing controls on the event dispatch thread. Dismissal is file-local and lasts only
 * for the IDE session. Indexing races defer the banner until the next platform refresh.
 */
public final class JypeNotificationProvider implements EditorNotificationProvider {

    /** Session-only dismissal flag; no project setting or file content is modified. */
    static final Key<Boolean> DISMISSED = Key.create("jype.pom.notification.dismissed");

    /**
     * Inspects the nearest POM only for Java files in a visible type-system package.
     *
     * @param project project owning the editor
     * @param file candidate editor file
     * @return an EDT panel factory, or {@code null} if configured, dismissed, indexing or irrelevant
     */
    @Override
    public @Nullable Function<? super @NotNull FileEditor, ? extends @Nullable JComponent>
    collectNotificationData(@NotNull Project project, @NotNull VirtualFile file) {
        if (project.isDisposed() || !file.isValid() || file.isDirectory()) return null;
        if (!"java".equalsIgnoreCase(file.getExtension())) return null;
        if (Boolean.TRUE.equals(file.getUserData(DISMISSED))) return null;
        if (DumbService.isDumb(project)) return null;

        VirtualFile pom;
        JypePomConfigurator.Inspection inspection;
        try {
            PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
            if (!(psiFile instanceof PsiJavaFile javaFile) || !isInTypeSystem(project, javaFile)) return null;
            pom = JypePomConfigurator.findPom(file, project);
            if (pom == null) return null;
            inspection = JypePomConfigurator.inspect(project, pom);
        } catch (IndexNotReadyException e) {
            return null;
        }

        JypePomConfigurator.Status status = inspection.status();
        if (status == JypePomConfigurator.Status.OK || status == JypePomConfigurator.Status.NOT_MAVEN) return null;
        return editor -> createPanel(project, file, pom, inspection, editor);
    }

    /** Builds controls on the EDT; action callbacks preserve the configurator's undoable command. */
    private static EditorNotificationPanel createPanel(Project project, VirtualFile file, VirtualFile pom,
                                                       JypePomConfigurator.Inspection inspection, FileEditor editor) {
        boolean malformed = inspection.status() == JypePomConfigurator.Status.MALFORMED_POM;
        EditorNotificationPanel panel = new EditorNotificationPanel(editor,
                malformed ? EditorNotificationPanel.Status.Error : EditorNotificationPanel.Status.Warning);
        panel.setText(buildMessage(inspection));
        if (malformed) {
            panel.createActionLabel("Open pom.xml", () -> {
                if (pom.isValid()) FileEditorManager.getInstance(project).openFile(pom, true);
            });
        } else {
            panel.createActionLabel("Configure", () -> {
                String error = JypePomConfigurator.configure(project, pom);
                if (error != null) panel.setText("Jype: " + error);
                else EditorNotifications.getInstance(project).updateAllNotifications();
            });
        }
        panel.createActionLabel("Ignore", () -> {
            file.putUserData(DISMISSED, Boolean.TRUE);
            EditorNotifications.getInstance(project).updateNotifications(file);
        });
        return panel;
    }

    /**
     * A file is relevant when a visible type system governs its package, or when a dependency
     * (library or other module) declares one, so consumers without any local package-info are
     * covered. Uses the file's module-aware resolve scope.
     */
    private static boolean isInTypeSystem(@NotNull Project project, @NotNull PsiJavaFile javaFile) {
        var scope = javaFile.getResolveScope();
        String pkg = javaFile.getPackageName();
        if (!pkg.isEmpty() && JypeTypeSystemReader.findTypeSystem(project, pkg, scope) != null) return true;
        VirtualFile virtualFile = javaFile.getVirtualFile();
        return virtualFile != null && JypeTypeSystemReader.usesDependencyTypeSystem(project, virtualFile, scope);
    }

    /**
     * @param inspection first missing POM requirement or syntax failure
     * @return editor-facing diagnostic, empty for an already configured POM
     */
    @NotNull
    static String buildMessage(@NotNull JypePomConfigurator.Inspection inspection) {
        return switch (inspection.status()) {
            case MISSING_DEPENDENCY ->
                "Jype: dependency " + JypePomConfigurator.JYPE_GROUP + ":" + JypePomConfigurator.JYPE_ARTIFACT
                        + " is missing from pom.xml.";
            case MISSING_XPLUGIN ->
                "Jype: argument " + JypePomConfigurator.XPLUGIN_ARG
                        + " is missing from the maven-compiler-plugin's compilerArgs.";
            case MISSING_PROCESSOR ->
                "Jype: the maven-compiler-plugin's annotationProcessorPaths does not include Jype.";
            case MALFORMED_POM ->
                "Jype: invalid pom.xml (" + inspection.detail() + "). Fix it to configure Jype.";
            case NOT_MAVEN -> "Jype: no usable pom.xml found.";
            case OK -> "";
        };
    }
}