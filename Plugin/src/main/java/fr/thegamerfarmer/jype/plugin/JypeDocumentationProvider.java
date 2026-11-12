package fr.thegamerfarmer.jype.plugin;

import com.intellij.codeInsight.javadoc.JavaDocUtil;
import com.intellij.java.JavaBundle;
import com.intellij.lang.documentation.AbstractDocumentationProvider;
import com.intellij.lang.documentation.DocumentationMarkup;
import com.intellij.lang.java.JavaDocumentationProvider;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiFormatUtil;
import com.intellij.psi.util.PsiFormatUtilBase;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Keeps Java's documentation content and navigation, replacing only its declaration view.
 *
 * <p>Consumer code also reaches API declarations through elements the plugin synthesizes: inherited
 * member views typed with the consumer's bindings, and alias classes with constructors mirroring
 * their binding's. Java would print those with every technical argument of the Jype types they
 * mention ({@code APICommand<BGPlayer, BGRole, …>}); they are documented as the API declaration
 * they stand for, or, for an alias class, with its public signature.
 */
public final class JypeDocumentationProvider extends AbstractDocumentationProvider {
    @Override
    public @Nullable String getQuickNavigateInfo(PsiElement element, PsiElement originalElement) {
        PsiElement target = documented(element);
        if (publicView(target)) return htmlSignature(target);
        return target == element ? null : new JavaDocumentationProvider().getQuickNavigateInfo(target, originalElement);
    }

    /**
     * No external javadoc for a Jype declaration whose source is attached: IntelliJ prefers the
     * library's HTML javadoc to the source comment, and that HTML documents the expanded
     * declaration, with every technical parameter. The source comment is documented instead.
     */
    @Override
    public @Nullable List<String> getUrlFor(PsiElement element, PsiElement originalElement) {
        PsiElement target = documented(element);
        if (publicView(target) && target instanceof PsiCompiledElement
                && !(target.getNavigationElement() instanceof PsiCompiledElement)) return List.of();
        return target == element ? null : new JavaDocumentationProvider().getUrlFor(target, originalElement);
    }

    @Override
    public @Nullable String generateDoc(PsiElement element, PsiElement originalElement) {
        PsiElement target = documented(element);
        // Java documents the constructor of a class named right after `new`, not the class.
        if (target instanceof PsiClass cls && publicView(cls) && namedAfterNew(originalElement)) {
            PsiMethod[] constructors = cls.getConstructors();
            if (constructors.length > 1) return constructorCandidates(cls, constructors);
            if (constructors.length == 1) target = documented(constructors[0]);
        }
        if (!publicView(target)) {
            return target == element ? null : new JavaDocumentationProvider().generateDoc(target, originalElement);
        }
        element = target;
        // Java pairs a compiled method's @param tags through IntelliJ's own source pairing, which
        // fails for alias parameters (see JypeSourceNavigation): the source method is documented.
        PsiElement source = element instanceof PsiMethod && element.getNavigationElement() instanceof PsiMethod method
                && !(method instanceof PsiCompiledElement) ? method : element;
        String documentation = new JavaDocumentationProvider().generateDoc(source, originalElement);
        String definition = DocumentationMarkup.DEFINITION_START
                + StringUtil.escapeXmlEntities(JypePublicPresentation.signature(element, source))
                + DocumentationMarkup.DEFINITION_END;
        String links = aliasLinks(element);
        if (documentation == null) return definition + links;
        documentation = publicContainer(documentation, element);
        int start = documentation.indexOf(DocumentationMarkup.DEFINITION_START);
        int end = documentation.indexOf(DocumentationMarkup.DEFINITION_END, start);
        if (start < 0 || end < 0) return definition + links + documentation;
        return documentation.substring(0, start) + definition
                + links + documentation.substring(end + DocumentationMarkup.DEFINITION_END.length());
    }

    /**
     * The declaration documented for {@code element}: an inherited view or alias constructor stands
     * for its source, and an attached library source for its class file.
     */
    private static PsiElement documented(PsiElement element) {
        PsiMember original = JypeInheritedMembers.original(element);
        if (original != null) return original;
        if (element instanceof PsiMethod method && method.isConstructor()) {
            PsiMethod binding = JypeTypeSystemReader.mirroredConstructor(method);
            if (binding != null) return binding;
        }
        PsiMember compiled = compiled(element);
        return compiled != null && publicView(compiled) ? compiled : element;
    }

    /**
     * The class-file declaration of an attached library source. IntelliJ may document the source
     * instead of the class file a reference resolved to, but only the class file carries the Jype
     * metadata, and Java prints the container with the class file's technical parameters.
     */
    private static @Nullable PsiMember compiled(PsiElement element) {
        if (!(element instanceof PsiMember member) || element instanceof PsiCompiledElement
                || element instanceof PsiTypeParameter) return null;
        PsiClass owner = member instanceof PsiClass cls ? cls : member.getContainingClass();
        if (owner == null || !(owner.getOriginalElement() instanceof PsiClass mirror)
                || mirror == owner || !(mirror instanceof PsiCompiledElement)) return null;
        if (member instanceof PsiClass) return mirror;
        if (member instanceof PsiField field) return mirror.findFieldByName(field.getName(), false);
        return member instanceof PsiMethod method ? JypeSourceNavigation.counterpart(method, mirror) : null;
    }

