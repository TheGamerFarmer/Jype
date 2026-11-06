package fr.thegamerfarmer.jype.plugin;

import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.util.RecursionManager;
import com.intellij.psi.*;
import com.intellij.psi.impl.source.PsiClassReferenceType;
import com.intellij.psi.impl.source.PsiImmediateClassType;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.TypeConversionUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/** Completes library types in the caller's scope, retaining real Java substitutions. */
final class JypeContextualTypes {
    record Completion(PsiClassType type, List<JypeInheritanceArguments.Problem> problems) {}

    private JypeContextualTypes() {}

    static @Nullable Completion complete(PsiJavaCodeReferenceElement reference) {
        if (DumbService.isDumb(reference.getProject())) return null;
        return RecursionManager.doPreventingRecursion(reference, false, () -> compute(reference));
    }

    private static @Nullable Completion compute(PsiJavaCodeReferenceElement reference) {
        if (!(reference.resolve() instanceof PsiClass target) || target instanceof PsiTypeParameter
                || JypeTypeSystemReader.isVirtualClass(target)) return null;
        JypeClassMetadata.Data metadata = JypeClassMetadata.read(target);
        if (metadata == null || metadata.managed().isEmpty()) return null;
        PsiJavaFile file = PsiTreeUtil.getContextOfType(reference, PsiJavaFile.class, false);
        if (file == null) return null;
        JypeTypeSystemReader.Result system = JypeTypeSystemReader.findTypeSystem(
                file.getProject(), file.getPackageName(), reference.getResolveScope());
        if (system == null || system.error() != null) return null;
        Map<String, JypeTypeSystemReader.TypeDefInfo> definitions = new LinkedHashMap<>();
        system.typeDefs().forEach(definition -> definitions.put(definition.name(), definition));
        if (!definitions.keySet().containsAll(metadata.managed())) return null;
        PsiTypeParameter[] parameters = target.getTypeParameters();
        PsiType[] supplied = reference.getTypeParameters();
        if (supplied.length >= parameters.length) return null;
        long ordinary = Arrays.stream(parameters).filter(p -> !metadata.managed().contains(p.getName())).count();
        if (ordinary != supplied.length) return null;
        PsiElementFactory factory = JavaPsiFacade.getElementFactory(reference.getProject());
        PsiSubstitutor substitutor = PsiSubstitutor.EMPTY;
        Map<String, PsiTypeParameter> lexical = lexicalParameters(reference);
        int next = 0;
        List<PsiType> arguments = new ArrayList<>();
        for (PsiTypeParameter parameter : parameters) {
            String name = parameter.getName();
            PsiType argument;
            if (!metadata.managed().contains(name)) {
                argument = supplied[next++];
            } else if (lexical.containsKey(name)) {
                argument = factory.createType(lexical.get(name));
            } else {
                argument = factory.createType(JypeTypeSystemReader.getOrCreateVirtualClass(
                        reference.getProject(), system, definitions.get(name), reference.getResolveScope()));
            }
            substitutor = substitutor.put(parameter, argument);
            arguments.add(argument);
        }
        List<JypeInheritanceArguments.Problem> problems = new ArrayList<>();
        for (int i = 0; i < parameters.length; i++) {
            for (PsiClassType declared : parameters[i].getExtendsListTypes()) {
                PsiType bound = substitutor.substitute(declared);
                if (bound != null && !TypeConversionUtil.isAssignable(bound, arguments.get(i))) {
                    problems.add(new JypeInheritanceArguments.Problem(reference, "[Jype] Type argument "
                            + arguments.get(i).getPresentableText() + " does not satisfy bound "
                            + bound.getPresentableText() + " of " + parameters[i].getName()));
                }
            }
        }
        return new Completion(new PublicType(target, substitutor, metadata.managed()), List.copyOf(problems));
    }

    private static Map<String, PsiTypeParameter> lexicalParameters(PsiElement context) {
        Map<String, PsiTypeParameter> parameters = new HashMap<>();
        for (PsiElement owner = context; owner != null; owner = owner.getContext()) {
            if (owner instanceof PsiTypeParameterListOwner generic) {
                for (PsiTypeParameter parameter : generic.getTypeParameters()) {
                    parameters.putIfAbsent(parameter.getName(), parameter);
                }
            }
            if (owner instanceof PsiModifierListOwner member && member.hasModifierProperty(PsiModifier.STATIC)) break;
        }
        return parameters;
    }

    static @Nullable PsiType infer(PsiTypeElement element) {
        if (DumbService.isDumb(element.getProject())) return null;
        PsiJavaFile file = PsiTreeUtil.getContextOfType(element, PsiJavaFile.class, false);
        if (file == null || file.getName().startsWith("package-info.")) return null;
        // Arrays and wildcards delegate to their component type elements in Java's parser.
        if (PsiTreeUtil.getChildOfType(element, PsiTypeElement.class) != null) return null;
        PsiJavaCodeReferenceElement reference = element.getInnermostComponentReferenceElement();
        if (reference == null) return null;
        Completion completion = complete(reference);
        if (completion != null) return annotated(completion.type(), element);
        PsiClassReferenceType raw = new PsiClassReferenceType(reference, null);
        PsiClass target = raw.resolve();
        if (target == null || target instanceof PsiTypeParameter) return null;
        JypeClassMetadata.Data metadata = JypeClassMetadata.read(target);
        if (metadata == null || metadata.managed().isEmpty()
                || raw.getParameters().length != target.getTypeParameters().length) return null;
        return annotated(new PublicType(target, raw.resolveGenerics().getSubstitutor(), metadata.managed()), element);
    }

    private static PsiType annotated(PsiType type, PsiTypeElement element) {
        List<PsiAnnotation> annotations = PsiTreeUtil.getChildrenOfTypeAsList(element, PsiAnnotation.class);
        return annotations.isEmpty() ? type : type.annotate(
                TypeAnnotationProvider.Static.create(annotations.toArray(PsiAnnotation[]::new)));
    }

    static boolean isPublicType(PsiType type) {
        return type instanceof PublicType;
    }

    /** Presentation changes never change canonical text or the class/substitutor identity. */
    private static final class PublicType extends PsiImmediateClassType {
        private final Set<String> managed;

        private PublicType(PsiClass cls, PsiSubstitutor substitutor, Set<String> managed) {
            super(cls, substitutor);
            this.managed = managed;
        }

        @Override
        public @NotNull String getPresentableText(boolean annotated) {
            PsiClass cls = resolve();
            if (cls == null) return super.getPresentableText(annotated);
            List<String> arguments = new ArrayList<>();
            for (PsiTypeParameter parameter : cls.getTypeParameters()) {
                if (managed.contains(parameter.getName())) continue;
                PsiType argument = resolveGenerics().getSubstitutor().substitute(parameter);
                if (argument == null) return super.getPresentableText(annotated);
                arguments.add(argument.getPresentableText(annotated));
            }
            return cls.getName() + (arguments.isEmpty() ? "" : "<" + String.join(", ", arguments) + ">");
        }
    }
}
