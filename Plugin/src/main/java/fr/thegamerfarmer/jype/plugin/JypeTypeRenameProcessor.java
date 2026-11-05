package fr.thegamerfarmer.jype.plugin;

import com.intellij.openapi.progress.ProgressManager;
import com.intellij.psi.*;
import com.intellij.psi.search.*;
import com.intellij.refactoring.listeners.RefactoringElementListener;
import com.intellij.refactoring.rename.RenamePsiElementProcessor;
import com.intellij.usageView.UsageInfo;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.containers.MultiMap;
import org.jetbrains.annotations.NotNull;

import java.util.*;

/**
 * Renames virtual aliases through their original source name and all related Java references.
 *
 * <p>Inherited aliases and bound overrides share one rename origin. Library declarations and
 * constant-expression names are rejected rather than rewriting unrelated source. Discovery
 * callbacks require read access; the platform invokes mutation inside its refactoring command.
 */
public final class JypeTypeRenameProcessor extends RenamePsiElementProcessor {
    /**
     * @param element candidate refactoring target
     * @return whether the target is a Jype virtual class
     */
    @Override
    public boolean canProcessElement(@NotNull PsiElement element) {
        return element instanceof PsiClass type && JypeTypeSystemReader.isVirtualClass(type);
    }

    /**
     * Requires the full refactoring workflow so inherited usages and conflicts are checked.
     *
     * @return {@code false}; editing only a synthetic identifier would not update its source
     */
    @Override
    public boolean isInplaceRenameSupported() {
        return false;
    }

    /**
     * Searches indexed words, then verifies resolved origin identity to reject same-name aliases.
     *
     * @param element virtual class accepted by {@link #canProcessElement}
     * @param scope refactoring search boundary
     * @param searchInCommentsAndStrings platform preference; alias name strings are always included,
     *                                  while comments cannot provide resolvable Java references
     * @return distinct references sharing the original declaration, in discovery order
     */
    @Override
    public Collection<PsiReference> findReferences(@NotNull PsiElement element, @NotNull SearchScope scope,
                                                  boolean searchInCommentsAndStrings) {
        PsiClass type = (PsiClass) element;
        PsiAnnotationMemberValue origin = JypeTypeSystemReader.renameOrigin(type);
        if (origin == null) return List.of();
        Set<PsiReference> references = new LinkedHashSet<>();
        PsiSearchHelper.getInstance(element.getProject()).processElementsWithWord((candidate, offset) -> {
            ProgressManager.checkCanceled();
            PsiReference reference = candidate.getContainingFile()
                    .findReferenceAt(candidate.getTextRange().getStartOffset() + offset);
            if (reference != null && reference.resolve() instanceof PsiClass resolved
                    && JypeTypeSystemReader.isVirtualClass(resolved)
                    && origin.equals(JypeTypeSystemReader.renameOrigin(resolved))) references.add(reference);
            return true;
        }, scope, Objects.requireNonNull(type.getName()), (short) (UsageSearchContext.IN_CODE | UsageSearchContext.IN_STRINGS), true);
        return references;
    }

    /**
     * Checks editability and collisions in inherited declaration and usage packages.
     *
     * @param element virtual class being renamed
     * @param newName proposed Java name
     * @param conflicts sink for source-editability and visible-class conflicts
     */
    @Override
    public void findExistingNameConflicts(@NotNull PsiElement element, @NotNull String newName,
                                          @NotNull MultiMap<PsiElement, String> conflicts) {
        PsiClass type = (PsiClass) element;
        PsiAnnotationMemberValue origin = JypeTypeSystemReader.renameOrigin(type);
        if (origin == null || !origin.isWritable() || !(origin instanceof PsiLiteralExpression)) {
            conflicts.putValue(element, "This Jype type is declared in a library or uses a constant name; rename its source declaration.");
            return;
        }
        var scope = GlobalSearchScope.allScope(element.getProject());
        Set<String> packages = relatedPackages(type, origin, scope);
        for (String pkg : packages) {
            PsiClass collision = JavaPsiFacade.getInstance(element.getProject()).findClass(pkg + "." + newName, scope);
            if (collision != null && !(JypeTypeSystemReader.isVirtualClass(collision)
                    && origin.equals(JypeTypeSystemReader.renameOrigin(collision)))) {
                conflicts.putValue(element, "A type named " + newName + " already exists in " + pkg);
            }
        }
    }

    /** Includes every declaration and usage package sharing the alias's original name attribute. */
    private Set<String> relatedPackages(PsiClass type, PsiAnnotationMemberValue origin, GlobalSearchScope scope) {
        Set<String> packages = new LinkedHashSet<>();
        for (var system : JypeTypeSystemReader.getTypeSystems(type.getProject(), scope)) {
            for (var def : system.typeDefs()) {
                PsiClass related = JypeTypeSystemReader.getOrCreateVirtualClass(type.getProject(), system, def, scope);
                if (!origin.equals(JypeTypeSystemReader.renameOrigin(related))) continue;
                packages.add(system.pkg());
            }
        }
        for (PsiReference reference : findReferences(type, scope, false)) {
            if (reference.getElement().getContainingFile() instanceof PsiJavaFile file) {
                packages.add(file.getPackageName());
            }
        }
        return packages;
    }

    /**
     * Updates usages before the name literal so cache invalidation cannot detach pending references.
     *
     * @param element virtual class being renamed
     * @param newName validated replacement name
     * @param usages usages collected by the refactoring workflow
     * @param listener optional listener notified with the newly resolved canonical virtual class
     * @throws IncorrectOperationException if the origin is not an editable source string literal
     */
    @Override
    public void renameElement(@NotNull PsiElement element, @NotNull String newName,
                              UsageInfo @NotNull [] usages, RefactoringElementListener listener) {
        PsiAnnotationMemberValue origin = JypeTypeSystemReader.renameOrigin((PsiClass) element);
        if (!(origin instanceof PsiLiteralExpression) || !origin.isWritable()) {
            throw new IncorrectOperationException("The Jype declaration is not an editable string literal");
        }
        List<PsiReference> references = Arrays.stream(usages).map(UsageInfo::getReference)
                .filter(Objects::nonNull).toList();
        for (PsiReference reference : references) reference.handleElementRename(newName);
        if (origin.isValid() && origin instanceof PsiLiteralExpression literal
                && !newName.equals(literal.getValue())) {
            ElementManipulators.handleContentChange(literal, newName);
        }
        if (listener != null && element instanceof PsiClass type && type.getQualifiedName() != null) {
            String qualified = type.getQualifiedName();
            PsiClass renamed = new JypePsiElementFinder(element.getProject()).findClass(
                    qualified.substring(0, qualified.lastIndexOf('.') + 1) + newName, type.getResolveScope());
            if (renamed != null) listener.elementRenamed(renamed);
        }
    }
}
