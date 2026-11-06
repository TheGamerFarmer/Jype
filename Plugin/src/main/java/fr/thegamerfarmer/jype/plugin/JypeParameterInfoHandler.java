package fr.thegamerfarmer.jype.plugin;

import com.intellij.lang.parameterInfo.*;
import com.intellij.openapi.project.DumbService;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/** Resolves overloads off the UI thread and renders their public Jype parameter types. */
public final class JypeParameterInfoHandler
        implements ParameterInfoHandler<PsiExpressionList, JypeParameterInfoHandler.Candidate> {
    record Candidate(PsiMethod method, List<String> parameters, boolean deprecated, boolean varargs) {}

    @Override
    public @Nullable PsiExpressionList findElementForParameterInfo(@NotNull CreateParameterInfoContext context) {
        PsiExpressionList arguments = argumentList(context.getFile(), context.getOffset());
        if (arguments == null || DumbService.isDumb(arguments.getProject())) return null;
        List<Candidate> candidates = candidates(arguments);
        if (candidates.isEmpty()) return null;
        context.setItemsToShow(candidates.toArray(Candidate[]::new));
        return arguments;
    }

    static List<Candidate> candidates(PsiExpressionList arguments) {
        PsiElement parent = arguments.getParent();
        JavaResolveResult[] results;
        if (parent instanceof PsiMethodCallExpression call) {
            results = call.getMethodExpression().multiResolve(true);
        } else if (parent instanceof PsiNewExpression expression) {
            results = new JavaResolveResult[]{expression.resolveMethodGenerics()};
        } else return List.of();
        List<Candidate> candidates = new ArrayList<>();
        for (JavaResolveResult result : results) {
            if (!(result.getElement() instanceof PsiMethod method) || !JypePublicPresentation.applies(method)) continue;
            List<String> parameters = Arrays.stream(method.getParameterList().getParameters())
                    .map(parameter -> JypePublicPresentation.type(parameter.getType()) + " "
                            + Objects.requireNonNullElse(parameter.getName(), "arg")).toList();
            candidates.add(new Candidate(method, parameters, method.isDeprecated(), method.isVarArgs()));
        }
        return List.copyOf(candidates);
    }

    @Override
    public void showParameterInfo(@NotNull PsiExpressionList arguments, @NotNull CreateParameterInfoContext context) {
        context.showHint(arguments, arguments.getTextRange().getStartOffset(), this);
    }

    @Override
    public @Nullable PsiExpressionList findElementForUpdatingParameterInfo(@NotNull UpdateParameterInfoContext context) {
        return argumentList(context.getFile(), context.getOffset());
    }

    @Override
    public void updateParameterInfo(@NotNull PsiExpressionList arguments, @NotNull UpdateParameterInfoContext context) {
        if (context.getParameterOwner() != arguments) {
            context.removeHint();
            return;
        }
        context.setCurrentParameter(ParameterInfoUtils.getCurrentParameterIndex(
                arguments.getNode(), context.getOffset(), JavaTokenType.COMMA));
    }

    @Override
    public void updateUI(Candidate candidate, @NotNull ParameterInfoUIContext context) {
        int selected = context.getCurrentParameterIndex();
        if (candidate.varargs() && !candidate.parameters().isEmpty()) {
            selected = Math.min(selected, candidate.parameters().size() - 1);
        }
        String text = String.join(", ", candidate.parameters());
        int start = -1;
        int end = -1;
        if (selected >= 0 && selected < candidate.parameters().size()) {
            start = 0;
            for (int i = 0; i < selected; i++) start += candidate.parameters().get(i).length() + 2;
            end = start + candidate.parameters().get(selected).length();
        }
        context.setupUIComponentPresentation(text, start, end, !context.isUIComponentEnabled(),
                candidate.deprecated(), false, context.getDefaultParameterColor());
    }

    private static @Nullable PsiExpressionList argumentList(PsiFile file, int offset) {
        PsiElement element = file.findElementAt(Math.max(0, offset - 1));
        return PsiTreeUtil.getParentOfType(element, PsiExpressionList.class, false);
    }
}
