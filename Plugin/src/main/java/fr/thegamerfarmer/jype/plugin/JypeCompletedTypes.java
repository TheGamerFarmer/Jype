package fr.thegamerfarmer.jype.plugin;

import com.intellij.psi.*;
import com.intellij.psi.impl.source.PsiClassReferenceType;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.psi.util.TypeConversionUtil;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Replays Java's generic checks on the supertypes javac sees once Jype completes raw parents.
 *
 * <p>IntelliJ keeps {@code class ConcreteNode extends BaseNode} raw, so conversions and calls through
 * that ancestry are "unchecked" for it. Here each raw Jype parent is replaced by its completed type
 * (as {@link JypeContextualTypes} computes it) and conversions are checked <em>without</em> unchecked
 * conversion. Anything that cannot be proven sound returns a negative result.
 */
final class JypeCompletedTypes {
    private JypeCompletedTypes() {}

    /** Result of a call checked against completed types; {@code returnType} is null for constructors. */
    record Call(boolean jype, @Nullable PsiType returnType) {}

    /** {@code right} converts to {@code left} without unchecked conversion once Jype parents are completed. */
    static boolean isAssignable(PsiType left, @Nullable PsiType right) {
        if (right == null) return false;
        if (TypeConversionUtil.isAssignable(left, right, false)) return true;
        if (!(left instanceof PsiClassType target) || target.isRaw() || !(right instanceof PsiClassType source)) return false;
        PsiClass cls = target.resolve();
        PsiClassType completed = cls == null ? null : supertype(source, cls);
        return completed != null && !completed.isRaw() && TypeConversionUtil.isAssignable(left, completed, false);
    }

    /** The {@code target} supertype of {@code type}, following completed Jype parents; null if unrelated. */
    static @Nullable PsiClassType supertype(PsiClassType type, PsiClass target) {
        return supertype(type, target, new HashSet<>());
    }

