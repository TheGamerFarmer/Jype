package fr.thegamerfarmer.jype.plugin;

import com.intellij.openapi.project.DumbService;
import com.intellij.psi.*;
import org.jetbrains.annotations.Nullable;

import javax.lang.model.SourceVersion;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static fr.thegamerfarmer.jype.plugin.JypeTypeSystemReader.TypeDefInfo;

/**
 * Recognizes Jype annotation syntax and validates declared type definitions.
 *
 * <p>Source annotation identity is checked from imports rather than annotation resolution,
 * which can recursively invoke Jype's element finder. Compiled annotations already provide
 * canonical names. Callers hold a read action; this utility retains no PSI or project state.
 */
final class JypeAnnotationSyntax {
    static final String SYSTEM = "fr.thegamerfarmer.jype.annotations.TypeSystem";
    static final String DEF = "fr.thegamerfarmer.jype.annotations.TypeDef";
    private static final ThreadLocal<Boolean> EVALUATING_CONSTANT = ThreadLocal.withInitial(() -> false);

    /**
     * Indicates when Java constant resolution must not re-enter Jype declaration discovery.
     *
     * @return whether the current thread is evaluating a nonliteral annotation value
     */
    static boolean isEvaluatingConstant() {
        return EVALUATING_CONSTANT.get();
    }

    /**
     * Validation result for a single attribute or array of definitions.
     *
     * @param values ordered definitions, empty when validation fails
     * @param error first validation diagnostic, or {@code null}
     */
    record Definitions(List<TypeDefInfo> values, @Nullable String error) {}

    private JypeAnnotationSyntax() {}

    /**
     * Validates names, duplicate entries and class-literal bounds.
     *
     * @param value declared annotation attribute; absent attributes mean no definitions
     * @return definitions or the first syntax/resolution diagnostic
     */
    static Definitions readDefinitions(@Nullable PsiAnnotationMemberValue value) {
        if (value == null) return new Definitions(List.of(), null);
        if (value instanceof PsiArrayInitializerMemberValue array) return readArray(array);
        if (!(value instanceof PsiAnnotation annotation) || !hasAnnotationName(annotation, DEF)) {
            return new Definitions(List.of(), "Expected a Jype @TypeDef");
        }
        String name = stringValue(annotation.findDeclaredAttributeValue("name"));
        if (name == null || !SourceVersion.isIdentifier(name) || SourceVersion.isKeyword(name)) {
            return new Definitions(List.of(), "@TypeDef name must be a Java identifier");
        }
        PsiAnnotationMemberValue bound = annotation.findDeclaredAttributeValue("bound");
        if (!(bound instanceof PsiClassObjectAccessExpression access)
                || !(access.getOperand().getType() instanceof PsiClassType type)) {
            return new Definitions(List.of(), "@TypeDef bound must be a class or interface");
        }
        PsiClass resolved = annotation instanceof PsiCompiledElement ? type.resolve()
                : JypeSourceBoundResolver.resolve(access);
        if (resolved == null || resolved.getQualifiedName() == null) {
            return new Definitions(List.of(), "Cannot resolve @TypeDef bound for " + name);
        }
        return new Definitions(List.of(new TypeDefInfo(name, resolved.getQualifiedName())), null);
    }

    /** Flattens array syntax while rejecting duplicate names across all entries. */
    private static Definitions readArray(PsiArrayInitializerMemberValue array) {
        List<TypeDefInfo> result = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (PsiAnnotationMemberValue child : array.getInitializers()) {
            Definitions definitions = readDefinitions(child);
            if (definitions.error() != null) return definitions;
            for (TypeDefInfo def : definitions.values()) {
                if (!names.add(def.name())) return new Definitions(List.of(), "Duplicate @TypeDef name: " + def.name());
                result.add(def);
            }
        }
        return new Definitions(result, null);
    }

    /**
     * Checks canonical annotation identity without resolving source annotation references.
     *
     * @param annotation source or compiled annotation
     * @param expected fully qualified annotation name
     * @return whether imports and local shadowing identify the expected annotation unambiguously
     */
    static boolean hasAnnotationName(PsiAnnotation annotation, String expected) {
        if (annotation instanceof PsiCompiledElement) return expected.equals(annotation.getQualifiedName());
        PsiJavaCodeReferenceElement reference = annotation.getNameReferenceElement();
        if (reference == null) return false;
        String text = reference.getText();
        if (text.contains(".")) return expected.equals(text);
        String simple = expected.substring(expected.lastIndexOf('.') + 1);
        if (!simple.equals(text) || !(annotation.getContainingFile() instanceof PsiJavaFile file)) return false;
        PsiImportList imports = file.getImportList();
        if (imports != null) {
            for (PsiImportStatement statement : imports.getImportStatements()) {
                if (!statement.isOnDemand() && statement.getQualifiedName() != null
                        && statement.getQualifiedName().endsWith("." + simple)) {
                    return expected.equals(statement.getQualifiedName());
                }
            }
        }
        String ownerPackage = expected.substring(0, expected.lastIndexOf('.'));
        if (ownerPackage.equals(file.getPackageName())) return true;
        if (imports == null || DumbService.isDumb(annotation.getProject())) return false;
        PsiClass local = JavaPsiFacade.getInstance(annotation.getProject())
                .findClass(file.getPackageName() + "." + simple, annotation.getResolveScope());
        return local == null && matchesOnDemandImports(annotation, imports, simple, expected);
    }

    /** Rejects wildcard imports when another imported package contributes the same simple name. */
    private static boolean matchesOnDemandImports(PsiAnnotation annotation, PsiImportList imports,
                                                  String simple, String expected) {
        boolean found = false;
        for (PsiImportStatement statement : imports.getImportStatements()) {
            if (!statement.isOnDemand() || statement.getQualifiedName() == null) continue;
            String candidate = statement.getQualifiedName() + "." + simple;
            if (candidate.equals(expected)) found = true;
            else if (JavaPsiFacade.getInstance(annotation.getProject())
                    .findClass(candidate, annotation.getResolveScope()) != null) return false;
        }
        return found;
    }

    /**
     * @param value literal or constant-expression annotation value
     * @return its Java compile-time string value, or {@code null} when absent or not a string constant
     */
    static @Nullable String stringValue(@Nullable PsiAnnotationMemberValue value) {
        if (value == null) return null;
        if (value instanceof PsiLiteralExpression literal) {
            return literal.getValue() instanceof String text ? text : null;
        }
        boolean nested = EVALUATING_CONSTANT.get();
        EVALUATING_CONSTANT.set(true);
        try {
            Object constant = JavaPsiFacade.getInstance(value.getProject())
                    .getConstantEvaluationHelper().computeConstantExpression(value);
            return constant instanceof String text ? text : null;
        } finally {
            if (nested) EVALUATING_CONSTANT.set(true);
            else EVALUATING_CONSTANT.remove();
        }
    }
}
