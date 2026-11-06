package fr.thegamerfarmer.jype.plugin;

import com.intellij.lang.documentation.AbstractDocumentationProvider;
import com.intellij.lang.documentation.DocumentationMarkup;
import com.intellij.lang.java.JavaDocumentationProvider;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.*;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/** Keeps Java's documentation content and navigation, replacing only its declaration view. */
public final class JypeDocumentationProvider extends AbstractDocumentationProvider {
    @Override
    public @Nullable String getQuickNavigateInfo(PsiElement element, PsiElement originalElement) {
        return JypePublicPresentation.applies(element) ? htmlSignature(element) : null;
    }

    @Override
    public @Nullable String generateDoc(PsiElement element, PsiElement originalElement) {
        if (!JypePublicPresentation.applies(element)) return null;
        String documentation = new JavaDocumentationProvider().generateDoc(element, originalElement);
        String definition = DocumentationMarkup.DEFINITION_START + htmlSignature(element)
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
        if (owner == null) return "";
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
