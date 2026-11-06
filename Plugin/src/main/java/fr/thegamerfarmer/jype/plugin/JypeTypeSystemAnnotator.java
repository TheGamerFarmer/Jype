package fr.thegamerfarmer.jype.plugin;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.project.DumbService;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

/**
 * Reports invalid declarations and bound violations in completed partial inheritance arguments.
 * This stateless daemon annotator reads PSI under platform read access and skips indexing.
 */
public final class JypeTypeSystemAnnotator implements Annotator {
    /**
     * Checks partial inheritance bounds and attaches declaration errors to the package annotation.
     *
     * @param element PSI element currently visited by the daemon
     * @param holder annotation sink for this pass
     */
    @Override
    public void annotate(@NotNull PsiElement element, @NotNull AnnotationHolder holder) {
        if (element instanceof PsiJavaCodeReferenceElement reference) {
            JypeInheritanceArguments.Completion completion = JypeInheritanceArguments.complete(reference);
            if (completion != null) {
                for (JypeInheritanceArguments.Problem problem : completion.problems()) {
                    holder.newAnnotation(HighlightSeverity.ERROR, problem.message()).range(problem.element()).create();
                }
            }
            if (completion == null) {
                JypeContextualTypes.Completion contextual = JypeContextualTypes.complete(reference);
                if (contextual != null) {
                    for (JypeInheritanceArguments.Problem problem : contextual.problems()) {
                        holder.newAnnotation(HighlightSeverity.ERROR, problem.message()).range(problem.element()).create();
                    }
                }
            }
            return;
        }
        if (!(element instanceof PsiAnnotation annotation)
                || !(annotation.getContainingFile() instanceof PsiJavaFile file)
                || !"package-info.java".equals(file.getName())
                || DumbService.isDumb(element.getProject())) return;
        JypeTypeSystemReader.Result system = JypeTypeSystemReader.findTypeSystem(
                element.getProject(), file.getPackageName(), element.getResolveScope());
        if (system != null && system.declaration().equals(annotation) && system.error() != null) {
            var diagnostic = holder.newAnnotation(HighlightSeverity.ERROR, system.error()).range(annotation);
            if (JypeTypeSystemResolver.MISSING_INHERIT.equals(system.error())) {
                diagnostic.withFix(new JypeAddInheritFix(annotation));
            }
            diagnostic.create();
        }
    }
}
