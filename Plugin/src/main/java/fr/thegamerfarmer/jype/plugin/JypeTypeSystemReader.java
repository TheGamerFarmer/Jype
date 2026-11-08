package fr.thegamerfarmer.jype.plugin;

import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.roots.ProjectRootModificationTracker;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.RecursionManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.impl.java.stubs.index.JavaAnnotationIndex;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.CachedValue;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiModificationTracker;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static fr.thegamerfarmer.jype.plugin.JypeAnnotationSyntax.*;

/**
 * Project-owned, scope-aware index of type systems and canonical virtual classes.
 *
 * <p>All methods reading PSI require a read action. Cached generations expire on PSI or project
 * root changes; a virtual class has one identity per declaration/definition within a generation.
 * Scope-specific results never leak between modules. Re-entrant discovery returns no candidates
 * and recursion-tainted results are not cached. Dumb-mode queries avoid index access.
 */
public final class JypeTypeSystemReader {
    private static final Key<CachedValue<State>> STATE = Key.create("jype.typeSystems");
    private static final Key<Boolean> VIRTUAL = Key.create("jype.virtualType");
    private static final Key<Result> DECLARATION = Key.create("jype.virtualDeclaration");
    private static final Key<PsiMethod> MIRRORED = Key.create("jype.mirroredConstructor");
    private static final ThreadLocal<Boolean> READING = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Set<VirtualKey>> BUILDING = ThreadLocal.withInitial(HashSet::new);

    private JypeTypeSystemReader() {}

    /**
     * Validated alias definition, independent of a particular requesting module.
     *
     * @param name Java identifier used as the virtual class name
     * @param boundFqn resolved canonical name of the class or interface bound
     */
    public record TypeDefInfo(@NotNull String name, @NotNull String boundFqn) {}

    /**
     * Resolved declaration and its diagnostic, valid only for the current PSI generation.
     *
     * @param pkg owning package
     * @param typeDefs ordered aliases, empty for an invalid declaration
     * @param declaration annotation retained for navigation and stable virtual-class identity
     * @param error validation diagnostic, or {@code null} on success
     */
    public record Result(@NotNull String pkg, @NotNull List<TypeDefInfo> typeDefs,
                         @NotNull PsiAnnotation declaration, @Nullable String error) {}

    /** Keeps identical alias names from distinct declarations in separate cache entries. */
    private record VirtualKey(PsiAnnotation declaration, TypeDefInfo definition) {}

    /** One project cache generation; maps permit concurrent read-action lookups. */
    private record State(List<PsiAnnotation> annotations, Map<VirtualKey, PsiClass> virtualTypes,
                         Map<GlobalSearchScope, List<Result>> systems) {}

    /** Obtains the annotation snapshot and caches tied to PSI and project-root modification counts. */
    private static State state(Project project) {
        return CachedValuesManager.getManager(project).getCachedValue(project, STATE, () ->
                CachedValueProvider.Result.create(
                        new State(List.copyOf(JavaAnnotationIndex.getInstance()
                                .getAnnotations("TypeSystem", project, GlobalSearchScope.allScope(project))),
                                new ConcurrentHashMap<>(), new ConcurrentHashMap<>()),
                        PsiModificationTracker.MODIFICATION_COUNT,
                        ProjectRootModificationTracker.getInstance(project)), false);
    }

    /**
     * Returns the binding constructor a virtual alias constructor stands for.
     *
     * @param constructor constructor resolved on an alias
     * @return the binding's declared constructor, or {@code null} for other methods
     */
    public static @Nullable PsiMethod mirroredConstructor(PsiMethod constructor) {
        return constructor.getUserData(MIRRORED);
    }

    /**
     * Distinguishes Jype aliases from real Java classes and type parameters.
     *
     * @param cls class returned by Java resolution
     * @return whether Jype created it as a virtual alias rather than a real Java declaration
     */
    public static boolean isVirtualClass(PsiClass cls) {
        return Boolean.TRUE.equals(cls.getUserData(VIRTUAL));
    }

