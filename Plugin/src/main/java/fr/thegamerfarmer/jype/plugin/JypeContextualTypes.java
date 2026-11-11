package fr.thegamerfarmer.jype.plugin;

import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.util.RecursionManager;
import com.intellij.psi.*;
import com.intellij.psi.impl.source.PsiExtensibleClass;
import com.intellij.psi.impl.source.PsiImmediateClassType;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.TypeConversionUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/** Completes library types in the caller's scope, retaining real Java substitutions. */
final class JypeContextualTypes {
    record Completion(PsiClassType type, List<JypeInheritanceArguments.Problem> problems) {}

    private JypeContextualTypes() {}

    /** Completes a reference outside type inference, with Java's full resolution. */
    static @Nullable Completion complete(PsiJavaCodeReferenceElement reference) {
        if (DumbService.isDumb(reference.getProject())) return null;
        return RecursionManager.doPreventingRecursion(reference, false,
                () -> reference.resolve() instanceof PsiClass target ? compute(reference, target) : null);
    }

    /**
     * Resolves a type reference during inference without walking the enclosing classes' members.
     *
     * <p>Inference runs inside {@code PsiTypeElement.getType()}, which the platform never makes
     * resolve. A full resolution walks the enclosing classes and asks augment providers for their
     * inner classes; Lombok answers by reading every method's return type, which infers and
     * resolves again, once per method, until the stack overflows. This lookup follows Java's order
     * for the declarations that cannot depend on augments: type parameters, physical member classes
     * of enclosing classes, single-type and static imports, the package, on-demand imports and
     * {@code java.lang}. Names it cannot place (inherited or generated member classes) are not
     * completed.
     */
    private static @Nullable PsiClass lookup(PsiJavaCodeReferenceElement reference) {
        String name = reference.getReferenceName();
        if (name == null) return null;
        JavaPsiFacade facade = JavaPsiFacade.getInstance(reference.getProject());
        var scope = reference.getResolveScope();
        if (reference.getQualifier() instanceof PsiJavaCodeReferenceElement qualifier) {
            String qualifierText = qualifier.getText().replaceAll("<[^<>]*>", "").replaceAll("\\s", "");
            PsiClass direct = facade.findClass(qualifierText + "." + name, scope);
            if (direct != null) return direct;
            PsiClass outer = lookup(qualifier);
            return outer == null ? null : ownMember(outer, name);
        }
        if (lexicalParameters(reference).containsKey(name)) return null;
        for (PsiClass cls = PsiTreeUtil.getContextOfType(reference, PsiClass.class, false); cls != null;
             cls = PsiTreeUtil.getContextOfType(cls, PsiClass.class, true)) {
            if (name.equals(cls.getName()) && !(cls instanceof PsiAnonymousClass)) return cls;
            PsiClass member = ownMember(cls, name);
            if (member != null) return member;
        }
        if (!(reference.getContainingFile() instanceof PsiJavaFile file)) return null;
        PsiImportList imports = file.getImportList();
        if (imports != null) {
            PsiImportStatement single = imports.findSingleClassImportStatement(name);
            if (single != null && single.getQualifiedName() != null) return facade.findClass(single.getQualifiedName(), scope);
            for (PsiImportStaticStatement imported : imports.getImportStaticStatements()) {
                if (imported.isOnDemand() || !name.equals(imported.getReferenceName())) continue;
                PsiJavaCodeReferenceElement owner = imported.getImportReference();
                if (owner == null || !(owner.getQualifier() instanceof PsiJavaCodeReferenceElement type)) continue;
                PsiClass member = facade.findClass(type.getText().replaceAll("\\s", "") + "." + name, scope);
                if (member != null) return member;
            }
        }
        String pkg = file.getPackageName();
        PsiClass local = facade.findClass(pkg.isEmpty() ? name : pkg + "." + name, scope);
        if (local != null) return local;
        if (imports != null) {
            PsiClass found = null;
            for (PsiImportStatementBase imported : imports.getAllImportStatements()) {
                if (!imported.isOnDemand() || imported.getImportReference() == null) continue;
                PsiClass candidate = facade.findClass(
                        imported.getImportReference().getText().replaceAll("\\s", "") + "." + name, scope);
                if (candidate == null || candidate.equals(found)) continue;
                if (found != null) return null;
                found = candidate;
            }
            if (found != null) return found;
        }
        return facade.findClass("java.lang." + name, scope);
    }

    /** Declared member classes only: augmented ones would call the augment providers back. */
    private static @Nullable PsiClass ownMember(PsiClass cls, String name) {
        PsiClass[] members = cls instanceof PsiExtensibleClass extensible
                ? extensible.getOwnInnerClasses().toArray(PsiClass.EMPTY_ARRAY)
                : cls instanceof PsiCompiledElement ? cls.getInnerClasses() : PsiClass.EMPTY_ARRAY;
        for (PsiClass member : members) if (name.equals(member.getName())) return member;
        return null;
    }

