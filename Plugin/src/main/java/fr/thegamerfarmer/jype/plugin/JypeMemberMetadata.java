package fr.thegamerfarmer.jype.plugin;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.psi.*;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/** Retains source type names and distinguishes business members from technical helpers. */
final class JypeMemberMetadata {
    private static final String ANNOTATION = "fr.thegamerfarmer.jype.annotations.JypeMember";
    private static final Logger LOG = Logger.getInstance(JypeMemberMetadata.class);

    record Data(String sourceType, Set<String> declared, boolean generated) {}

    private JypeMemberMetadata() {}

    static @Nullable Data read(PsiModifierListOwner member) {
        return CachedValuesManager.getCachedValue(member, () ->
                CachedValueProvider.Result.create(readUncached(member), PsiModificationTracker.MODIFICATION_COUNT,
                        com.intellij.openapi.roots.ProjectRootModificationTracker.getInstance(member.getProject())));
    }

    private static @Nullable Data readUncached(PsiModifierListOwner member) {
        PsiAnnotation annotation = member.getAnnotation(ANNOTATION);
        if (annotation == null) return null;
        Object version = constant(annotation.findAttributeValue("version"));
        Object source = constant(annotation.findAttributeValue("sourceType"));
        Object generated = constant(annotation.findAttributeValue("generated"));
        PsiAnnotationMemberValue names = annotation.findAttributeValue("declaredParameters");
        Set<String> declared = new LinkedHashSet<>();
        boolean valid = names != null;
        if (names != null) {
            PsiAnnotationMemberValue[] values = names instanceof PsiArrayInitializerMemberValue array
                    ? array.getInitializers() : new PsiAnnotationMemberValue[]{names};
            for (PsiAnnotationMemberValue value : values) {
                if (!(constant(value) instanceof String name)
                        || !PsiNameHelper.getInstance(member.getProject()).isIdentifier(name)
                        || !declared.add(name)) {
                    valid = false;
                    break;
                }
            }
        }
        if (member instanceof PsiMethod method) {
            Set<String> parameters = new HashSet<>();
            for (PsiTypeParameter parameter : method.getTypeParameters()) parameters.add(parameter.getName());
            valid &= parameters.containsAll(declared);
        } else valid &= declared.isEmpty();
        if (!Integer.valueOf(1).equals(version) || !(source instanceof String type)
                || !(generated instanceof Boolean technical) || !valid) {
            LOG.warn("Unsupported or invalid Jype member metadata on " + member);
            return null;
        }
        return new Data(type, Set.copyOf(declared), technical);
    }

    static boolean isTechnical(PsiElement element) {
        if (!(element instanceof PsiModifierListOwner member)) return false;
        Data data = read(member);
        return data != null && data.generated();
    }

    private static @Nullable Object constant(PsiAnnotationMemberValue value) {
        return value == null ? null : JavaPsiFacade.getInstance(value.getProject())
                .getConstantEvaluationHelper().computeConstantExpression(value);
    }
}