    /**
     * Resolves the current annotation snapshot once per requesting visibility scope.
     *
     * @param project owning project
     * @param scope requesting module's visibility boundary
     * @return immutable declarations visible in scope; empty during indexing, disposal or re-entry
     */
    public static @NotNull List<Result> getTypeSystems(Project project, GlobalSearchScope scope) {
        if (project.isDisposed() || DumbService.isDumb(project) || READING.get()
                || isEvaluatingConstant()) return List.of();
        ProgressManager.checkCanceled();
        State current = state(project);
        List<Result> cached = current.systems().get(scope);
        if (cached != null) return cached;
        READING.set(true);
        try {
            var stamp = RecursionManager.markStack();
            List<Result> resolved = resolveVisibleSystems(current.annotations(), scope);
            if (!stamp.mayCacheNow()) return resolved;
            List<Result> raced = current.systems().putIfAbsent(scope, resolved);
            return raced != null ? raced : resolved;
        } finally {
            READING.remove();
        }
    }

    /** Filters index candidates before resolving; cancellation is checked between declarations. */
    private static List<Result> resolveVisibleSystems(List<PsiAnnotation> annotations, GlobalSearchScope scope) {
        JypeTypeSystemResolver resolver = new JypeTypeSystemResolver(annotations, scope);
        List<Result> results = new ArrayList<>();
        for (PsiAnnotation annotation : annotations) {
            ProgressManager.checkCanceled();
            PsiFile file = annotation.getContainingFile();
            if (file == null || file.getVirtualFile() == null || !scope.contains(file.getVirtualFile())) continue;
            if (!(file instanceof PsiJavaFile javaFile)
                    || !(file.getName().equals("package-info.java") || file.getName().equals("package-info.class"))
                    || !hasAnnotationName(annotation, SYSTEM)) continue;
            Result result = resolver.resolve(annotation, javaFile.getPackageName());
            if (result != null) results.add(result);
        }
        return List.copyOf(results);
    }

    /**
     * Selects the most specific package declaration without falling back from an invalid child.
     *
     * @param project owning project
     * @param pkg package of the requesting declaration
     * @param scope requesting module's visibility boundary
     * @return the nearest ancestor-package type system, an invalid result for duplicate roots,
     *         or {@code null} when no visible declaration governs the package
     */
    public static @Nullable Result findTypeSystem(Project project, String pkg, GlobalSearchScope scope) {
        if (project.isDisposed() || DumbService.isDumb(project)) return null;
        boolean candidate = state(project).annotations().stream().anyMatch(annotation ->
                annotation.getContainingFile() instanceof PsiJavaFile file
                        && (pkg.equals(file.getPackageName()) || pkg.startsWith(file.getPackageName() + ".")));
        if (!candidate) return null;
        Result best = null;
        for (Result result : getTypeSystems(project, scope)) {
            if (!pkg.equals(result.pkg()) && !pkg.startsWith(result.pkg() + ".")) continue;
            if (best == null || result.pkg().length() > best.pkg().length()) best = result;
            else if (result.pkg().equals(best.pkg())) {
                best = JypeTypeSystemResolver.invalid(best.pkg(), best.declaration(),
                        "Multiple @TypeSystem declarations in resolve scope: " + best.pkg());
            }
        }
        return best;
    }

    /**
     * Detects a type system declared by a dependency of the requesting file, using the same
     * scope-filtered snapshot as {@code inherit} completion. A dependency is a library
     * ({@code package-info.class}) or another module; declarations in the file's own module do not
     * count, so a local package-info keeps governing only its own package tree.
     *
     * @param project owning project
     * @param file requesting source file
     * @param scope the file's resolve scope
     * @return whether at least one visible type system comes from outside the file's own module
     */
    public static boolean usesDependencyTypeSystem(Project project, VirtualFile file, GlobalSearchScope scope) {
        ProjectFileIndex index = ProjectFileIndex.getInstance(project);
        Module own = index.getModuleForFile(file);
        for (Result result : getTypeSystems(project, scope)) {
            VirtualFile declaring = result.declaration().getContainingFile().getVirtualFile();
            if (declaring != null && !Objects.equals(index.getModuleForFile(declaring), own)) return true;
        }
        return false;
    }

    /**
     * Recognizes source package annotations without resolving annotation reference identity.
     *
     * @param file source file to examine
     * @return whether it is a source {@code package-info.java} declaring Jype's annotation
     */
    public static boolean isTypeSystemFile(PsiFile file) {
        return JypeTypeOrigins.isTypeSystemFile(file);
    }

    /**
     * Identifies editable alias declaration literals rather than ordinary strings.
     *
     * @param literal candidate source string
     * @return whether it names an alias inside a package-level Jype type-system declaration
     */
    public static boolean isTypeNameLiteral(PsiLiteralExpression literal) {
        return JypeTypeOrigins.isTypeNameLiteral(literal);
    }

