package fr.thegamerfarmer.jype.plugin;

import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.util.ArrayUtil;
import com.intellij.util.Processor;
import com.intellij.util.indexing.IdFilter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Adds virtual alias names to Java completion and short-name lookup.
 *
 * <p>The project-scoped extension owns no independent cache: the reader supplies canonical,
 * modification-tracked PSI under the caller's read action. Only class declarations are indexed;
 * methods and fields remain the responsibility of ordinary Java indices.
 */
public final class JypeShortNamesCache extends PsiShortNamesCache {
    private final Project project;

    /**
     * Binds completion and short-name lookup to one project's declaration cache.
     *
     * @param project project whose visible type-system declarations are exposed
     */
    public JypeShortNamesCache(Project project) {
        this.project = project;
    }

    /**
     * @param name exact, case-sensitive alias name
     * @param scope requesting module's visibility boundary
     * @return canonical virtual classes for all matching visible declarations
     */
    @Override
    public PsiClass @NotNull [] getClassesByName(@NotNull String name, @NotNull GlobalSearchScope scope) {
        List<PsiClass> classes = new ArrayList<>();
        for (JypeTypeSystemReader.Result system : JypeTypeSystemReader.getTypeSystems(project, scope)) {
            for (JypeTypeSystemReader.TypeDefInfo def : system.typeDefs()) {
                if (name.equals(def.name())) {
                    classes.add(JypeTypeSystemReader.getOrCreateVirtualClass(project, system, def, scope));
                }
            }
        }
        return classes.toArray(PsiClass.EMPTY_ARRAY);
    }

    /**
     * Enumerates completion names without materializing virtual classes.
     *
     * @return distinct alias names across the project; empty while indices are unavailable
     */
    @Override
    public String @NotNull [] getAllClassNames() {
        return JypeTypeSystemReader.getTypeSystems(project, GlobalSearchScope.allScope(project)).stream()
                .flatMap(system -> system.typeDefs().stream()).map(JypeTypeSystemReader.TypeDefInfo::name)
                .distinct().toArray(String[]::new);
    }

    /**
     * @param name exact alias name
     * @param processor consumer allowed to stop iteration
     * @param scope requesting visibility boundary
     * @param filter physical index-ID filter, not applicable to synthetic PSI classes
     * @return {@code false} if the consumer stops, otherwise {@code true}
     */
    @Override
    public boolean processClassesWithName(@NotNull String name, @NotNull Processor<? super PsiClass> processor,
                                         @NotNull GlobalSearchScope scope, @Nullable IdFilter filter) {
        for (PsiClass cls : getClassesByName(name, scope)) {
            if (!processor.process(cls)) return false;
        }
        return true;
    }

    /**
     * @param name method name, intentionally not indexed by this class-only extension
     * @param scope requested method scope
     * @return no additional methods
     */
    @Override
    public PsiMethod @NotNull [] getMethodsByName(@NotNull String name, @NotNull GlobalSearchScope scope) {
        return PsiMethod.EMPTY_ARRAY;
    }

    /**
     * @param name method name, intentionally not indexed here
     * @param scope requested method scope
     * @param max result threshold, immaterial because there are no indexed methods
     * @return no additional methods
     */
    @Override
    public PsiMethod @NotNull [] getMethodsByNameIfNotMoreThan(@NotNull String name,
                                                             @NotNull GlobalSearchScope scope, int max) {
        return PsiMethod.EMPTY_ARRAY;
    }

    /**
     * @param name requested method name
     * @param scope requested method scope
     * @param processor consumer, never invoked because aliases introduce no indexed methods
     * @return {@code true}, indicating enumeration completed without interruption
     */
    @Override
    public boolean processMethodsWithName(@NotNull String name, @NotNull GlobalSearchScope scope,
                                           @NotNull Processor<? super PsiMethod> processor) {
        return true;
    }

    /**
     * @param name field name, intentionally not indexed by this class-only extension
     * @param scope requested field scope
     * @return no additional fields
     */
    @Override
    public PsiField @NotNull [] getFieldsByName(@NotNull String name, @NotNull GlobalSearchScope scope) {
        return PsiField.EMPTY_ARRAY;
    }

    /** @return no completion names; this extension indexes classes, not their methods */
    @Override
    public String @NotNull [] getAllMethodNames() {
        return ArrayUtil.EMPTY_STRING_ARRAY;
    }

    /** @return no completion names; this extension indexes classes, not their fields */
    @Override
    public String @NotNull [] getAllFieldNames() {
        return ArrayUtil.EMPTY_STRING_ARRAY;
    }

    /**
     * @param name requested field name
     * @param processor consumer, never invoked because no fields are indexed here
     * @param scope requested field scope
     * @param filter physical index-ID restriction, irrelevant to this empty enumeration
     * @return {@code true}, indicating enumeration completed without interruption
     */
    @Override
    public boolean processFieldsWithName(@NotNull String name, @NotNull Processor<? super PsiField> processor,
                                         @NotNull GlobalSearchScope scope, @Nullable IdFilter filter) {
        return true;
    }

    /**
     * @param name field name, intentionally not indexed here
     * @param scope requested field scope
     * @param max result threshold, immaterial because there are no indexed fields
     * @return no additional fields
     */
    @Override
    public PsiField @NotNull [] getFieldsByNameIfNotMoreThan(@NotNull String name,
                                                           @NotNull GlobalSearchScope scope, int max) {
        return PsiField.EMPTY_ARRAY;
    }
}