    /** Mirrors {@link JavaDocumentationProvider#generateDocStatic}'s detection of {@code new Name(…)}. */
    private static boolean namedAfterNew(@Nullable PsiElement originalElement) {
        if (originalElement == null || originalElement instanceof PsiPackage
                || originalElement instanceof PsiFileSystemItem) return false;
        PsiElement previous = PsiTreeUtil.prevLeaf(originalElement);
        if (previous instanceof PsiErrorElement) previous = previous.getPrevSibling();
        else if (previous != null) previous = previous.getParent();
        return previous instanceof PsiNewExpression;
    }

    /** Java's candidate list for {@code new Name(…)}, with public signatures. */
    private static String constructorCandidates(PsiClass cls, PsiMethod[] constructors) {
        StringBuilder links = new StringBuilder();
        for (PsiMethod constructor : constructors) {
            PsiElement target = documented(constructor);
            String reference = JavaDocUtil.getReferenceText(target.getProject(), target);
            String label = publicView(target) ? htmlSignature(target) : StringUtil.escapeXmlEntities(
                    PsiFormatUtil.formatMethod((PsiMethod) target, PsiSubstitutor.EMPTY,
                            PsiFormatUtilBase.SHOW_NAME | PsiFormatUtilBase.SHOW_PARAMETERS,
                            PsiFormatUtilBase.SHOW_NAME | PsiFormatUtilBase.SHOW_TYPE));
            links.append("&nbsp;&nbsp;<a href=\"psi_element://")
                    .append(StringUtil.escapeXmlEntities(Objects.requireNonNullElse(reference, "")))
                    .append("\">").append(label).append("</a><br>");
        }
        return JavaBundle.message("javadoc.constructor.candidates", cls.getName(), links);
    }

    private static boolean publicView(PsiElement element) {
        return JypePublicPresentation.applies(element) || virtualAlias(element);
    }

    private static boolean virtualAlias(PsiElement element) {
        return element instanceof PsiClass cls && JypeTypeSystemReader.isVirtualClass(cls);
    }

    private static String htmlSignature(PsiElement element) {
        return StringUtil.escapeXmlEntities(JypePublicPresentation.signature(element));
    }

    private static String publicContainer(String documentation, PsiElement element) {
        int start = documentation.indexOf("<div class=\"bottom\">");
        int end = documentation.indexOf("</div>", start);
        int definition = documentation.indexOf(DocumentationMarkup.DEFINITION_START);
        PsiClass owner = JypePublicPresentation.owner(element);
        if (start < 0 || end < 0 || definition < end || owner == null || owner.getQualifiedName() == null) {
            return documentation;
        }
        JypeClassMetadata.Data data = JypeClassMetadata.read(owner);
        if (data == null) return documentation;
        String name = StringUtil.escapeXmlEntities(owner.getQualifiedName());
        String label = StringUtil.escapeXmlEntities(owner.getQualifiedName()
                + JypePublicPresentation.typeParameters(owner, data.managed()));
        String container = "<div class=\"bottom\"><a href=\"psi_element://" + name + "\">" + label + "</a></div>";
        return documentation.substring(0, start) + container + documentation.substring(end + "</div>".length());
    }

    private static String aliasLinks(PsiElement element) {
        PsiClass owner = JypePublicPresentation.owner(element);
        if (owner == null || virtualAlias(owner)) return "";
        String root = JypeTypeSystemReader.getTypeSystemRootPackage(owner);
        if (root == null) return "";
        Set<String> shadowed = new HashSet<>();
        if (element instanceof PsiMethod method) {
            JypeMemberMetadata.Data metadata = JypeMemberMetadata.read(method);
            if (metadata != null) shadowed.addAll(metadata.declared());
            else for (PsiTypeParameter parameter : method.getTypeParameters()) shadowed.add(parameter.getName());
        }
        String signature = JypePublicPresentation.signature(element);
        Set<String> tokens = new HashSet<>();
        for (int start = 0; start < signature.length();) {
            if (!Character.isJavaIdentifierStart(signature.charAt(start))) {
                start++;
                continue;
            }
            int end = start + 1;
            while (end < signature.length() && Character.isJavaIdentifierPart(signature.charAt(end))) end++;
            tokens.add(signature.substring(start, end));
            start = end;
        }
        List<String> links = new ArrayList<>();
        for (String name : JypeTypeSystemReader.getManagedNames(owner)) {
            if (!tokens.contains(name) || shadowed.contains(name)) continue;
            links.add("<a href=\"psi_element://" + StringUtil.escapeXmlEntities(root + "." + name)
                    + "\">" + StringUtil.escapeXmlEntities(name) + "</a>");
        }
        return links.isEmpty() ? "" : "<div>Jype: " + String.join(", ", links) + "</div>";
    }
}
