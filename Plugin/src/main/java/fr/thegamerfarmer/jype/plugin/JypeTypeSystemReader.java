package fr.thegamerfarmer.jype.plugin;

import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ProjectRootModificationTracker;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.RecursionManager;
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
    private static final ThreadLocal<Boolean> READING = ThreadLocal.withInitial(() -> false);

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
        String source = "package " + system.pkg() + "; public class " + definition.name()
                + clause + definition.boundFqn() + " {}";
        PsiJavaFile file = (PsiJavaFile) PsiFileFactory.getInstance(project)
                .createFileFromText(definition.name() + ".java", JavaFileType.INSTANCE, source);
        ((com.intellij.psi.impl.source.PsiFileImpl) file)
                .setOriginalFile(system.declaration().getContainingFile());
        PsiClass created = file.getClasses()[0];
        created.putUserData(VIRTUAL, true);
        created.putUserData(DECLARATION, system);
        PsiClass raced = cache.putIfAbsent(key, created);
        return raced != null ? raced : created;
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
