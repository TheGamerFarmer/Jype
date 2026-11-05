package fr.thegamerfarmer.jype.plugin;

import com.intellij.ide.projectView.PresentationData;
import com.intellij.ide.projectView.ProjectViewNode;
import com.intellij.ide.projectView.ProjectViewNodeDecorator;
import com.intellij.openapi.util.IconLoader;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

import javax.swing.Icon;

/**
 * Distinguishes source type-system declarations in the project view without changing PSI.
 * The platform owns presentation updates and supplies read access for the source inspection.
 */
public final class JypePackageInfoDecorator implements ProjectViewNodeDecorator {
    /** Shared plugin-resource icon; not associated with an individual PSI file's lifetime. */
    static final Icon ICON = IconLoader.getIcon("/icons/typeSystem.svg", JypePackageInfoDecorator.class);

    /**
     * @param node project-view node; non-type-system nodes are left unchanged
     * @param data mutable presentation receiving the type-system icon and compact file label
     */
    @Override
    public void decorate(@NotNull ProjectViewNode<?> node, @NotNull PresentationData data) {
        if (node.getValue() instanceof PsiFile file && JypeTypeSystemReader.isTypeSystemFile(file)) {
            data.clearText();
            data.setPresentableText("package-info");
            data.setIcon(ICON);
        }
    }
}