    /**
     * Resolves a declaration literal without confusing inherited aliases with local declarations.
     *
     * @param literal candidate {@code TypeDef.name} string
     * @return its canonical virtual class, or {@code null} if invalid or owned by another file
     */
    public static @Nullable PsiClass typeDeclaredBy(PsiLiteralExpression literal) {
        if (!isTypeNameLiteral(literal) || !(literal.getValue() instanceof String name)) return null;
        PsiJavaFile file = (PsiJavaFile) literal.getContainingFile();
        Result system = findTypeSystem(file.getProject(), file.getPackageName(), file.getResolveScope());
        if (system == null || !Objects.equals(system.declaration().getContainingFile().getVirtualFile(),
                file.getVirtualFile())) return null;
        return system.typeDefs().stream().filter(info -> info.name().equals(name)).findFirst()
                .map(info -> getOrCreateVirtualClass(file.getProject(), system, info, file.getResolveScope())).orElse(null);
    }

    /**
     * Locates the source name shared by an inherited alias family.
     *
     * @param type canonical Jype virtual class
     * @return the original name value to rename, following inheritance rather than local overrides,
     *         or {@code null} for non-Jype or unavailable declarations
     */
    public static @Nullable PsiAnnotationMemberValue renameOrigin(PsiClass type) {
        return JypeTypeOrigins.renameOrigin(type, type.getUserData(DECLARATION));
    }

    /**
     * Returns the generation's canonical class, publishing only after potentially re-entrant resolution.
     *
     * @param project owning project
     * @param system validated declaration
     * @param definition alias to materialize
     * @param scope requesting scope; visibility is established by the caller's system lookup,
     *              while identity remains declaration-based across scopes
     * @return synthetic PSI class whose original file points at the declaring package-info
     */
    public static PsiClass getOrCreateVirtualClass(Project project, Result system,
                                                   TypeDefInfo definition, GlobalSearchScope scope) {
        VirtualKey key = new VirtualKey(system.declaration(), definition);
        Map<VirtualKey, PsiClass> cache = state(project).virtualTypes();
        PsiClass existing = cache.get(key);
        if (existing != null) return existing;
        // Resolution must stay outside computeIfAbsent: Java may re-enter this finder.
        PsiClass bound = JavaPsiFacade.getInstance(project)
                .findClass(definition.boundFqn(), system.declaration().getResolveScope());
        String clause = bound != null && bound.isInterface() ? " implements " : " extends ";
        Set<VirtualKey> building = BUILDING.get();
        // A constructor parameter naming this alias re-enters the finder: the nested request gets
        // an uncached class without constructors, enough to resolve the parameter's type.
        boolean reentrant = !building.add(key);
        try {
            String source = "package " + system.pkg() + "; public class " + definition.name()
                    + clause + definition.boundFqn() + templateArguments(project, system, bound)
                    + " {" + (reentrant ? "" : constructors(definition.name(), bound)) + "}";
            PsiJavaFile file = (PsiJavaFile) PsiFileFactory.getInstance(project)
                    .createFileFromText(definition.name() + ".java", JavaFileType.INSTANCE, source);
            ((com.intellij.psi.impl.source.PsiFileImpl) file)
                    .setOriginalFile(system.declaration().getContainingFile());
            PsiClass created = file.getClasses()[0];
            created.putUserData(VIRTUAL, true);
            created.putUserData(DECLARATION, system);
            if (!reentrant) {
                List<PsiMethod> mirrored = mirrorable(bound);
                PsiMethod[] declared = created.getConstructors();
                for (int i = 0; i < declared.length && i < mirrored.size(); i++) {
                    declared[i].putUserData(MIRRORED, mirrored.get(i));
                }
            }
            if (reentrant) return created;
            PsiClass raced = cache.putIfAbsent(key, created);
            return raced != null ? raced : created;
        } finally {
            if (!reentrant) {
                building.remove(key);
                if (building.isEmpty()) BUILDING.remove();
            }
        }
    }

