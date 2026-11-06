package fr.thegamerfarmer.jype.plugin;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.psi.*;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/** Reads the public-view contract without loading library classes. */
final class JypeClassMetadata {
    static final String ANNOTATION = "fr.thegamerfarmer.jype.annotations.JypeClass";
    private static final Logger LOG = Logger.getInstance(JypeClassMetadata.class);

    record Data(Set<String> managed, List<String> declared, String typeSystem) {}

    private JypeClassMetadata() {}

    static @Nullable Data read(PsiClass cls) {
        return CachedValuesManager.getCachedValue(cls, () ->
                com.intellij.psi.util.CachedValueProvider.Result.create(readUncached(cls),
                        PsiModificationTracker.MODIFICATION_COUNT,
                        com.intellij.openapi.roots.ProjectRootModificationTracker.getInstance(cls.getProject())));
    }

    private static @Nullable Data readUncached(PsiClass cls) {
        PsiAnnotation annotation = cls.getAnnotation(ANNOTATION);
        if (annotation == null) {
            Set<String> names = JypeTypeSystemReader.getManagedNames(cls);
            Set<String> managed = new LinkedHashSet<>();
            List<String> declared = new ArrayList<>();
            for (PsiTypeParameter parameter : cls.getTypeParameters()) {
                String name = parameter.getName();
                declared.add(name);
                if (names.contains(name)) managed.add(name);
            }
            return managed.isEmpty() ? null : new Data(Set.copyOf(managed), List.copyOf(declared),
                    Objects.requireNonNullElse(JypeTypeSystemReader.getTypeSystemRootPackage(cls), ""));
        }
        Object version = constant(annotation.findAttributeValue("version"));
        List<String> managed = strings(annotation.findAttributeValue("managedParameters"));
        List<String> declared = strings(annotation.findAttributeValue("declaredParameters"));
        Object system = constant(annotation.findAttributeValue("typeSystem"));
        Set<String> parameters = new HashSet<>();
        List<String> managedOrder = new ArrayList<>();
        for (PsiTypeParameter parameter : cls.getTypeParameters()) {
            parameters.add(parameter.getName());
            if (managed != null && managed.contains(parameter.getName())) managedOrder.add(parameter.getName());
        }
        PsiNameHelper names = PsiNameHelper.getInstance(cls.getProject());
        if (!Integer.valueOf(1).equals(version) || managed == null || declared == null
                || !(system instanceof String pkg) || pkg.isBlank()
                || Arrays.stream(pkg.split("\\.", -1)).anyMatch(part -> !names.isIdentifier(part))
                || managed.stream().anyMatch(name -> !names.isIdentifier(name))
                || declared.stream().anyMatch(name -> !names.isIdentifier(name))
                || new HashSet<>(managed).size() != managed.size()
                || new HashSet<>(declared).size() != declared.size()
                || !managedOrder.equals(managed)
                || !parameters.containsAll(managed) || !parameters.containsAll(declared)) {
            LOG.warn("Unsupported or invalid Jype class metadata on " + cls.getQualifiedName());
            return null;
        }
        return new Data(Set.copyOf(managed), List.copyOf(declared), pkg);
    }

    private static @Nullable Object constant(PsiAnnotationMemberValue value) {
        return value == null ? null : JavaPsiFacade.getInstance(value.getProject())
                .getConstantEvaluationHelper().computeConstantExpression(value);
    }

    private static @Nullable List<String> strings(PsiAnnotationMemberValue value) {
        if (value == null) return null;
        PsiAnnotationMemberValue[] values = value instanceof PsiArrayInitializerMemberValue array
                ? array.getInitializers() : new PsiAnnotationMemberValue[]{value};
        List<String> result = new ArrayList<>();
        for (PsiAnnotationMemberValue item : values) {
            if (!(constant(item) instanceof String name) || name.isBlank()) return null;
            result.add(name);
        }
        return List.copyOf(result);
    }
}
