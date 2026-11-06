package fr.thegamerfarmer.jype.plugin;

import com.intellij.psi.*;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;

/** Renders the Jype surface from typed PSI, never from a flattened Java signature. */
final class JypePublicPresentation {
    private JypePublicPresentation() {}

    static @Nullable PsiClass owner(PsiElement element) {
        if (element instanceof PsiClass cls && !(cls instanceof PsiTypeParameter)) return cls;
        return element instanceof PsiMember member ? member.getContainingClass() : null;
    }

    static boolean applies(PsiElement element) {
        PsiClass owner = owner(element);
        return owner != null && JypeClassMetadata.read(owner) != null;
    }

    static String type(PsiType type) {
        if (type == null) return "";
        if (type instanceof PsiEllipsisType array) return type(array.getComponentType()) + "...";
        if (type instanceof PsiArrayType array) return type(array.getComponentType()) + "[]";
        if (type instanceof PsiWildcardType wildcard) {
            return wildcard.isBounded() ? "? " + (wildcard.isExtends() ? "extends " : "super ")
                    + type(wildcard.getBound()) : "?";
        }
        if (!(type instanceof PsiClassType classType)) return type.getPresentableText();
        PsiClass cls = classType.resolve();
        if (cls == null || cls instanceof PsiTypeParameter) return type.getPresentableText();
        JypeClassMetadata.Data data = JypeClassMetadata.read(cls);
        PsiType[] supplied = classType.getParameters();
        PsiTypeParameter[] parameters = cls.getTypeParameters();
        List<String> arguments = new ArrayList<>();
        for (int i = 0; i < supplied.length; i++) {
            if (data != null && i < parameters.length && data.managed().contains(parameters[i].getName())) continue;
            arguments.add(type(supplied[i]));
        }
        return cls.getName() + (arguments.isEmpty() ? "" : "<" + String.join(", ", arguments) + ">");
    }

    static String parameters(PsiMethod method) {
        return Arrays.stream(method.getParameterList().getParameters())
                .map(parameter -> type(parameter.getType()) + " "
                        + Objects.requireNonNullElse(parameter.getName(), "arg"))
                .collect(Collectors.joining(", "));
    }

    static String memberType(PsiModifierListOwner member, PsiType actual) {
        return type(JypeSourceMemberType.sourceType(member, actual));
    }

    static String signature(PsiElement element) {
        if (element instanceof PsiClass cls) {
            JypeClassMetadata.Data data = JypeClassMetadata.read(cls);
            String kind = cls.isAnnotationType() ? "@interface" : cls.isInterface() ? "interface"
                    : cls.isEnum() ? "enum" : cls.isRecord() ? "record" : "class";
            StringBuilder signature = new StringBuilder(modifiers(cls)).append(kind).append(' ')
                    .append(cls.getName()).append(typeParameters(cls, data == null ? Set.of() : data.managed()));
            if (!cls.isEnum() && !cls.isRecord()) {
                appendParents(signature, cls.getExtendsListTypes(), " extends ");
                appendParents(signature, cls.getImplementsListTypes(), " implements ");
            }
            return signature.toString();
        }
        if (element instanceof PsiMethod method) {
            String generics = typeParameters(method, injectedMethodParameters(method));
            String result = modifiers(method) + generics;
            if (!generics.isEmpty()) result += " ";
            if (!method.isConstructor()) result += memberType(method, method.getReturnType()) + " ";
            result += method.getName() + "(" + parameters(method) + ")";
            PsiClassType[] exceptions = method.getThrowsList().getReferencedTypes();
            if (exceptions.length > 0) result += " throws " + Arrays.stream(exceptions)
                    .map(JypePublicPresentation::type).collect(Collectors.joining(", "));
            return result;
        }
        if (element instanceof PsiField field) return modifiers(field) + memberType(field, field.getType()) + " " + field.getName();
        return element.getText();
    }

    private static Set<String> injectedMethodParameters(PsiMethod method) {
        JypeMemberMetadata.Data metadata = JypeMemberMetadata.read(method);
        PsiClass owner = method.getContainingClass();
        if (metadata == null || owner == null) return Set.of();
        Set<String> managed = new HashSet<>(JypeTypeSystemReader.getManagedNames(owner));
        managed.removeAll(metadata.declared());
        return managed;
    }

    private static void appendParents(StringBuilder signature, PsiClassType[] parents, String clause) {
        if (parents.length > 0) signature.append(clause).append(Arrays.stream(parents)
                .map(JypePublicPresentation::type).collect(Collectors.joining(", ")));
    }

    static String typeParameters(PsiTypeParameterListOwner owner, Set<String> managed) {
        List<String> parameters = new ArrayList<>();
        for (PsiTypeParameter parameter : owner.getTypeParameters()) {
            if (managed.contains(parameter.getName())) continue;
            String bounds = Arrays.stream(parameter.getExtendsListTypes())
                    .map(JypePublicPresentation::type).collect(Collectors.joining(" & "));
            parameters.add(parameter.getName() + (bounds.isEmpty() ? "" : " extends " + bounds));
        }
        return parameters.isEmpty() ? "" : "<" + String.join(", ", parameters) + ">";
    }

    private static String modifiers(PsiModifierListOwner owner) {
        return Arrays.stream(PsiModifier.MODIFIERS).filter(owner::hasModifierProperty)
                .collect(Collectors.joining(" ", "", " ")).stripLeading();
    }
}