    private static @Nullable PsiClassType supertype(PsiClassType type, PsiClass target, Set<PsiClass> visited) {
        PsiClassType.ClassResolveResult resolved = type.resolveGenerics();
        PsiClass cls = resolved.getElement();
        if (cls == null) return null;
        if (cls.getManager().areElementsEquivalent(cls, target)) return type;
        if (!visited.add(cls) || !cls.isInheritor(target, true)) return null;
        for (PsiClassType parent : parents(cls)) {
            PsiType substituted = resolved.getSubstitutor().substitute(parent);
            if (substituted instanceof PsiClassType next) {
                PsiClassType result = supertype(next, target, visited);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static List<PsiClassType> parents(PsiClass cls) {
        List<PsiClassType> result = new ArrayList<>();
        List<PsiClassType> declared = new ArrayList<>(List.of(cls.getExtendsListTypes()));
        declared.addAll(List.of(cls.getImplementsListTypes()));
        for (PsiClassType parent : declared) {
            PsiClassType completed = null;
            if (parent instanceof PsiClassReferenceType reference) {
                JypeContextualTypes.Completion completion = JypeContextualTypes.complete(reference.getReference());
                if (completion != null && completion.problems().isEmpty()) completed = completion.type();
            }
            result.add(completed != null ? completed : parent);
        }
        if (declared.isEmpty()) result.addAll(List.of(cls.getSuperTypes()));
        return result;
    }

    /**
     * Checks a call as javac does after completion: receiver substitution through completed parents,
     * inference of method type parameters from completed argument types, and argument conversion
     * without unchecked conversion.
     *
     * @return the checked call, or {@code null} when it cannot be proven sound
     */
    static @Nullable Call check(PsiCallExpression call) {
        PsiMethod method = call.resolveMethod();
        PsiExpressionList list = call.getArgumentList();
        // An implicit default constructor has no PSI method and no argument to check.
        if (method == null && call instanceof PsiNewExpression creation && creation.getAnonymousClass() == null
                && list != null && list.isEmpty() && creation.getType() instanceof PsiClassType type
                && type.resolve() != null) return new Call(false, type);
        if (method == null || list == null) return null;
        PsiClass declaring = method.getContainingClass();
        if (declaring == null) return null;
        PsiSubstitutor substitutor = PsiSubstitutor.EMPTY;
        boolean jype = JypeClassMetadata.read(declaring) != null;
        if (!method.hasModifierProperty(PsiModifier.STATIC)) {
            PsiClassType receiver = receiver(call, declaring);
            PsiClassType completed = receiver == null ? null : supertype(receiver, declaring);
            if (completed == null || declaring.hasTypeParameters() && completed.isRaw()) return null;
            substitutor = completed.resolveGenerics().getSubstitutor();
        }
        PsiExpression[] arguments = list.getExpressions();
        PsiParameter[] parameters = method.getParameterList().getParameters();
        PsiType[] formal = new PsiType[arguments.length];
        for (int i = 0; i < arguments.length; i++) {
            PsiType declared;
            if (i < parameters.length - 1 || !method.isVarArgs() && i < parameters.length) {
                declared = parameters[i].getType();
            } else if (method.isVarArgs() && parameters.length > 0) {
                PsiType last = parameters[parameters.length - 1].getType();
                declared = arguments.length == parameters.length && arguments[i].getType() instanceof PsiArrayType
                        ? ((PsiEllipsisType) last).toArrayType() : ((PsiEllipsisType) last).getComponentType();
            } else return null;
            formal[i] = substitutor.substitute(declared);
            jype |= mentionsJype(formal[i]);
        }
        if (!method.isVarArgs() && arguments.length != parameters.length) return null;
        if (method.hasTypeParameters()) {
            PsiType[] actual = new PsiType[arguments.length];
            for (int i = 0; i < arguments.length; i++) {
                actual[i] = arguments[i].getType();
                if (formal[i] instanceof PsiClassType parameter && actual[i] instanceof PsiClassType argument
                        && parameter.resolve() != null) {
                    PsiClassType completed = supertype(argument, parameter.resolve());
                    if (completed != null) actual[i] = completed;
                }
            }
            PsiSubstitutor inferred = JavaPsiFacade.getInstance(call.getProject()).getResolveHelper()
                    .inferTypeArguments(method.getTypeParameters(), formal, actual, PsiUtil.getLanguageLevel(call));
            for (PsiTypeParameter parameter : method.getTypeParameters()) {
                if (inferred.substitute(parameter) == null) return null;
            }
            substitutor = substitutor.putAll(inferred);
            for (int i = 0; i < formal.length; i++) formal[i] = inferred.substitute(formal[i]);
        }
        for (int i = 0; i < arguments.length; i++) {
            if (formal[i] == null) return null;
            if (arguments[i] instanceof PsiFunctionalExpression functional) {
                if (!functional.isAcceptable(formal[i])) return null;
            } else if (!isAssignable(formal[i], arguments[i].getType())) return null;
        }
        PsiType returnType = method.isConstructor() ? call.getType() : substitutor.substitute(method.getReturnType());
        jype |= mentionsJype(returnType);
        return new Call(jype, returnType);
    }

    /** The type through which an instance member is reached; {@code super}/{@code this} use the enclosing class. */
    private static @Nullable PsiClassType receiver(PsiCallExpression call, PsiClass declaring) {
        if (call instanceof PsiNewExpression creation) {
            return creation.getType() instanceof PsiClassType type ? type : null;
        }
        if (!(call instanceof PsiMethodCallExpression invocation)) return null;
        PsiExpression qualifier = invocation.getMethodExpression().getQualifierExpression();
        if (qualifier instanceof PsiSuperExpression || qualifier instanceof PsiThisExpression) {
            PsiJavaCodeReferenceElement outer = qualifier instanceof PsiSuperExpression parent
                    ? parent.getQualifier() : ((PsiThisExpression) qualifier).getQualifier();
            PsiClass owner = outer != null && outer.resolve() instanceof PsiClass resolved ? resolved
                    : PsiTreeUtil.getParentOfType(call, PsiClass.class);
            return owner == null ? null : ownType(owner);
        }
        if (qualifier != null) return qualifier.getType() instanceof PsiClassType type ? type : null;
        for (PsiClass owner = PsiTreeUtil.getParentOfType(call, PsiClass.class); owner != null;
             owner = PsiTreeUtil.getParentOfType(owner, PsiClass.class)) {
            if (owner.getManager().areElementsEquivalent(owner, declaring) || owner.isInheritor(declaring, true)) {
                return ownType(owner);
            }
        }
        return null;
    }

    /** {@code C<T1, …, Tn>} seen from inside {@code C}: never raw. */
    private static PsiClassType ownType(PsiClass owner) {
        PsiElementFactory factory = JavaPsiFacade.getElementFactory(owner.getProject());
        return factory.createType(owner, Arrays.stream(owner.getTypeParameters())
                .map(factory::createType).toArray(PsiType[]::new));
    }

    /** Whether a type, or one of its arguments, is a Jype-managed class. */
    static boolean mentionsJype(@Nullable PsiType type) {
        if (type instanceof PsiArrayType array) return mentionsJype(array.getComponentType());
        if (type instanceof PsiWildcardType wildcard) return mentionsJype(wildcard.getBound());
        if (!(type instanceof PsiClassType cls)) return false;
        PsiClass resolved = cls.resolve();
        if (resolved != null && !(resolved instanceof PsiTypeParameter)
                && (JypeTypeSystemReader.isVirtualClass(resolved) || JypeClassMetadata.read(resolved) != null)) return true;
        for (PsiType argument : cls.getParameters()) {
            if (mentionsJype(argument)) return true;
        }
        return false;
    }
}
