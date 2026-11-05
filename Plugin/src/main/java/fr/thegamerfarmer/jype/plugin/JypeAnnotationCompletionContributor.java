package fr.thegamerfarmer.jype.plugin;

import com.intellij.codeInsight.completion.CompletionContributor;
import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.CompletionResultSet;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.openapi.project.DumbService;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static fr.thegamerfarmer.jype.plugin.JypeAnnotationSyntax.*;

/** Completes package inheritance and inherited override names in annotation strings. */
public final class JypeAnnotationCompletionContributor extends CompletionContributor {
    @Override
    public void fillCompletionVariants(@NotNull CompletionParameters parameters,
                                       @NotNull CompletionResultSet result) {
        if (DumbService.isDumb(parameters.getPosition().getProject())) return;
        PsiLiteralExpression literal = PsiTreeUtil.getParentOfType(
                parameters.getPosition(), PsiLiteralExpression.class, false);
        if (literal == null || !(literal.getParent() instanceof PsiNameValuePair pair)
                || !(pair.getParent().getParent() instanceof PsiAnnotation annotation)
                || !(parameters.getOriginalFile() instanceof PsiJavaFile file)
                || !"package-info.java".equals(file.getName())) return;
        PsiAnnotation system;
        boolean inherit = "inherit".equals(pair.getName()) && hasAnnotationName(annotation, SYSTEM);
        if (inherit) {
            system = annotation;
        } else {
            if (!"name".equals(pair.getName()) || !hasAnnotationName(annotation, DEF)) return;
            system = PsiTreeUtil.getParentOfType(annotation, PsiAnnotation.class);
            if (system == null || !hasAnnotationName(system, SYSTEM)) return;
            PsiAnnotationMemberValue overrides = system.findDeclaredAttributeValue("overrides");
            if (overrides == null || !PsiTreeUtil.isAncestor(overrides, annotation, false)) return;
        }
        PsiLiteralExpression original = PsiTreeUtil.getParentOfType(
                file.findElementAt(parameters.getOffset() - 1), PsiLiteralExpression.class, false);
        if (original == null) return;
        int offset = parameters.getOffset() - original.getTextRange().getStartOffset();
        if (offset < 1 || !original.getText().startsWith("\"")) return;
        String prefix = original.getText().substring(1, offset);
        int suffixLength = Math.max(0, original.getTextLength() - offset
                - (original.getText().endsWith("\"") ? 1 : 0));
        CompletionResultSet strings = result.withPrefixMatcher(prefix);
        List<JypeTypeSystemReader.Result> systems =
                JypeTypeSystemReader.getTypeSystems(file.getProject(), file.getResolveScope());
        if (inherit) {
            for (var candidate : systems) {
                if (candidate.error() != null || wouldCycle(candidate, file.getPackageName(), systems)) continue;
                add(strings, candidate.pkg(), suffixLength);
            }
        } else {
            String parent = stringValue(system.findDeclaredAttributeValue("inherit"));
            Set<String> used = new HashSet<>();
            for (PsiAnnotation sibling : PsiTreeUtil.findChildrenOfType(system, PsiAnnotation.class)) {
                if (sibling == annotation || !hasAnnotationName(sibling, DEF)) continue;
                String name = stringValue(sibling.findDeclaredAttributeValue("name"));
                if (name != null) used.add(name);
            }
            for (var candidate : systems) {
                if (!candidate.pkg().equals(parent) || candidate.error() != null) continue;
                for (var definition : candidate.typeDefs()) {
                    if (!used.contains(definition.name())) add(strings, definition.name(), suffixLength);
                }
            }
            result.stopHere();
        }
    }

    private boolean wouldCycle(JypeTypeSystemReader.Result candidate, String pkg,
                               List<JypeTypeSystemReader.Result> systems) {
        Set<String> visited = new HashSet<>();
        while (visited.add(candidate.pkg())) {
            if (candidate.pkg().equals(pkg)) return true;
            String parent = stringValue(candidate.declaration().findDeclaredAttributeValue("inherit"));
            var inherited = systems.stream().filter(system -> system.pkg().equals(parent)).findFirst();
            if (inherited.isEmpty()) return false;
            candidate = inherited.get();
        }
        return true;
    }

    private void add(CompletionResultSet result, String value, int suffixLength) {
        result.addElement(LookupElementBuilder.create(value)
                .withIcon(JypePackageInfoDecorator.ICON)
                .withInsertHandler((context, item) -> {
                    if (suffixLength > 0) {
                        context.getDocument().deleteString(context.getTailOffset(),
                                context.getTailOffset() + suffixLength);
                    }
                }));
    }
}
