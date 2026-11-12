package fr.thegamerfarmer.jype.plugin;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.FileIndexFacade;
import com.intellij.openapi.roots.OrderEntry;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.util.RecursionManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.impl.compiled.ClsCustomNavigationPolicy;
import com.intellij.psi.impl.compiled.ClsFileImpl;
import com.intellij.psi.impl.compiled.ClsMethodImpl;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.StringJoiner;

/**
 * Pairs a compiled Jype class with its attached source, where IntelliJ's own pairing fails.
 *
 * <p>IntelliJ finds the source of a class file through its {@code SourceFile} attribute, which
 * obfuscators rewrite (ProGuard's {@code -renamesourcefileattribute SourceFile}); the source is
 * then looked up as {@code package/TopLevelName.java} in the library's source roots. It pairs a
 * compiled method with its source by parameter types, but a parameter naming an alias is the
 * alias in the source and a type variable in the class file; their public types match.
 */
public final class JypeSourceNavigation implements ClsCustomNavigationPolicy {
    @Override
    public @Nullable PsiElement getNavigationElement(@NotNull ClsFileImpl file) {
        return CachedValuesManager.getCachedValue(file, () -> CachedValueProvider.Result.create(
                RecursionManager.doPreventingRecursion(file, false, () -> source(file)),
                file, FileIndexFacade.getInstance(file.getProject()).getRootModificationTracker()));
    }

    @Override
    public @Nullable PsiElement getNavigationElement(@NotNull ClsMethodImpl method) {
        return RecursionManager.doPreventingRecursion(method, false, () -> {
            if (method.getSourceMirrorMethod() != null) return null;
            PsiClass owner = method.getContainingClass();
            if (owner == null || JypeClassMetadata.read(owner) == null
                    || !(owner.getNavigationElement() instanceof PsiClass source)
                    || source instanceof PsiCompiledElement) return null;
            return counterpart(method, source);
        });
    }

    private static @Nullable PsiJavaFile source(ClsFileImpl file) {
        PsiClass[] classes = file.getClasses();
        VirtualFile classFile = file.getVirtualFile();
        if (classes.length != 1 || classFile == null || classes[0].getName() == null) return null;
        Project project = file.getProject();
        String packageName = file.getPackageName();
        String path = (packageName.isEmpty() ? "" : packageName.replace('.', '/') + "/") + classes[0].getName() + ".java";
        for (OrderEntry entry : ProjectFileIndex.getInstance(project).getOrderEntriesForFile(classFile)) {
            for (VirtualFile root : entry.getFiles(OrderRootType.SOURCES)) {
                VirtualFile candidate = root.findFileByRelativePath(path);
                if (candidate != null && PsiManager.getInstance(project).findFile(candidate) instanceof PsiJavaFile source
                        && packageName.equals(source.getPackageName())) {
                    // Only Jype libraries: other class files keep IntelliJ's own pairing.
                    return JypeTypeSystemReader.isInTypeSystemPackage(classes[0]) ? source : null;
                }
            }
        }
        return null;
    }

    /** The unique method of {@code in} matching {@code method} by public parameter types. */
    static @Nullable PsiMethod counterpart(PsiMethod method, PsiClass in) {
        String expected = parameterTypes(method);
        PsiMethod match = null;
        PsiMethod[] candidates = method.isConstructor() ? in.getConstructors()
                : in.findMethodsByName(method.getName(), false);
        for (PsiMethod candidate : candidates) {
            if (candidate.isConstructor() != method.isConstructor() || !expected.equals(parameterTypes(candidate))) continue;
            if (match != null) return null;
            match = candidate;
        }
        return match;
    }

    private static String parameterTypes(PsiMethod method) {
        StringJoiner types = new StringJoiner(",");
        for (PsiParameter parameter : method.getParameterList().getParameters()) {
            types.add(JypePublicPresentation.type(parameter.getType()));
        }
        return types.toString();
    }
}
