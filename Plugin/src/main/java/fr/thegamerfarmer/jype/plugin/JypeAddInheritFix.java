package fr.thegamerfarmer.jype.plugin;

import com.intellij.codeInsight.completion.CodeCompletionHandlerBase;
import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.intention.impl.BaseIntentionAction;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

import static fr.thegamerfarmer.jype.plugin.JypeAnnotationSyntax.*;

/** Adds the missing parent attribute without choosing an inherited system for the user. */
public final class JypeAddInheritFix extends BaseIntentionAction {
    private final SmartPsiElementPointer<PsiAnnotation> declaration;

    public JypeAddInheritFix(PsiAnnotation annotation) {
        declaration = SmartPointerManager.getInstance(annotation.getProject())
                .createSmartPsiElementPointer(annotation);
        setText("Add inherit to @TypeSystem");
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Configure @TypeSystem inheritance";
    }

    @Override
    public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
        PsiAnnotation annotation = declaration.getElement();
        if (editor == null || annotation == null || !canModify(annotation)
                || annotation.getContainingFile() != file || DumbService.isDumb(project)
                || !hasAnnotationName(annotation, SYSTEM)
                || annotation.findDeclaredAttributeValue("overrides") == null) return false;
        String parent = stringValue(annotation.findDeclaredAttributeValue("inherit"));
        return parent == null || parent.isEmpty();
    }

    @Override
    public PsiElement getElementToMakeWritable(@NotNull PsiFile currentFile) {
        return declaration.getElement();
    }

    @Override
    public void invoke(@NotNull Project project, Editor editor, PsiFile file) {
        if (!isAvailable(project, editor, file)) return;
        PsiAnnotation annotation = declaration.getElement();
        if (annotation == null) return;
        PsiAnnotationMemberValue value = annotation.setDeclaredAttributeValue("inherit",
                JavaPsiFacade.getElementFactory(project).createExpressionFromText("\"\"", annotation));
        PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(editor.getDocument());
        int offset = value.getTextRange().getStartOffset() + 1;
        editor.getCaretModel().moveToOffset(offset);
        ApplicationManager.getApplication().invokeLater(() -> {
            if (project.isDisposed() || editor.isDisposed() || DumbService.isDumb(project)
                    || !isAvailable(project, editor, file)
                    || editor.getCaretModel().getOffset() != offset) return;
            PsiDocumentManager.getInstance(project).commitDocument(editor.getDocument());
            new CodeCompletionHandlerBase(CompletionType.BASIC).invokeCompletion(project, editor);
        }, project.getDisposed());
    }

    @Override
    public boolean startInWriteAction() {
        return true;
    }
}
