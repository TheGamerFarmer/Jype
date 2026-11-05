package fr.thegamerfarmer.jype.plugin;

import com.intellij.openapi.project.DumbService;
import com.intellij.patterns.PlatformPatterns;
import com.intellij.psi.*;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;

/**
 * Makes TypeDef name strings navigable and discoverable by Java rename searches.
 * Providers run under PSI read access, retain no project state and return no references while
 * indexing. Soft references avoid introducing an extra unresolved-reference diagnostic.
 */
public final class JypeTypeReferenceContributor extends PsiReferenceContributor {
    /**
     * Registers soft references only on Java literals; each callback verifies declaration context.
     *
     * @param registrar platform registry receiving a provider restricted to Java literal expressions
     */
    @Override
    public void registerReferenceProviders(@NotNull PsiReferenceRegistrar registrar) {
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(PsiLiteralExpression.class),
                new PsiReferenceProvider() {
                    /**
                     * Builds references lazily without retaining a resolved virtual class.
                     *
                     * @param element literal matched by the registered pattern
                     * @param context platform pattern context; no cross-call state is stored
                     * @return one soft alias-name reference, or none for unrelated literals
                     */
                    @Override
                    public PsiReference @NotNull [] getReferencesByElement(@NotNull PsiElement element,
                                                                          @NotNull ProcessingContext context) {
                        if (DumbService.isDumb(element.getProject())) return PsiReference.EMPTY_ARRAY;
                        PsiLiteralExpression literal = (PsiLiteralExpression) element;
                        if (!JypeTypeSystemReader.isTypeNameLiteral(literal)) return PsiReference.EMPTY_ARRAY;
                        return new PsiReference[]{new PsiReferenceBase<PsiLiteralExpression>(literal,
                                ElementManipulators.getValueTextRange(literal), true) {
                            /**
                             * Resolves against the latest cache generation rather than retaining stale PSI.
                             *
                             * @return locally declared virtual alias, or {@code null} if no longer valid
                             */
                            @Override
                            public PsiElement resolve() {
                                return JypeTypeSystemReader.typeDeclaredBy(getElement());
                            }
                        }};
                    }
                });
    }
}
