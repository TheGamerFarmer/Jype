package fr.thegamerfarmer.jype.plugin;

import com.intellij.psi.*;
import com.intellij.psi.augment.PsiAugmentProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Supplies implicit Jype arguments to Java's ordinary member-resolution machinery. */
public final class JypeTypeAugmentProvider extends PsiAugmentProvider {
    @Override
    protected <Psi extends PsiElement> @NotNull List<Psi> getAugments(
            @NotNull PsiElement element, @NotNull Class<Psi> type, @Nullable String nameHint) {
        if (!(element instanceof PsiClass cls)) return List.of();
        if (type == PsiMethod.class) return JypeInheritedMembers.methods(cls, nameHint).stream().map(type::cast).toList();
        if (type == PsiField.class) return JypeInheritedMembers.fields(cls, nameHint).stream().map(type::cast).toList();
        return List.of();
    }

    @Override
    protected @Nullable PsiType inferType(@NotNull PsiTypeElement typeElement) {
        return JypeContextualTypes.infer(typeElement);
    }
}
