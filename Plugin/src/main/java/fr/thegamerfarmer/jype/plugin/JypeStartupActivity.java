package fr.thegamerfarmer.jype.plugin;

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.startup.StartupActivity;
import com.intellij.ui.EditorNotifications;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.NotNull;

/**
 * Refreshes open editors once indices become available, without flushing caches or deleting files.
 * The message-bus connection is disposed with the project. Smart-mode callbacks refresh daemon
 * analysis under read access and request notification recomputation through the platform.
 */
public final class JypeStartupActivity implements StartupActivity.DumbAware {
    /**
     * Installs one project-lifetime indexing listener and schedules the initial smart-mode refresh.
     *
     * @param project project being opened; callbacks tolerate later disposal
     */
    @Override
    public void runActivity(@NotNull Project project) {
        project.getMessageBus().connect(project).subscribe(DumbService.DUMB_MODE, new DumbService.DumbModeListener() {
            /** Refreshes aliases after the platform has finished rebuilding indices. */
            @Override
            public void exitDumbMode() {
                restart(project);
            }
        });
        DumbService.getInstance(project).runWhenSmart(() -> restart(project));
    }

    /** Restarts only open PSI files and notifications, skipping a project already disposed. */
    private static void restart(Project project) {
        if (project.isDisposed()) return;
        ReadAction.run(() -> {
            for (var file : FileEditorManager.getInstance(project).getOpenFiles()) {
                var psi = PsiManager.getInstance(project).findFile(file);
                if (psi != null) DaemonCodeAnalyzer.getInstance(project).restart(psi);
            }
        });
        EditorNotifications.getInstance(project).updateAllNotifications();
    }
}
