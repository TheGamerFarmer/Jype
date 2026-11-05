package fr.thegamerfarmer.jype.plugin;

import com.intellij.ide.FileIconProvider;
import com.intellij.openapi.fileEditor.impl.EditorTabTitleProvider;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Icon;

/** Uses the type-system presentation in editor tabs without renaming the source file. */
public final class JypePackageInfoPresentation implements EditorTabTitleProvider, FileIconProvider, DumbAware {
    private boolean isTypeSystem(Project project, VirtualFile file) {
        if (project == null || project.isDisposed() || !file.isValid()
                || !"package-info.java".equals(file.getName())) return false;
        return JypeTypeSystemReader.isTypeSystemFile(PsiManager.getInstance(project).findFile(file));
    }

    @Override
    public @Nullable String getEditorTabTitle(@NotNull Project project, @NotNull VirtualFile file) {
        return isTypeSystem(project, file) ? "package-info" : null;
    }

    @Override
    public @Nullable Icon getIcon(@NotNull VirtualFile file, int flags, @Nullable Project project) {
        return isTypeSystem(project, file) ? JypePackageInfoDecorator.ICON : null;
    }
}
