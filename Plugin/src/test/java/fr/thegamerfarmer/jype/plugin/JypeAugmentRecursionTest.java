package fr.thegamerfarmer.jype.plugin;

import com.intellij.psi.*;
import com.intellij.psi.augment.PsiAugmentProvider;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Lombok computes a class's inner classes by reading every method's return type. When those types
 * are completed Jype references, inference resolves a reference, which asks the class for its inner
 * classes again: Jype must not recurse once per method until the stack overflows.
 */
public class JypeAugmentRecursionTest extends JypeJavaFixture {
    /** Mimics Lombok's builder processor: inner-class augments read all return types. */
    private static final class ReturnTypeReadingProvider extends PsiAugmentProvider {
        @Override
        protected <Psi extends PsiElement> @NotNull List<Psi> getAugments(
                @NotNull PsiElement element, @NotNull Class<Psi> type, @Nullable String nameHint) {
            if (type == PsiClass.class && element instanceof PsiClass cls) {
                for (PsiMethod method : cls.getMethods()) method.getReturnType();
            }
            return List.of();
        }
    }

    /** Mimics IntelliJ 2026.2, whose implicit parameter modifiers depend on the declared type. */
    private static final class TypeReadingModifierProvider extends PsiAugmentProvider {
        @Override
        protected @NotNull java.util.Set<String> transformModifiers(@NotNull PsiModifierList modifierList,
                                                                   @NotNull java.util.Set<String> modifiers) {
            if (modifierList.getParent() instanceof PsiParameter parameter) parameter.getType();
            return modifiers;
        }
    }

    public void testParameterModifiersReadingTheirTypeDoNotOverflow() {
        PsiAugmentProvider.EP_NAME.getPoint().registerExtension(new TypeReadingModifierProvider(), getTestRootDisposable());
        myFixture.addClass("""
                package lib;
                @fr.thegamerfarmer.jype.annotations.JypeClass(managedParameters={"ENTITY"}, typeSystem="lib")
                public class Lib<ENTITY> { public ENTITY value() { return null; } }
                """);
        objectSystem("lib", "ENTITY");
        PsiFile file = myFixture.addFileToProject("lib/User.java", """
                package lib;
                class User {
                    Object use(Lib value) { return value.value(); }
                    static Object apply(java.util.function.Function<Lib, Object> function) { return function.apply(null); }
                    Object lambda() { return apply((Lib item) -> item.value()); }
                }
                """);
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        myFixture.checkHighlighting(false, false, false);
        PsiParameter parameter = ((PsiJavaFile) file).getClasses()[0].findMethodsByName("use", false)[0]
                .getParameterList().getParameters()[0];
        assertEquals("lib.Lib<java.lang.Object>", parameter.getType().getCanonicalText());
    }

    public void testInnerClassAugmentsReadingReturnTypesDoNotOverflow() {
        PsiAugmentProvider.EP_NAME.getPoint().registerExtension(new ReturnTypeReadingProvider(), getTestRootDisposable());
        myFixture.addClass("""
                package lib;
                @fr.thegamerfarmer.jype.annotations.JypeClass(managedParameters={"ENTITY"}, typeSystem="lib")
                public class Lib<ENTITY> { public ENTITY value() { return null; } }
                """);
        objectSystem("lib", "ENTITY");
        StringBuilder methods = new StringBuilder();
        for (int i = 0; i < 400; i++) methods.append("    Lib m").append(i).append("() { return null; }\n");
        PsiFile file = myFixture.addFileToProject("lib/Holder.java",
                "package lib;\nclass Holder {\n" + methods + "    Object first() { return m0().value(); }\n}\n");
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        myFixture.checkHighlighting(false, false, false);
        PsiMethod method = ((PsiJavaFile) file).getClasses()[0].findMethodsByName("m7", false)[0];
        assertEquals("Completion still applies once the cycle is cut", "Lib", method.getReturnType().getPresentableText());
        // ENTITY is bound to a class without parameters, which is that class itself.
        assertEquals("lib.Lib<java.lang.Object>", method.getReturnType().getCanonicalText());
    }
}
