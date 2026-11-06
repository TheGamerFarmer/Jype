package fr.thegamerfarmer.jype.plugin;

import com.intellij.codeInspection.InspectionSuppressor;
import com.intellij.codeInspection.SuppressQuickFix;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

/** Only raw-reference diagnostics proven to have a valid implicit substitution are redundant. */
public final class JypeRawTypeInspectionSuppressor implements InspectionSuppressor {
    @Override
    public boolean isSuppressedFor(@NotNull PsiElement element, @NotNull String toolId) {
        if (!toolId.equals("rawtypes") && !toolId.equals("RawUseOfParameterized")
                && !toolId.equals("RawUseOfParameterizedType")) return false;
        PsiJavaCodeReferenceElement reference = PsiTreeUtil.getParentOfType(
                element, PsiJavaCodeReferenceElement.class, false);
        if (reference == null) return false;
        JypeContextualTypes.Completion completion = JypeContextualTypes.complete(reference);
        return completion != null && completion.problems().isEmpty();
    }

    @Override
    public SuppressQuickFix @NotNull [] getSuppressActions(PsiElement element, @NotNull String toolId) {
        return SuppressQuickFix.EMPTY_ARRAY;
    }
}
