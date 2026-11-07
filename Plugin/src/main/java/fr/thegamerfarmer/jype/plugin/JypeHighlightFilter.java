package fr.thegamerfarmer.jype.plugin;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.daemon.impl.HighlightInfoFilter;
import com.intellij.openapi.project.DumbService;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Hides unresolved managed aliases and arity errors for verified Jype partial inheritance, plus the
 * override diagnostics that only exist because IntelliJ sees a Jype parent as raw: erasure clashes
 * with {@link JypeInheritedMembers} views, and overrides that javac accepts once the parent's
 * technical arguments are completed. This stateless read-action filter never hides type mismatches,
 * other missing methods or data-flow warnings, and leaves all highlighting unchanged while indices
 * are unavailable.
 */
public final class JypeHighlightFilter implements HighlightInfoFilter {
    /**
     * @param info proposed daemon highlight
     * @param file highlighted file, possibly absent or non-Java
     * @return {@code false} only for a managed alias or a recognized partial inheritance arity error
     */
    @Override
    public boolean accept(@NotNull HighlightInfo info, @Nullable PsiFile file) {
        if (!(file instanceof PsiJavaFile) || DumbService.isDumb(file.getProject())) return true;
        String description = info.getDescription();
        if (description == null) return true;
        PsiElement element = file.findElementAt(info.getStartOffset());
        if (description.contains("both methods have same erasure")) return !isInheritedViewClash(description, element);
        if (description.equals("Method does not override method from its superclass")) {
            PsiMethod method = PsiTreeUtil.getParentOfType(element, PsiMethod.class, false);
            return method == null || !JypeInheritedMembers.overridesCompletedMember(method, null);
        }
        java.util.regex.Matcher abstractMethod = ABSTRACT_METHOD.matcher(description);
        if (abstractMethod.find()) return !implementsCompletedMember(abstractMethod, element);
        PsiJavaCodeReferenceElement reference =
                PsiTreeUtil.getParentOfType(element, PsiJavaCodeReferenceElement.class, false);
        if (description.startsWith("Wrong number of type arguments")
                && reference != null
                && reference.getParameterList().getTextRange().equals(
                        new com.intellij.openapi.util.TextRange(info.getStartOffset(), info.getEndOffset()))) {
            return JypeContextualTypes.complete(reference) == null
                    && JypeInheritanceArguments.complete(reference) == null;
        }
        if (!description.startsWith("Cannot resolve symbol")) return true;
        if (reference == null || reference.isQualified() || reference.resolve() != null) return true;
        PsiClass owner = PsiTreeUtil.getParentOfType(reference, PsiClass.class);
        return owner == null || !JypeTypeSystemReader.getManagedNames(owner).contains(reference.getReferenceName());
    }

    private static final java.util.regex.Pattern CLASH = java.util.regex.Pattern.compile(
            "^'([^'(]+)\\(.*?\\)' in '([^']+)' clashes with '[^'(]+\\(.*?\\)' in '([^']+)'");
    private static final java.util.regex.Pattern ABSTRACT_METHOD = java.util.regex.Pattern.compile(
            "^Class '[^']+' must (?:either be declared abstract or )?implement abstract method '([^'(]+)\\(.*?\\)' in '([^']+)'$");

    /** The class (or a superclass) implements the member as javac sees it, with completed Jype arguments. */
    private static boolean implementsCompletedMember(java.util.regex.Matcher matcher, @Nullable PsiElement element) {
        PsiClass highlighted = PsiTreeUtil.getParentOfType(element, PsiClass.class, false);
        if (highlighted == null) return false;
        for (PsiMethod method : highlighted.findMethodsByName(matcher.group(1), true)) {
            if (method instanceof com.intellij.psi.impl.light.LightElement
                    || method.hasModifierProperty(PsiModifier.ABSTRACT)) continue;
            if (JypeInheritedMembers.overridesCompletedMember(method, matcher.group(2))) return true;
        }
        return false;
    }

    /**
     * A view projected by {@link JypeInheritedMembers} restores the generic signature that raw
     * inheritance erased; it is not a second declaration. Only clashes whose first method is such a
     * view, in the highlighted class or one of its superclasses, are hidden.
     */
    private static boolean isInheritedViewClash(String description, @Nullable PsiElement element) {
        java.util.regex.Matcher matcher = CLASH.matcher(description);
        PsiClass highlighted = PsiTreeUtil.getParentOfType(element, PsiClass.class, false);
        if (!matcher.find() || highlighted == null) return false;
        String name = matcher.group(1);
        PsiClass viewOwner = JavaPsiFacade.getInstance(highlighted.getProject())
                .findClass(matcher.group(2), highlighted.getResolveScope());
        if (!(viewOwner instanceof com.intellij.psi.impl.source.PsiExtensibleClass extensible)
                || !highlighted.isEquivalentTo(viewOwner) && !highlighted.isInheritor(viewOwner, true)
                || extensible.getOwnMethods().stream().anyMatch(method -> method.getName().equals(name))) return false;
        return !JypeInheritedMembers.methods(viewOwner, name).isEmpty();
    }
}
