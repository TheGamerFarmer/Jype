package fr.thegamerfarmer.jype.plugin;

import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.util.RecursionManager;
import com.intellij.psi.*;
import com.intellij.psi.impl.light.LightMethod;
import com.intellij.psi.impl.light.LightField;
import com.intellij.psi.impl.source.PsiClassReferenceType;
import com.intellij.psi.impl.source.PsiExtensibleClass;
import com.intellij.psi.util.MethodSignatureUtil;
import com.intellij.psi.util.MethodSignature;
import com.intellij.psi.util.MethodSignatureBackedByPsiMethod;
import com.intellij.psi.util.TypeConversionUtil;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/** Supplies inherited method views where Java's raw reference-list PSI cannot infer arguments. */
final class JypeInheritedMembers {
    private JypeInheritedMembers() {}

    static List<PsiMethod> methods(PsiClass owner, String nameHint) {
        if (!(owner instanceof PsiExtensibleClass extensible) || owner instanceof PsiTypeParameter
                || JypeTypeSystemReader.isVirtualClass(owner)) return List.of();
        List<PsiMethod> result = RecursionManager.doPreventingRecursion(owner, false,
                () -> compute(owner, extensible, nameHint));
        return result == null ? List.of() : result;
    }

    private static List<PsiMethod> compute(PsiClass owner, PsiExtensibleClass extensible, String nameHint) {
        List<PsiMethod> methods = new ArrayList<>();
        for (PsiClassType completed : completedParents(owner)) {
            PsiClass target = completed.resolve();
            if (target == null) continue;
            PsiSubstitutor parent = completed.resolveGenerics().getSubstitutor();
            for (PsiMethod method : target.getAllMethods()) {
                if (method.isConstructor() || method.hasTypeParameters()
                        || nameHint != null && !nameHint.equals(method.getName())
                        || method.hasModifierProperty(PsiModifier.PRIVATE)
                        || !JavaPsiFacade.getInstance(owner.getProject()).getResolveHelper()
                        .isAccessible(method, owner, null)) continue;
                PsiClass declaring = method.getContainingClass();
                if (declaring == null) continue;
                PsiSubstitutor substitution = TypeConversionUtil.getSuperClassSubstitutor(declaring, target, parent);
                LightMethod view = new LightMethod(owner.getManager(), method, owner, JavaLanguage.INSTANCE, substitution) {
                    @Override
                    public @NotNull MethodSignature getSignature(@NotNull PsiSubstitutor substitutor) {
                        return MethodSignatureBackedByPsiMethod.create(this, substitutor, false);
                    }
                };
                if (extensible.getOwnMethods().stream().anyMatch(own ->
                        MethodSignatureUtil.areSignaturesEqual(own, view))
                        || methods.stream().anyMatch(previous ->
                        MethodSignatureUtil.areSignaturesEqual(previous, view))) continue;
                methods.add(view);
            }
        }
        return List.copyOf(methods);
    }

    static List<PsiField> fields(PsiClass owner, String nameHint) {
        if (!(owner instanceof PsiExtensibleClass extensible) || owner instanceof PsiTypeParameter
                || JypeTypeSystemReader.isVirtualClass(owner)) return List.of();
        List<PsiField> result = RecursionManager.doPreventingRecursion(owner, false, () -> {
            List<PsiField> fields = new ArrayList<>();
            for (PsiClassType parent : completedParents(owner)) {
                PsiClass target = parent.resolve();
                if (target == null) continue;
                for (PsiField field : target.getAllFields()) {
                    if (nameHint != null && !nameHint.equals(field.getName())
                            || field.hasModifierProperty(PsiModifier.PRIVATE)
                            || !JavaPsiFacade.getInstance(owner.getProject()).getResolveHelper()
                            .isAccessible(field, owner, null)
                            || extensible.getOwnFields().stream().anyMatch(own -> own.getName().equals(field.getName()))) continue;
                    PsiClass declaring = field.getContainingClass();
                    if (declaring == null) continue;
                    PsiSubstitutor substitution = TypeConversionUtil.getSuperClassSubstitutor(
                            declaring, target, parent.resolveGenerics().getSubstitutor());
                    LightField view = new LightField(owner.getManager(), field, owner) {
                        @Override public @NotNull PsiType getType() {
                            return Objects.requireNonNull(substitution.substitute(field.getType()));
                        }
                    };
                    view.setNavigationElement(field);
                    fields.add(view);
                }
            }
            return List.copyOf(fields);
        });
        return result == null ? List.of() : result;
    }

    private static List<PsiClassType> completedParents(PsiClass owner) {
        List<PsiClassType> parents = new ArrayList<>();
        parents.addAll(List.of(owner.getExtendsListTypes()));
        parents.addAll(List.of(owner.getImplementsListTypes()));
        List<PsiClassType> result = new ArrayList<>();
        for (PsiClassType parentType : parents) {
            PsiClassType completed;
            if (JypeContextualTypes.isPublicType(parentType)) {
                completed = parentType;
            } else if (parentType instanceof PsiClassReferenceType referenceType) {
                JypeContextualTypes.Completion completion = JypeContextualTypes.complete(referenceType.getReference());
                if (completion == null || !completion.problems().isEmpty()) continue;
                completed = completion.type();
            } else continue;
            result.add(completed);
        }
        return List.copyOf(result);
    }
}
