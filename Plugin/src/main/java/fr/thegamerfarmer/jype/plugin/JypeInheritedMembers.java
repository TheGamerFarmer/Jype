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
import org.jetbrains.annotations.Nullable;

import java.util.*;

/** Supplies inherited method views where Java's raw reference-list PSI cannot infer arguments. */
final class JypeInheritedMembers {
    private JypeInheritedMembers() {}

    /** An inherited method as typed in a consumer class; it stands for the API declaration it exposes. */
    private static final class MethodView extends LightMethod {
        MethodView(PsiClass owner, PsiMethod original, PsiSubstitutor substitution) {
            super(owner.getManager(), original, owner, JavaLanguage.INSTANCE, substitution);
        }

        @Override
        public @NotNull MethodSignature getSignature(@NotNull PsiSubstitutor substitutor) {
            return MethodSignatureBackedByPsiMethod.create(this, substitutor, false);
        }

        PsiMethod original() {
            return myMethod;
        }
    }

    /** An inherited field as typed in a consumer class; it stands for the API declaration it exposes. */
    private static final class FieldView extends LightField {
        private final PsiField original;
        private final PsiSubstitutor substitution;

        FieldView(PsiClass owner, PsiField original, PsiSubstitutor substitution) {
            super(owner.getManager(), original, owner);
            this.original = original;
            this.substitution = substitution;
            setNavigationElement(original);
        }

        @Override
        public @NotNull PsiType getType() {
            return Objects.requireNonNull(substitution.substitute(original.getType()));
        }
    }

    /**
     * The API declaration behind an inherited view, which is documented as that declaration:
     * the view only re-types it with the consumer's bindings and technical arguments.
     *
     * @return the declared member, or {@code null} when {@code element} is not a Jype view
     */
    static @Nullable PsiMember original(PsiElement element) {
        if (element instanceof MethodView view) return view.original();
        if (element instanceof FieldView view) return view.original;
        return null;
    }

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
                        // An abstract view in a concrete class would hide a missing implementation.
                        || method.hasModifierProperty(PsiModifier.ABSTRACT) && !owner.isInterface()
                        && !owner.hasModifierProperty(PsiModifier.ABSTRACT)
                        || !JavaPsiFacade.getInstance(owner.getProject()).getResolveHelper()
                        .isAccessible(method, owner, null)) continue;
                PsiClass declaring = method.getContainingClass();
                if (declaring == null || !alteredByRawInheritance(declaring, method)) continue;
                PsiSubstitutor substitution = TypeConversionUtil.getSuperClassSubstitutor(declaring, target, parent);
                MethodView view = new MethodView(owner, method, substitution);
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
                    if (declaring == null || !declaring.hasTypeParameters() || erased(field.getType())) continue;
                    PsiSubstitutor substitution = TypeConversionUtil.getSuperClassSubstitutor(
                            declaring, target, parent.resolveGenerics().getSubstitutor());
                    fields.add(new FieldView(owner, field, substitution));
                }
            }
            return List.copyOf(fields);
        });
        return result == null ? List.of() : result;
    }

    /**
     * Whether a physical method overrides a member of a raw Jype parent once that parent's
     * technical arguments are completed (javac sees {@code process(ConcreteNode)} implement
     * {@code process(ENTITY)} with {@code ENTITY = ConcreteNode}; IntelliJ only sees the erased member).
     *
     * @param declaringName simple or qualified name the overridden member must be declared in, or {@code null}
     */
    static boolean overridesCompletedMember(PsiMethod method, @org.jetbrains.annotations.Nullable String declaringName) {
        PsiClass owner = method.getContainingClass();
        if (owner == null || method.isConstructor() || method.hasModifierProperty(PsiModifier.STATIC)) return false;
        MethodSignature signature = method.getSignature(PsiSubstitutor.EMPTY);
        Set<PsiClass> visited = new HashSet<>();
        Deque<PsiClass> pending = new ArrayDeque<>(List.of(owner));
        while (!pending.isEmpty()) {
            PsiClass current = pending.poll();
            if (!visited.add(current)) continue;
            for (PsiClassType completed : completedParents(current)) {
                PsiClass target = completed.resolve();
                if (target == null) continue;
                PsiSubstitutor parent = completed.resolveGenerics().getSubstitutor();
                for (PsiMethod candidate : target.findMethodsByName(method.getName(), true)) {
                    PsiClass declaring = candidate.getContainingClass();
                    if (declaring == null || candidate.hasModifierProperty(PsiModifier.PRIVATE)
                            || candidate.hasModifierProperty(PsiModifier.STATIC)
                            || declaringName != null && !declaringName.equals(declaring.getName())
                            && !declaringName.equals(declaring.getQualifiedName())) continue;
                    PsiSubstitutor substitution = TypeConversionUtil.getSuperClassSubstitutor(declaring, target, parent);
                    if (MethodSignatureUtil.areSignaturesEqual(signature, candidate.getSignature(substitution))) return true;
                }
            }
            for (PsiClass parent : current.getSupers()) {
                if (!(parent instanceof PsiCompiledElement)) pending.add(parent);
            }
        }
        return false;
    }

    /**
     * Raw inheritance only erases members of generic declarations, and only when their signature
     * mentions generic types. Other members are inherited unchanged; projecting them would only
     * duplicate them (for example {@code Object.getClass()} would lose its special typing).
     */
    private static boolean alteredByRawInheritance(PsiClass declaring, PsiMethod method) {
        if (!declaring.hasTypeParameters()) return false;
        if (!erased(method.getReturnType())) return true;
        for (PsiParameter parameter : method.getParameterList().getParameters()) {
            if (!erased(parameter.getType())) return true;
        }
        return false;
    }

    private static boolean erased(PsiType type) {
        return type == null || type.equals(TypeConversionUtil.erasure(type));
    }

    static List<PsiClassType> completedParents(PsiClass owner) {
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
