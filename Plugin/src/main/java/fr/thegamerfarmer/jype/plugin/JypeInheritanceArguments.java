package fr.thegamerfarmer.jype.plugin;

import com.intellij.openapi.project.DumbService;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.TypeConversionUtil;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Recognizes partial inheritance arguments using the engine's shared-name and trailing rules.
 * Completion is read-only and never fills an ordinary missing argument with a default.
 */
final class JypeInheritanceArguments {
    private JypeInheritanceArguments() {}

    /** Diagnostics evaluated against the completed superclass or interface substitution. */
    record Completion(List<Problem> problems) {}

    /** A bound violation, attached to its explicit argument or to the implicit argument list. */
    record Problem(PsiElement element, String message) {}

    /**
     * Completes only partial extends/implements lists governed by a valid visible type system.
     * The IDE still performs its normal checks for complete, raw and unrelated applications.
     */
    static @Nullable Completion complete(PsiJavaCodeReferenceElement reference) {
        if (!(reference.getParent() instanceof PsiReferenceList)
                || DumbService.isDumb(reference.getProject())
                || !(reference.resolve() instanceof PsiClass target)) return null;
        PsiClass owner = PsiTreeUtil.getParentOfType(reference, PsiClass.class);
        if (owner == null || owner instanceof PsiTypeParameter) return null;
        PsiTypeParameter[] parameters = target.getTypeParameters();
        PsiTypeElement[] supplied = reference.getParameterList().getTypeParameterElements();
        if (supplied.length == 0 || supplied.length >= parameters.length) return null;
        Map<String, JypeTypeSystemReader.TypeDefInfo> managed = JypeTypeSystemReader.getTypeDefMap(owner);
        if (managed.isEmpty()) return null;
        Map<String, PsiTypeParameter> own = new HashMap<>();
        for (PsiTypeParameter parameter : owner.getTypeParameters()) own.put(parameter.getName(), parameter);
        Set<String> shared = new HashSet<>(own.keySet());
        long unmanaged = Arrays.stream(parameters).filter(p -> !managed.containsKey(p.getName())).count();
        int unshared = (int) Arrays.stream(parameters).filter(p -> !shared.contains(p.getName())).count();
        boolean positional = false;
        if (unshared != supplied.length) {
            if (unmanaged == supplied.length) shared.addAll(managed.keySet());
            unshared = (int) Arrays.stream(parameters).filter(p -> !shared.contains(p.getName())).count();
            if (unshared != supplied.length) {
                for (int i = supplied.length; i < parameters.length; i++) {
                    String name = parameters[i].getName();
                    if (!managed.containsKey(name) || !shared.contains(name)) return null;
                }
                positional = true;
            }
        }
        PsiType[] arguments = new PsiType[parameters.length];
        PsiElement[] locations = new PsiElement[parameters.length];
        PsiElementFactory factory = JavaPsiFacade.getElementFactory(reference.getProject());
        int next = 0;
        for (int i = 0; i < parameters.length; i++) {
            String name = parameters[i].getName();
            boolean explicit = positional ? i < supplied.length : !shared.contains(name);
            if (explicit) {
                PsiTypeElement element = supplied[next++];
                arguments[i] = element.getType();
                locations[i] = element;
            } else {
                PsiTypeParameter parameter = own.get(name);
                if (parameter != null) {
                    arguments[i] = factory.createType(parameter);
                } else {
                    JypeTypeSystemReader.TypeDefInfo definition = managed.get(name);
                    if (definition == null) return null;
                    PsiClass bound = JavaPsiFacade.getInstance(reference.getProject())
                            .findClass(definition.boundFqn(), reference.getResolveScope());
                    if (bound == null) return null;
                    arguments[i] = factory.createType(bound);
                }
                locations[i] = reference.getParameterList();
            }
        }
        PsiSubstitutor substitutor = PsiSubstitutor.EMPTY;
        for (int i = 0; i < parameters.length; i++) substitutor = substitutor.put(parameters[i], arguments[i]);
        List<Problem> problems = new ArrayList<>();
        for (int i = 0; i < parameters.length; i++) {
            for (PsiClassType declared : parameters[i].getExtendsListTypes()) {
                PsiType bound = substitutor.substitute(declared);
                if (bound != null && !TypeConversionUtil.isAssignable(bound, arguments[i])) {
                    problems.add(new Problem(locations[i], "[Jype] Type argument "
                            + arguments[i].getPresentableText() + " does not satisfy bound "
                            + bound.getPresentableText() + " of " + parameters[i].getName()));
                }
            }
        }
        return new Completion(List.copyOf(problems));
    }
}
