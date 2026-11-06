package fr.thegamerfarmer.jype.plugin;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.util.RecursionManager;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/** Resolves captured type syntax using binary names, not guesses about missing source imports. */
final class JypeSourceMemberType {
    private static final Logger LOG = Logger.getInstance(JypeSourceMemberType.class);

    private JypeSourceMemberType() {}

    static PsiType sourceType(PsiModifierListOwner member, PsiType actual) {
        if (DumbService.isDumb(member.getProject())) return actual;
        JypeMemberMetadata.Data metadata = JypeMemberMetadata.read(member);
        if (metadata == null || metadata.sourceType().isBlank() || metadata.generated()) return actual;
        PsiType source = RecursionManager.doPreventingRecursion(member, false,
                () -> parse(member, actual, metadata.sourceType()));
        return source == null ? actual : source;
    }

    private static @Nullable PsiType parse(PsiModifierListOwner member, PsiType actual, String source) {
        Map<String, Set<String>> names = new HashMap<>();
        binaryNames(actual, names);
        Set<String> aliases = new HashSet<>();
        PsiClass owner = JypePublicPresentation.owner(member);
        if (owner != null) aliases.addAll(JypeTypeSystemReader.getManagedNames(owner));
        if (member instanceof PsiMethod method) {
            for (PsiTypeParameter parameter : method.getTypeParameters()) aliases.add(parameter.getName());
        }
        StringBuilder qualified = new StringBuilder();
        for (int start = 0; start < source.length();) {
            if (!Character.isJavaIdentifierStart(source.charAt(start))) {
                qualified.append(source.charAt(start++));
                continue;
            }
            int end = start + 1;
            while (end < source.length() && Character.isJavaIdentifierPart(source.charAt(end))) end++;
            String name = source.substring(start, end);
            Set<String> candidates = names.get(name);
            boolean component = start > 0 && source.charAt(start - 1) == '.'
                    || end < source.length() && source.charAt(end) == '.';
            qualified.append(!component && !aliases.contains(name) && candidates != null && candidates.size() == 1
                    ? candidates.iterator().next() : name);
            start = end;
        }
        try {
            PsiTypeElement element = JavaPsiFacade.getElementFactory(member.getProject())
                    .createTypeElementFromText(qualified.toString(), member);
            if (PsiTreeUtil.findChildOfType(element, PsiErrorElement.class) != null) {
                LOG.warn("Invalid Jype source type on " + member + ": " + source);
                return null;
            }
            PsiType type = element.getType();
            if (!resolved(type)) {
                LOG.warn("Unresolvable Jype source type on " + member + ": " + source);
                return null;
            }
            return type;
        } catch (IncorrectOperationException exception) {
            LOG.warn("Invalid Jype source type on " + member + ": " + source, exception);
            return null;
        }
    }

    private static void binaryNames(PsiType type, Map<String, Set<String>> names) {
        if (type instanceof PsiArrayType array) binaryNames(array.getComponentType(), names);
        else if (type instanceof PsiWildcardType wildcard) binaryNames(wildcard.getBound(), names);
        else if (type instanceof PsiClassType classType) {
            PsiClass cls = classType.resolve();
            if (cls != null && !(cls instanceof PsiTypeParameter) && cls.getQualifiedName() != null) {
                names.computeIfAbsent(cls.getName(), ignored -> new HashSet<>()).add(cls.getQualifiedName());
            }
            for (PsiType argument : classType.getParameters()) binaryNames(argument, names);
        }
    }

    private static boolean resolved(PsiType type) {
        if (type instanceof PsiArrayType array) return resolved(array.getComponentType());
        if (type instanceof PsiWildcardType wildcard) return !wildcard.isBounded() || resolved(wildcard.getBound());
        if (type instanceof PsiClassType cls) return cls.resolve() != null && Arrays.stream(cls.getParameters())
                .allMatch(JypeSourceMemberType::resolved);
        return type instanceof PsiPrimitiveType;
    }
}
