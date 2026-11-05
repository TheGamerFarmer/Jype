package fr.thegamerfarmer.jype.plugin;

import com.intellij.psi.*;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

/**
 * Resolves source class literals without asking Java to resolve the surrounding annotation.
 * This avoids re-entering Jype's element finder during annotation highlighting. All access
 * requires a read action; results are restricted to the literal's resolve scope.
 */
final class JypeSourceBoundResolver {
    private JypeSourceBoundResolver() {}

    /**
     * Applies qualified-name, explicit-import, package and on-demand lookup precedence.
     *
     * @param access class literal whose operand names a class or interface
     * @return the unique visible class, or {@code null} for missing or ambiguous bounds
     */
    static @Nullable PsiClass resolve(PsiClassObjectAccessExpression access) {
        if (!(access.getContainingFile() instanceof PsiJavaFile file)) return null;
        String text = access.getOperand().getText().replaceAll("\\s+", "");
        var facade = JavaPsiFacade.getInstance(access.getProject());
        var scope = access.getResolveScope();
        PsiClass qualified = text.contains(".") ? facade.findClass(text, scope) : null;
        if (qualified != null) return qualified;
        int dot = text.indexOf('.');
        String first = dot < 0 ? text : text.substring(0, dot);
        PsiImportList imports = file.getImportList();
        if (imports != null) {
            for (PsiImportStatementBase statement : imports.getAllImportStatements()) {
                if (statement.isOnDemand()) continue;
                PsiJavaCodeReferenceElement reference = statement.getImportReference();
                if (reference != null && first.equals(reference.getReferenceName())) {
                    return facade.findClass(reference.getQualifiedName() + text.substring(first.length()), scope);
                }
            }
        }
        PsiClass local = facade.findClass(file.getPackageName() + "." + text, scope);
        if (local != null) return local;
        Set<PsiClass> candidates = new HashSet<>();
        PsiClass javaLang = facade.findClass("java.lang." + text, scope);
        if (javaLang != null) candidates.add(javaLang);
        if (imports != null) {
            for (PsiImportStatementBase statement : imports.getAllImportStatements()) {
                PsiJavaCodeReferenceElement reference = statement.getImportReference();
                if (!statement.isOnDemand() || reference == null) continue;
                PsiClass candidate = facade.findClass(reference.getQualifiedName() + "." + text, scope);
                if (candidate != null) candidates.add(candidate);
            }
        }
        return candidates.size() == 1 ? candidates.iterator().next() : null;
    }
}
