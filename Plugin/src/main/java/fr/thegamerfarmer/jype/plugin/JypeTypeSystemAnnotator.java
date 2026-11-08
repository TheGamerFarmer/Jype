package fr.thegamerfarmer.jype.plugin;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.Annotator;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.openapi.project.DumbService;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiUtil;
import org.jetbrains.annotations.NotNull;

/**
 * Reports invalid declarations, bound violations in completed partial inheritance arguments, and
 * alias creations whose binding constructor is not accessible.
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
        if (element instanceof PsiNewExpression creation) {
            checkCreation(creation, holder);
            return;
        }
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

    /**
     * Alias constructors mirror every binding constructor publicly; a creation follows the binding
     * constructor's own access, as the engine does: {@code new ALIAS(...)} compiles where
     * {@code new Binding(...)} would. Abstract bindings are never created themselves.
     */
    private static void checkCreation(PsiNewExpression creation, AnnotationHolder holder) {
        PsiJavaCodeReferenceElement reference = creation.getClassReference();
        if (reference == null || creation.getAnonymousClass() != null
                || DumbService.isDumb(creation.getProject())) return;
        PsiMethod constructor = creation.resolveConstructor();
        PsiMethod binding = constructor == null ? null : JypeTypeSystemReader.mirroredConstructor(constructor);
        if (binding == null || binding.hasModifierProperty(PsiModifier.PUBLIC)) return;
        PsiClass owner = binding.getContainingClass();
        if (owner == null || owner.hasModifierProperty(PsiModifier.ABSTRACT) || accessible(binding, owner, creation)) return;
        PsiClass site = PsiUtil.getTopLevelClass(creation);
        holder.newAnnotation(HighlightSeverity.ERROR, "[Jype] " + reference.getReferenceName() + " is bound to "
                        + owner.getQualifiedName() + ", whose constructor with "
                        + binding.getParameterList().getParametersCount() + " parameter(s) is not accessible from "
                        + (site == null ? "here" : site.getQualifiedName()) + ".")
                .range(reference).create();
    }

    /** A protected constructor of another package is only reachable through {@code super(...)}. */
    private static boolean accessible(PsiMethod binding, PsiClass owner, PsiElement place) {
        if (binding.hasModifierProperty(PsiModifier.PRIVATE)) {
            PsiClass top = PsiUtil.getTopLevelClass(owner);
            return top != null && top.getManager().areElementsEquivalent(top, PsiUtil.getTopLevelClass(place));
        }
        return place.getContainingFile() instanceof PsiJavaFile file
                && file.getPackageName().equals(PsiUtil.getPackageName(owner));
    }
}
