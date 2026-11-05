package fr.thegamerfarmer.jype.plugin;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.project.DumbService;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

/**
 * Reports declaration-wide validation errors instead of exposing a partial alias mapping.
 * This stateless daemon annotator reads PSI under platform read access and skips indexing.
 */
public final class JypeTypeSystemAnnotator implements Annotator {
    /**
     * Attaches one error to the owning package annotation, never to its nested TypeDef values.
     *
     * @param element PSI element currently visited by the daemon
     * @param holder annotation sink for this pass
     */
    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        if (!(element instanceof PsiAnnotation annotation)
                || !(annotation.getContainingFile() instanceof PsiJavaFile file)
                || !"package-info.java".equals(file.getName())
                || DumbService.isDumb(element.getProject())) return;
        JypeTypeSystemReader.Result system = JypeTypeSystemReader.findTypeSystem(
                element.getProject(), file.getPackageName(), element.getResolveScope());
        if (system != null && system.declaration().equals(annotation) && system.error() != null) {
            holder.newAnnotation(HighlightSeverity.ERROR, system.error()).range(annotation).create();
        }
    }
}
