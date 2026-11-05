package fr.thegamerfarmer.jype;

import fr.thegamerfarmer.jype.annotations.TypeDef;
import fr.thegamerfarmer.jype.annotations.TypeSystem;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.element.PackageElement;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeKind;
import javax.tools.Diagnostic;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Authoritatively validates and resolves package annotations after Enter.
 *
 * <p>Unlike the pre-Enter metadata reader, this pass resolves actual declared TypeMirrors
 * and reports annotation errors through javac. Overrides replace named associations;
 * they are not required to be subtypes of the replaced bound. Java still checks every
 * resulting type application and recursive bound.
 */
class TypeSystemResolver {

    private final ProcessingEnvironment env;

    /** Binds resolution and diagnostics to the current annotation-processing task. */
    TypeSystemResolver(ProcessingEnvironment env) {
        this.env = env;
    }

    /**
     * Resolves a package and its transitive parents in declaration order.
     *
     * @return the complete mapping, or {@code null} after a diagnostic on invalid input
     */
    @Nullable Map<String, TypeMirror> resolve(@NotNull Element pkgElement) {
        if (!(pkgElement instanceof PackageElement pkg)) {
            reportError("[Jype] @TypeSystem must annotate a package.", pkgElement);
            return null;
        }
        return resolve(pkg, new LinkedHashSet<>());
    }

    @Nullable
    private Map<String, TypeMirror> resolve(PackageElement pkg, Set<String> resolving) {
        String packageName = pkg.getQualifiedName().toString();
        if (!resolving.add(packageName)) {
            reportError("[Jype] Circular @TypeSystem inheritance involving package \""
                    + packageName + "\".", pkg);
            return null;
        }

        TypeSystem ts = pkg.getAnnotation(TypeSystem.class);
        if (ts == null) {
            reportError("[Jype] No @TypeSystem found on package \"" + packageName + "\".", pkg);
            resolving.remove(packageName);
            return null;
        }

        Map<String, TypeMirror> result = new LinkedHashMap<>();
        String parentPackage = ts.inherit();
        boolean declaresOverrides = pkg.getAnnotationMirrors().stream()
                .filter(annotation -> annotation.getAnnotationType().toString().equals(TypeSystem.class.getName()))
                .anyMatch(annotation -> annotation.getElementValues().keySet().stream()
                        .anyMatch(attribute -> attribute.getSimpleName().contentEquals("overrides")));
        if (parentPackage.isEmpty() && declaresOverrides) {
            reportError("[Jype] @TypeSystem overrides require non-empty inherit, even when overrides is empty.", pkg);
            resolving.remove(packageName);
            return null;
        }
        if (!parentPackage.isEmpty() && ts.value().length > 0) {
            reportError("[Jype] @TypeSystem must use either value, or inherit with optional overrides; "
                    + "definitions must not be silently ignored.", pkg);
            resolving.remove(packageName);
            return null;
        }
        if (!parentPackage.isEmpty()) {
            PackageElement parent = env.getElementUtils().getPackageElement(parentPackage);
            if (parent == null) {
                reportError("[Jype] Parent package not found: \"" + parentPackage + "\".", pkg);
                resolving.remove(packageName);
                return null;
            }
            Map<String, TypeMirror> inherited = resolve(parent, resolving);
            if (inherited == null) {
                resolving.remove(packageName);
                return null;
            }
            result.putAll(inherited);
            if (!addTypeDefs(ts.overrides(), result, true, pkg)) {
                resolving.remove(packageName);
                return null;
            }
        } else if (!addTypeDefs(ts.value(), result, false, pkg)) {
            resolving.remove(packageName);
            return null;
        }

        resolving.remove(packageName);
        return result;
    }

    private boolean addTypeDefs(TypeDef[] defs, Map<String, TypeMirror> result,
                                boolean overrides, Element diagnosticElement) {
        boolean valid = true;
        Set<String> namesInThisAnnotation = new LinkedHashSet<>();
        for (TypeDef def : defs) {
            String name = def.name();
            if (name.isEmpty()) {
                reportError("[Jype] @TypeDef name must not be empty.", diagnosticElement);
                valid = false;
                continue;
            }
            if (!javax.lang.model.SourceVersion.isIdentifier(name)
                    || javax.lang.model.SourceVersion.isKeyword(name)) {
                reportError("[Jype] @TypeDef name \"" + name
                        + "\" must be a Java type-parameter identifier, without whitespace.", diagnosticElement);
                valid = false;
                continue;
            }
            if (!namesInThisAnnotation.add(name)) {
                reportError("[Jype] Duplicate @TypeDef name \"" + name + "\".", diagnosticElement);
                valid = false;
                continue;
            }
            if (overrides && !result.containsKey(name)) {
                reportError("[Jype] @TypeDef override \"" + name
                        + "\" does not exist in the inherited @TypeSystem.", diagnosticElement);
                valid = false;
                continue;
            }

            TypeMirror mirror = resolveBound(def, name, diagnosticElement);
            if (mirror == null) {
                valid = false;
                continue;
            }
            result.put(name, mirror);
        }
        return valid;
    }

    @Nullable
    private TypeMirror resolveBound(TypeDef def, String name, Element diagnosticElement) {
        try {
            Class<?> bound = def.bound();
            var typeElement = env.getElementUtils().getTypeElement(bound.getCanonicalName());
            if (typeElement == null) {
                reportError("[Jype] Bound class \"" + bound.getCanonicalName()
                        + "\" for @TypeDef \"" + name + "\" cannot be resolved.", diagnosticElement);
                return null;
            }
            return typeElement.asType();
        } catch (MirroredTypeException e) {
            TypeMirror mirror = e.getTypeMirror();
            if (mirror.getKind() != TypeKind.DECLARED) {
                reportError("[Jype] Bound for @TypeDef \"" + name
                        + "\" must be a resolvable declared class or interface.", diagnosticElement);
                return null;
            }
            return mirror;
        }
    }

    private void reportError(String message, Element element) {
        env.getMessager().printMessage(Diagnostic.Kind.ERROR, message, element);
    }
}
