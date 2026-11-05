package fr.thegamerfarmer.jype.plugin;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.daemon.impl.HighlightInfoFilter;
import com.intellij.openapi.project.DumbService;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Hides only unresolved, unqualified managed-name highlights missed by Java's virtual lookup.
 * This stateless read-action filter never hides type mismatches, missing methods or data-flow
 * warnings, and leaves all highlighting unchanged while indices are unavailable.
 */
public final class JypeHighlightFilter implements HighlightInfoFilter {
    /**
     * @param info proposed daemon highlight
     * @param file highlighted file, possibly absent or non-Java
     * @return {@code false} only for an unresolved managed alias in a real Java owner class
     */
    @Override
    public boolean accept(@NotNull HighlightInfo info, @Nullable PsiFile file) {
        if (!(file instanceof PsiJavaFile) || DumbService.isDumb(file.getProject())) return true;
        String description = info.getDescription();
        if (description == null || !description.startsWith("Cannot resolve symbol")) return true;
        PsiElement element = file.findElementAt(info.getStartOffset());
        PsiJavaCodeReferenceElement reference =
                PsiTreeUtil.getParentOfType(element, PsiJavaCodeReferenceElement.class, false);
        if (reference == null || reference.isQualified() || reference.resolve() != null) return true;
        PsiClass owner = PsiTreeUtil.getParentOfType(reference, PsiClass.class);
        return owner == null || !JypeTypeSystemReader.getManagedNames(owner).contains(reference.getReferenceName());
    }
}
