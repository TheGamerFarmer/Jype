package fr.thegamerfarmer.jype.plugin;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElementFinder;
import com.intellij.psi.PsiPackage;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Exposes declaration-owned virtual classes to Java qualified-name and package lookup.
 * Platform callbacks run under read access; the shared reader handles indexing, recursion
 * and cache invalidation. The project reference belongs to this project-scoped extension.
 */
public final class JypePsiElementFinder extends PsiElementFinder {
    private final Project project;

    /**
     * Binds Java finder callbacks to the owning project's modification-tracked reader.
     *
     * @param project project whose type-system declarations this finder exposes
     */
    public JypePsiElementFinder(Project project) {
        this.project = project;
    }

    /**
     * Resolves a managed simple name in the nearest visible type-system package.
     *
     * @param name requested fully qualified name
     * @param scope requesting module's visibility boundary
     * @return canonical virtual class, or {@code null} for unqualified or unmanaged names
     */
    @Override
    public @Nullable PsiClass findClass(@NotNull String name, @NotNull GlobalSearchScope scope) {
        int dot = name.lastIndexOf('.');
        if (dot < 0) return null;
        String pkg = name.substring(0, dot);
        String simple = name.substring(dot + 1);
        JypeTypeSystemReader.Result system = JypeTypeSystemReader.findTypeSystem(project, pkg, scope);
        if (system == null) return null;
        for (JypeTypeSystemReader.TypeDefInfo def : system.typeDefs()) {
            if (def.name().equals(simple)) {
                return JypeTypeSystemReader.getOrCreateVirtualClass(project, system, def, scope);
            }
        }
        return null;
    }

    /**
     * Adapts the single-result lookup to the Java finder extension's array contract.
     *
     * @param name requested fully qualified name
     * @param scope requesting visibility boundary
     * @return empty or one-element array sharing identity with {@link #findClass}
     */
    @Override
    public PsiClass @NotNull [] findClasses(@NotNull String name, @NotNull GlobalSearchScope scope) {
        PsiClass result = findClass(name, scope);
        return result == null ? PsiClass.EMPTY_ARRAY : new PsiClass[]{result};
    }

    /**
     * Supplies aliases for Java package enumeration and completion.
     *
     * @param pkg requested package, including descendants governed by an ancestor declaration
     * @param scope requesting visibility boundary
     * @return canonical classes in definition order, or an empty array
     */
    @Override
    public PsiClass @NotNull [] getClasses(@NotNull PsiPackage pkg, @NotNull GlobalSearchScope scope) {
        JypeTypeSystemReader.Result system =
                JypeTypeSystemReader.findTypeSystem(project, pkg.getQualifiedName(), scope);
        if (system == null) return PsiClass.EMPTY_ARRAY;
        return system.typeDefs().stream()
                .map(def -> JypeTypeSystemReader.getOrCreateVirtualClass(project, system, def, scope))
                .toArray(PsiClass[]::new);
    }
}
