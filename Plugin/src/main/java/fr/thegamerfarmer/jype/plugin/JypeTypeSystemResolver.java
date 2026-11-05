package fr.thegamerfarmer.jype.plugin;

import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.search.GlobalSearchScope;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static fr.thegamerfarmer.jype.plugin.JypeAnnotationSyntax.*;
import static fr.thegamerfarmer.jype.plugin.JypeTypeSystemReader.*;

/**
 * Resolves inheritance and overrides against a fixed, scope-filtered annotation snapshot.
 *
 * <p>Instances belong to a single read operation. The thread-local guard spans nested resolver
 * instances because bound resolution can re-enter IDE lookups; it is always released on exit.
 * Invalid declarations produce diagnostics instead of partial type definitions.
 */
final class JypeTypeSystemResolver {
    static final String MISSING_INHERIT = "Overrides require non-empty inherit";
    private static final ThreadLocal<Set<PsiAnnotation>> RESOLVING = ThreadLocal.withInitial(HashSet::new);
    private final List<PsiAnnotation> annotations;
    private final GlobalSearchScope scope;

    /**
     * @param annotations indexed candidates from the current project cache generation
     * @param scope requesting module's visibility boundary, also applied to inherited systems
     */
    JypeTypeSystemResolver(List<PsiAnnotation> annotations, GlobalSearchScope scope) {
        this.annotations = annotations;
        this.scope = scope;
    }

    /**
     * Resolves one declaration, preserving inherited definition order and applying named overrides.
     *
     * @param annotation canonical Jype annotation, accessed under a read action
     * @param pkg package owning the declaration
     * @return complete definitions or a diagnostic, including cyclic inheritance
     */
    Result resolve(PsiAnnotation annotation, String pkg) {
        Set<PsiAnnotation> resolving = RESOLVING.get();
        if (!resolving.add(annotation)) return invalid(pkg, annotation, "Cyclic @TypeSystem inheritance");
        try {
            return resolveAttributes(annotation, pkg);
        } finally {
            resolving.remove(annotation);
            if (resolving.isEmpty()) RESOLVING.remove();
        }
    }

    /** Validates mutually exclusive attributes before merging inherited definitions. */
    private Result resolveAttributes(PsiAnnotation annotation, String pkg) {
        Map<String, TypeDefInfo> definitions = new LinkedHashMap<>();
        String parent = stringValue(annotation.findDeclaredAttributeValue("inherit"));
        if (annotation.findDeclaredAttributeValue("overrides") != null
                && (parent == null || parent.isEmpty())) {
            return invalid(pkg, annotation, MISSING_INHERIT);
        }
        Definitions values = readDefinitions(annotation.findDeclaredAttributeValue("value"));
        Definitions overrides = readDefinitions(annotation.findDeclaredAttributeValue("overrides"));
        if (values.error() != null) return invalid(pkg, annotation, values.error());
        if (overrides.error() != null) return invalid(pkg, annotation, overrides.error());
        if (parent != null && !parent.isEmpty()) {
            if (!values.values().isEmpty()) return invalid(pkg, annotation, "Do not combine value and inherit");
            Result inherited = findExactSystem(parent);
            if (inherited == null) return invalid(pkg, annotation, "Cannot find inherited @TypeSystem: " + parent);
            if (inherited.error() != null) return invalid(pkg, annotation, inherited.error());
            inherited.typeDefs().forEach(def -> definitions.put(def.name(), def));
            for (TypeDefInfo override : overrides.values()) {
                if (!definitions.containsKey(override.name())) {
                    return invalid(pkg, annotation, "Unknown @TypeSystem override: " + override.name());
                }
                definitions.put(override.name(), override);
            }
        } else {
            for (TypeDefInfo def : values.values()) definitions.put(def.name(), def);
        }
        return new Result(pkg, List.copyOf(definitions.values()), annotation, null);
    }

    /** Resolves exact inherited packages and rejects duplicate declarations in the same scope. */
    private Result findExactSystem(String pkg) {
        Result found = null;
        for (PsiAnnotation annotation : annotations) {
            PsiFile file = annotation.getContainingFile();
            if (file instanceof PsiJavaFile javaFile && pkg.equals(javaFile.getPackageName())
                    && file.getVirtualFile() != null && scope.contains(file.getVirtualFile())
                    && hasAnnotationName(annotation, SYSTEM)) {
                if (found != null) return invalid(pkg, annotation,
                        "Multiple @TypeSystem declarations in resolve scope: " + pkg);
                found = resolve(annotation, pkg);
            }
        }
        return found;
    }

    /** Creates a failure result without exposing partially resolved aliases. */
    static Result invalid(String pkg, PsiAnnotation annotation, String error) {
        return new Result(pkg, List.of(), annotation, error);
    }
}
