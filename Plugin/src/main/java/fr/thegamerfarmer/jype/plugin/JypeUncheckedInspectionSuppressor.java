package fr.thegamerfarmer.jype.plugin;

import com.intellij.codeInspection.InspectionSuppressor;
import com.intellij.codeInspection.SuppressQuickFix;
import com.intellij.openapi.project.DumbService;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiTypesUtil;
import org.jetbrains.annotations.NotNull;

/**
 * Hides unchecked warnings that exist only because IntelliJ sees Jype parents as raw.
 *
 * <p>A warning is hidden only if every call and conversion it concerns is proven sound by
 * {@link JypeCompletedTypes} (no unchecked conversion once raw Jype parents are completed) and at
 * least one involves a Jype type, or if it is a cast to a Jype type written without arguments.
 * Other unchecked casts, raw values written by the user, conversions to incompatible types and
 * warnings inside lambda or anonymous bodies of the call are kept.
 */
public final class JypeUncheckedInspectionSuppressor implements InspectionSuppressor {
    /**
     * @param element element on which the unchecked inspection reports
     * @param toolId inspection suppression identifier
     * @return whether the warning concerns only calls and conversions valid after Jype completion
     */
    @Override
    public boolean isSuppressedFor(@NotNull PsiElement element, @NotNull String toolId) {
        if (!toolId.equals("unchecked") && !toolId.equals("UNCHECKED_WARNING")
                || DumbService.isDumb(element.getProject())) return false;
        PsiTypeCastExpression cast = PsiTreeUtil.getParentOfType(element, PsiTypeCastExpression.class, false);
        if (cast != null && (cast == element || PsiTreeUtil.isAncestor(cast.getCastType(), element, false))) {
            return isWrittenRawJypeCast(cast);
        }
        PsiExpression expression = PsiTreeUtil.getParentOfType(element, PsiExpression.class, false);
        if (expression instanceof PsiReferenceExpression reference
                && reference.getParent() instanceof PsiMethodCallExpression call
                && call.getMethodExpression() == reference) expression = call;
        if (expression == null || expression instanceof PsiTypeCastExpression) return false;
        boolean jype = false;
        PsiType actual = expression.getType();
        if (expression instanceof PsiCallExpression call) {
            JypeCompletedTypes.Call checked = JypeCompletedTypes.check(call);
            if (checked == null) return false;
            jype = checked.jype();
            actual = checked.returnType();
        }
        if (expression.getParent() instanceof PsiExpressionList list && list.getParent() instanceof PsiCallExpression outer) {
            JypeCompletedTypes.Call checked = JypeCompletedTypes.check(outer);
            if (checked == null) return false;
            jype |= checked.jype();
        }
        PsiType expected = PsiTypesUtil.getExpectedTypeByParent(expression);
        if (expected != null) {
            if (actual == null || !JypeCompletedTypes.isAssignable(expected, actual)) return false;
            jype |= JypeCompletedTypes.mentionsJype(expected);
        }
        return jype;
    }

    /**
     * {@code (LibCore) plugin} is a checked cast to a raw type in the source; it only looks unchecked
     * because the plugin completes the alias arguments. A cast naming ordinary arguments keeps its warning.
     */
    private static boolean isWrittenRawJypeCast(PsiTypeCastExpression cast) {
        PsiTypeElement type = cast.getCastType();
        if (type == null || PsiTreeUtil.getChildOfType(type, PsiTypeElement.class) != null) return false;
        PsiJavaCodeReferenceElement reference = type.getInnermostComponentReferenceElement();
        if (reference == null || reference.getTypeParameters().length != 0) return false;
        JypeContextualTypes.Completion completion = JypeContextualTypes.complete(reference);
        return completion != null && completion.problems().isEmpty();
    }

    @Override
    public SuppressQuickFix @NotNull [] getSuppressActions(PsiElement element, @NotNull String toolId) {
        return SuppressQuickFix.EMPTY_ARRAY;
    }
}