    private static @Nullable Completion compute(PsiJavaCodeReferenceElement reference, PsiClass target) {
        if (target instanceof PsiTypeParameter || JypeTypeSystemReader.isVirtualClass(target)) return null;
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
                argument = binding(reference, system, definitions.get(name), factory);
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

    /**
     * Like the engine, an alias bound to a class without type parameters is that class itself
     * (for example {@code ENTITY} is {@code ConcreteNode}); only template bounds remain virtual aliases.
     * A class naming aliases itself is a template for the engine, so its aliases stay virtual, unless
     * it is the binding of an alias in a specialized system: the engine keeps such a class a binding.
     */
    private static PsiType binding(PsiJavaCodeReferenceElement reference, JypeTypeSystemReader.Result system,
                                   JypeTypeSystemReader.TypeDefInfo definition, PsiElementFactory factory) {
        PsiClass bound = JavaPsiFacade.getInstance(reference.getProject())
                .findClass(definition.boundFqn(), reference.getResolveScope());
        if (bound != null && !JypeTypeSystemReader.isVirtualClass(bound) && !bound.hasTypeParameters()
                && !namesAliases(reference, system)) {
            return factory.createType(bound);
        }
        return factory.createType(JypeTypeSystemReader.getOrCreateVirtualClass(
                reference.getProject(), system, definition, reference.getResolveScope()));
    }

    private static boolean namesAliases(PsiElement context, JypeTypeSystemReader.Result system) {
        Set<String> aliases = new HashSet<>();
        system.typeDefs().forEach(definition -> aliases.add(definition.name()));
        for (PsiClass cls = PsiTreeUtil.getContextOfType(context, PsiClass.class, true); cls != null;
             cls = PsiTreeUtil.getContextOfType(cls, PsiClass.class, true)) {
            if (JypeTypeSystemReader.isSpecializedBinding(cls, system)) continue;
            PsiClass owner = cls;
            Set<String> names = CachedValuesManager.getCachedValue(owner, () -> CachedValueProvider.Result.create(
                    typeReferenceNames(owner), PsiModificationTracker.MODIFICATION_COUNT));
            if (!Collections.disjoint(names, aliases)) return true;
        }
        return false;
    }

    /** Unqualified type names written in a class; expressions such as a field named {@code ENTITY} are ignored. */
    private static Set<String> typeReferenceNames(PsiClass cls) {
        Set<String> names = new HashSet<>();
        cls.accept(new JavaRecursiveElementWalkingVisitor() {
            @Override
            public void visitReferenceElement(@NotNull PsiJavaCodeReferenceElement reference) {
                if (!(reference instanceof PsiReferenceExpression) && !reference.isQualified()
                        && reference.getReferenceName() != null) names.add(reference.getReferenceName());
                super.visitReferenceElement(reference);
            }
        });
        return Set.copyOf(names);
    }

    private static Map<String, PsiTypeParameter> lexicalParameters(PsiElement context) {
        Map<String, PsiTypeParameter> parameters = new HashMap<>();
        for (PsiElement owner = context; owner != null; owner = owner.getContext()) {
            if (owner instanceof PsiTypeParameterListOwner generic) {
                for (PsiTypeParameter parameter : generic.getTypeParameters()) {
                    parameters.putIfAbsent(parameter.getName(), parameter);
                }
            }
            if (staticContext(owner)) break;
        }
        return parameters;
    }

    /**
     * Whether a declaration starts a static context. Only classes use implicit modifiers: for a
     * parameter or variable, the platform derives them from the declared type (and augment providers
     * may read it), which would infer this very type element again.
     */
    private static boolean staticContext(PsiElement owner) {
        if (owner instanceof PsiClass cls) return cls.hasModifierProperty(PsiModifier.STATIC);
        if (!(owner instanceof PsiMethod || owner instanceof PsiField || owner instanceof PsiClassInitializer)) return false;
        PsiModifierList modifiers = ((PsiModifierListOwner) owner).getModifierList();
        if (modifiers != null && modifiers.hasExplicitModifier(PsiModifier.STATIC)) return true;
        return owner instanceof PsiField field && field.getContainingClass() != null
                && field.getContainingClass().isInterface();
    }

    /**
     * Supplies the completed type of an explicit type element. It never runs a full resolution
     * (see {@link #lookup}), so it cannot re-enter the augment providers of the enclosing classes.
     */
    static @Nullable PsiType infer(PsiTypeElement element) {
        if (DumbService.isDumb(element.getProject())) return null;
        // Safety net: an inference that needs its own type element again yields Java's type instead
        // of recursing until the stack overflows; the platform does not cache that partial result.
        return RecursionManager.doPreventingRecursion(element, false, () -> inferOnce(element));
    }

    private static @Nullable PsiType inferOnce(PsiTypeElement element) {
        PsiJavaFile file = PsiTreeUtil.getContextOfType(element, PsiJavaFile.class, false);
        if (file == null || file.getName().startsWith("package-info.")) return null;
        // Arrays and wildcards delegate to their component type elements in Java's parser.
        if (PsiTreeUtil.getChildOfType(element, PsiTypeElement.class) != null) return null;
        PsiJavaCodeReferenceElement reference = element.getInnermostComponentReferenceElement();
        if (reference == null) return null;
        PsiClass target = lookup(reference);
        if (target == null || target instanceof PsiTypeParameter || JypeTypeSystemReader.isVirtualClass(target)) return null;
        JypeClassMetadata.Data metadata = JypeClassMetadata.read(target);
        if (metadata == null || metadata.managed().isEmpty()) return null;
        Completion completion = RecursionManager.doPreventingRecursion(reference, false, () -> compute(reference, target));
        if (completion != null) return annotated(completion.type(), element);
        PsiTypeParameter[] parameters = target.getTypeParameters();
        PsiType[] supplied = reference.getTypeParameters();
        if (supplied.length != parameters.length) return null;
        PsiSubstitutor substitutor = PsiSubstitutor.EMPTY;
        for (int i = 0; i < parameters.length; i++) substitutor = substitutor.put(parameters[i], supplied[i]);
        return annotated(new PublicType(target, substitutor, metadata.managed()), element);
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
