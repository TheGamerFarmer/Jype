package fr.thegamerfarmer.jype.plugin;

import com.intellij.codeInspection.InspectionSuppressor;
import com.intellij.codeInspection.SuppressQuickFix;
import com.intellij.openapi.project.DumbService;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

/**
 * Narrows failing-cast suppression to the mismatch between a virtual subclass and its real bound.
 * Called under PSI read access; indexing disables suppression. Real type parameters, unrelated
 * casts and inspections other than Java data-flow/constant-condition analysis remain untouched.
 */
public final class JypeCastInspectionSuppressor implements InspectionSuppressor {
    /**
     * @param element inspected cast target; other element shapes are never suppressed
     * @param toolId platform inspection identifier
     * @return whether the operand is assignable to the alias's sole declared bound
     */
    @Override
    public boolean isSuppressedFor(@NotNull PsiElement element, @NotNull String toolId) {
        if (!"DataFlowIssue".equals(toolId) && !"ConstantConditions".equals(toolId)) return false;
        if (DumbService.isDumb(element.getProject())
                || !(element instanceof PsiTypeElement typeElement)
                || !(typeElement.getParent() instanceof PsiTypeCastExpression cast)
                || cast.getCastType() != typeElement
                || !(typeElement.getType() instanceof PsiClassType targetType)) return false;
        PsiClass target = targetType.resolve();
        if (target == null || !JypeTypeSystemReader.isVirtualClass(target)) return false;
        PsiExpression operand = cast.getOperand();
        PsiType sourceType = operand == null ? null : operand.getType();
        if (sourceType == null) return false;
        PsiClassType[] bounds = target.getImplementsListTypes();
        if (bounds.length == 0) bounds = target.getExtendsListTypes();
        return bounds.length == 1 && bounds[0].isAssignableFrom(sourceType);
    }

    /**
     * Does not offer persistent suppression annotations for a synthetic IDE representation.
     *
     * @param element inspected PSI element
     * @param toolId platform inspection identifier
     * @return no suppression quick fixes
     */
    @Override
    public SuppressQuickFix @NotNull [] getSuppressActions(@NotNull PsiElement element,
                                                         @NotNull String toolId) {
        return SuppressQuickFix.EMPTY_ARRAY;
    }
}
