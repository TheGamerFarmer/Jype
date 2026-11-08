package fr.thegamerfarmer.jype.plugin;

import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.*;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

/** Decorates Java candidates without changing overloads, sorting or insertion handlers. */
public final class JypeSignatureCompletionContributor extends CompletionContributor {
    @Override
    public void fillCompletionVariants(@NotNull CompletionParameters parameters, @NotNull CompletionResultSet result) {
        result.runRemainingContributors(parameters, candidate -> {
            Object object = candidate.getLookupElement().getObject();
            if (object instanceof PsiElement technical && JypeMemberMetadata.isTechnical(technical)) return;
            if (object instanceof PsiClass generated && isGeneratedFactory(generated)) return;
            if (!(object instanceof PsiElement element) || !JypePublicPresentation.applies(element)) {
                result.passResult(candidate);
                return;
            }
            LookupElement item = new LookupElementDecorator<>(candidate.getLookupElement()) {
                @Override
                public void renderElement(@NotNull LookupElementPresentation presentation) {
                    super.renderElement(presentation);
                    decorate(element, presentation);
                }

                @Override
                public LookupElementRenderer<? extends LookupElement> getExpensiveRenderer() {
                    return new LookupElementRenderer<>() {
                        @Override
                        public void renderElement(@NotNull LookupElement item,
                                                  @NotNull LookupElementPresentation presentation) {
                            getDelegate().renderElement(presentation);
                            decorate(element, presentation);
                        }
                    };
                }
            };
            result.passResult(candidate.withLookupElement(item));
        });
    }

    /**
     * Hides the engine's runtime factories ({@code Jype$New$ALIAS}) and installers
     * ({@code Jype$Install}): source code writes {@code new ALIAS(...)}, never these classes.
     */
    static boolean isGeneratedFactory(PsiClass cls) {
        String name = cls.getName();
        return name != null && cls.getContainingClass() == null
                && (name.startsWith("Jype$New$") || name.equals("Jype$Install"));
    }

    private static void decorate(PsiElement element, LookupElementPresentation presentation) {
        if (element instanceof PsiMethod method) {
            presentation.setTypeText(JypePublicPresentation.memberType(method, method.getReturnType()));
            presentation.setTailText("(" + JypePublicPresentation.parameters(method) + ")", true);
        } else if (element instanceof PsiField field) {
            presentation.setTypeText(JypePublicPresentation.memberType(field, field.getType()));
        } else if (element instanceof PsiClass cls) {
            JypeClassMetadata.Data metadata = JypeClassMetadata.read(cls);
            if (metadata != null) {
                String name = cls.getQualifiedName();
                String pkg = name == null || !name.contains(".") ? "" : " (" + name.substring(0, name.lastIndexOf('.')) + ")";
                presentation.setTailText(JypePublicPresentation.typeParameters(cls, metadata.managed()) + pkg, true);
            }
        }
    }
}