    /**
     * Mirrors the binding's constructors, as the engine instantiates an alias through the
     * constructors of its binding: {@code new LINK(entity)} needs {@code Link(ENTITY)}.
     * Mirrors are public; {@link JypeTypeSystemAnnotator} applies the binding constructor's own
     * access at each creation, which a mirror declared in the alias's package could not express.
     * Interfaces and generic constructors contribute nothing; the implicit default constructor
     * remains when the binding declares none.
     */
    private static String constructors(String name, @Nullable PsiClass bound) {
        StringBuilder result = new StringBuilder();
        for (PsiMethod constructor : mirrorable(bound)) {
            StringJoiner parameters = new StringJoiner(", ");
            StringJoiner arguments = new StringJoiner(", ");
            PsiParameter[] declared = constructor.getParameterList().getParameters();
            for (int i = 0; i < declared.length; i++) {
                parameters.add(declared[i].getType().getCanonicalText() + " p" + i);
                arguments.add("p" + i);
            }
            result.append(" public ").append(name).append("(").append(parameters)
                    .append(") { super(").append(arguments).append("); }");
        }
        return result.toString();
    }

    private static List<PsiMethod> mirrorable(@Nullable PsiClass bound) {
        if (bound == null || bound.isInterface()) return List.of();
        List<PsiMethod> result = new ArrayList<>();
        for (PsiMethod constructor : bound.getConstructors()) {
            if (!constructor.hasTypeParameters()) result.add(constructor);
        }
        return result;
    }

    /**
     * A template bound (for example {@code StoreBase<ENTITY, …, J_STORE, …>}) is extended
     * with the system's own arguments, as the engine binds it, so members of the alias keep their
     * generic types instead of the raw erasure. Parameterless bindings are written as their class,
     * other aliases as their virtual class; a bound naming a non-alias parameter stays raw.
     */
    private static String templateArguments(Project project, Result system, @Nullable PsiClass bound) {
        if (bound == null || !bound.hasTypeParameters()) return "";
        Map<String, TypeDefInfo> definitions = new HashMap<>();
        system.typeDefs().forEach(definition -> definitions.put(definition.name(), definition));
        List<String> arguments = new ArrayList<>();
        for (PsiTypeParameter parameter : bound.getTypeParameters()) {
            TypeDefInfo definition = definitions.get(parameter.getName());
            if (definition == null) return "";
            PsiClass binding = JavaPsiFacade.getInstance(project)
                    .findClass(definition.boundFqn(), system.declaration().getResolveScope());
            arguments.add(binding != null && !isVirtualClass(binding) && !binding.hasTypeParameters()
                    ? definition.boundFqn() : system.pkg() + "." + definition.name());
        }
        return "<" + String.join(", ", arguments) + ">";
    }

    /** Finds the class's package system, then follows its superclass with identity-cycle protection. */
    private static @Nullable Result findTypeSystem(PsiClass cls, Set<PsiClass> visited) {
        if (!visited.add(cls) || isVirtualClass(cls)) return null;
        if (cls.getContainingFile() instanceof PsiJavaFile file) {
            Result direct = findTypeSystem(cls.getProject(), file.getPackageName(), cls.getResolveScope());
            if (direct != null) return direct;
        }
        PsiClass parent = cls.getSuperClass();
        return parent == null ? null : findTypeSystem(parent, visited);
    }

    /**
     * Collects the effective aliases supplied by a class's package or superclass.
     *
     * @param cls real Java class whose package or superclass supplies a type system
     * @return ordered alias map, empty for absent or invalid systems
     */
    public static @NotNull Map<String, TypeDefInfo> getTypeDefMap(@NotNull PsiClass cls) {
        Result result = findTypeSystem(cls, new HashSet<>());
        if (result == null) return Map.of();
        Map<String, TypeDefInfo> definitions = new LinkedHashMap<>();
        result.typeDefs().forEach(def -> definitions.put(def.name(), def));
        return definitions;
    }

    /**
     * Exposes effective alias names for narrowly scoped highlighting decisions.
     *
     * @param cls real Java class to inspect
     * @return names managed by its package or inherited type system
     */
    public static @NotNull Set<String> getManagedNames(@NotNull PsiClass cls) {
        return getTypeDefMap(cls).keySet();
    }

    /**
     * Finds the package that owns a class's effective type-system declaration.
     *
     * @param cls real Java class to inspect
     * @return governing type-system package, or {@code null} if none exists
     */
    public static @Nullable String getTypeSystemRootPackage(@NotNull PsiClass cls) {
        Result result = findTypeSystem(cls, new HashSet<>());
        return result == null ? null : result.pkg();
    }

    /**
     * Checks declaration presence independently of whether that declaration validates.
     *
     * @param cls real Java class to inspect
     * @return whether its package or superclass has a visible declaration, even if invalid
     */
    public static boolean isInTypeSystemPackage(@NotNull PsiClass cls) {
        return findTypeSystem(cls, new HashSet<>()) != null;
    }
}
