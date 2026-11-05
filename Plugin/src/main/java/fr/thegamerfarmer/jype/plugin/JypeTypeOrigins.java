package fr.thegamerfarmer.jype.plugin;

import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

import static fr.thegamerfarmer.jype.plugin.JypeAnnotationSyntax.*;
import static fr.thegamerfarmer.jype.plugin.JypeTypeSystemReader.*;

/**
 * Maps virtual aliases back to source annotation values for references and rename operations.
 * Calls require a PSI read action. Inherited aliases retain their original declaration even
 * when a descendant changes their bound; cycles are bounded by visited annotation identity.
 */
final class JypeTypeOrigins {
    private JypeTypeOrigins() {}

    /**
     * @param file candidate PSI file
     * @return whether it is a source package-info declaring the canonical Jype annotation
     */
    static boolean isTypeSystemFile(PsiFile file) {
        if (!(file instanceof PsiJavaFile javaFile) || !"package-info.java".equals(file.getName())) return false;
        PsiPackageStatement statement = javaFile.getPackageStatement();
        PsiModifierList modifiers = statement == null ? null : statement.getAnnotationList();
        return modifiers != null && Arrays.stream(modifiers.getAnnotations())
                .anyMatch(annotation -> hasAnnotationName(annotation, SYSTEM));
    }

    /**
     * @param literal candidate string literal
     * @return whether it is a TypeDef name nested in a Jype package annotation
     */
    static boolean isTypeNameLiteral(PsiLiteralExpression literal) {
        if (!(literal.getParent() instanceof PsiNameValuePair pair) || !"name".equals(pair.getName())
                || !(pair.getParent().getParent() instanceof PsiAnnotation def)
                || !hasAnnotationName(def, DEF) || !(literal.getValue() instanceof String)
                || !isTypeSystemFile(literal.getContainingFile())) return false;
        PsiAnnotation system = PsiTreeUtil.getParentOfType(def, PsiAnnotation.class);
        return system != null && hasAnnotationName(system, SYSTEM);
    }

    /**
     * @param type virtual alias being renamed
     * @param system declaration recorded when the alias was created, or {@code null}
     * @return original name attribute, including constant expressions, or {@code null} if unavailable
     */
    static @Nullable PsiAnnotationMemberValue renameOrigin(PsiClass type, @Nullable Result system) {
        if (system == null) return null;
        String name = type.getName();
        Set<PsiAnnotation> visited = new HashSet<>();
        while (visited.add(system.declaration())) {
            String parent = stringValue(system.declaration().findDeclaredAttributeValue("inherit"));
            Result inherited = parent == null ? null : getTypeSystems(type.getProject(), type.getResolveScope()).stream()
                    .filter(candidate -> candidate.pkg().equals(parent)).findFirst().orElse(null);
            if (inherited == null || inherited.typeDefs().stream().noneMatch(def -> def.name().equals(name))) break;
            system = inherited;
        }
        for (PsiAnnotation def : PsiTreeUtil.findChildrenOfType(system.declaration(), PsiAnnotation.class)) {
            PsiAnnotationMemberValue value = def.findDeclaredAttributeValue("name");
            if (hasAnnotationName(def, DEF) && Objects.equals(name, stringValue(value))) return value;
        }
        return null;
    }
}
