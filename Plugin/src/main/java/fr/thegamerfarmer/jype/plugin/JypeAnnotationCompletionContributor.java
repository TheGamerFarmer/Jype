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
            result.stopHere();
            system = PsiTreeUtil.getParentOfType(annotation, PsiAnnotation.class);
            if (system == null || !hasAnnotationName(system, SYSTEM)) return;
            PsiAnnotationMemberValue overrides = system.findDeclaredAttributeValue("overrides");
            if (overrides == null || !PsiTreeUtil.isAncestor(overrides, annotation, false)) return;
        }
        PsiLiteralExpression original = PsiTreeUtil.getParentOfType(
                file.findElementAt(parameters.getOffset() - 1), PsiLiteralExpression.class, false);
        if (original == null) return;
        PsiAnnotation originalAnnotation = PsiTreeUtil.getParentOfType(original, PsiAnnotation.class);
        if (originalAnnotation == null) return;
        system = inherit ? originalAnnotation
                : PsiTreeUtil.getParentOfType(originalAnnotation, PsiAnnotation.class);
        if (system == null) return;
        int offset = parameters.getOffset() - original.getTextRange().getStartOffset();
        if (offset < 1 || !original.getText().startsWith("\"")) return;
        if (offset > original.getTextLength()) return;
        String prefix = original.getText().substring(1, offset);
        CompletionResultSet strings = result.withPrefixMatcher(prefix);
        List<JypeTypeSystemReader.Result> systems =
                JypeTypeSystemReader.getTypeSystems(file.getProject(), file.getResolveScope());
        if (inherit) {
            for (var candidate : systems) {
                if (candidate.error() != null || wouldCycle(candidate, file.getPackageName(), systems)) continue;
                add(strings, candidate.pkg());
            }
        } else {
            String parent = stringValue(system.findDeclaredAttributeValue("inherit"));
            Set<String> used = new HashSet<>();
            PsiAnnotationMemberValue overrides = system.findDeclaredAttributeValue("overrides");
            for (PsiAnnotation sibling : PsiTreeUtil.findChildrenOfType(system, PsiAnnotation.class)) {
                if (sibling == originalAnnotation || !hasAnnotationName(sibling, DEF)
                        || overrides == null || !PsiTreeUtil.isAncestor(overrides, sibling, false)) continue;
                String name = stringValue(sibling.findDeclaredAttributeValue("name"));
                if (name != null) used.add(name);
            }
            for (var candidate : systems) {
                if (!candidate.pkg().equals(parent) || candidate.error() != null) continue;
                for (var definition : candidate.typeDefs()) {
                    if (!used.contains(definition.name())) add(strings, definition.name());
                }
            }
        }
        result.stopHere();
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

    private void add(CompletionResultSet result, String value) {
        result.addElement(LookupElementBuilder.create(value)
                .withIcon(JypePackageInfoDecorator.ICON)
                .withInsertHandler((context, item) -> {
                    context.commitDocument();
                    PsiLiteralExpression inserted = PsiTreeUtil.getParentOfType(
                            context.getFile().findElementAt(context.getTailOffset() - 1),
                            PsiLiteralExpression.class, false);
                    if (inserted == null || !inserted.getText().startsWith("\"")) return;
                    int end = inserted.getTextRange().getEndOffset()
                            - (inserted.getText().endsWith("\"") ? 1 : 0);
                    if (end > context.getTailOffset()) {
                        context.getDocument().deleteString(context.getTailOffset(), end);
                    }
                }));
    }
}
