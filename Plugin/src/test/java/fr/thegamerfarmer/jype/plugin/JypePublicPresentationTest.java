package fr.thegamerfarmer.jype.plugin;

import com.intellij.lang.documentation.DocumentationMarkup;
import com.intellij.psi.*;

import java.util.Arrays;
import java.util.List;

/** Public declarations retain alias names, ordinary generics and their documentation. */
public class JypePublicPresentationTest extends JypeJavaFixture {
    public void testClassAndMemberDocumentationDoNotExpandTechnicalParameters() {
        objectSystem("api", "ENTITY");
        PsiClass cls = myFixture.addClass("""
                package api;
                /** Service documentation. */
                public class Service<T extends java.util.List<ENTITY>, ENTITY> {
                    /** Entity documentation. */
                    public java.util.List<ENTITY> entities() { return null; }
                    /** The method's ENTITY is an ordinary parameter. */
                    public <ENTITY extends Number> ENTITY shadow(ENTITY value) { return value; }
                }
                """);
        JypeDocumentationProvider provider = new JypeDocumentationProvider();
        String document = com.intellij.codeInsight.documentation.DocumentationManager
                .getProviderFromElement(cls).generateDoc(cls, null);
        assertTrue(document, document.contains("Service documentation."));
        assertTrue(document, document.contains("Service&lt;T extends List&lt;ENTITY&gt;&gt;"));
        assertFalse(document, document.contains("Service&lt;T extends List&lt;ENTITY&gt;, ENTITY"));
        PsiMethod method = cls.findMethodsByName("entities", false)[0];
        document = provider.generateDoc(method, null);
        assertTrue(document, document.contains("Entity documentation."));
        assertTrue(document, document.contains("List&lt;ENTITY&gt; entities()"));
        assertTrue(document, document.contains("psi_element://api.ENTITY"));
        assertTrue(provider.getQuickNavigateInfo(method, null).contains("List&lt;ENTITY&gt; entities()"));
        PsiMethod shadow = cls.findMethodsByName("shadow", false)[0];
        assertTrue(provider.generateDoc(shadow, null).contains("&lt;ENTITY extends Number&gt; ENTITY shadow(ENTITY value)"));
    }

    public void testOrdinaryJavaKeepsItsDefaultDocumentation() {
        PsiClass cls = myFixture.addClass("package plain; public class Box<T> {}");
        JypeDocumentationProvider provider = new JypeDocumentationProvider();
        assertNull(provider.generateDoc(cls, null));
        assertNull(provider.getQuickNavigateInfo(cls, null));
    }

    public void testArrayWildcardAndOrdinaryBoundsPresentation() {
        objectSystem("api", "ENTITY");
        PsiClass cls = myFixture.addClass("""
                package api;
                public class Box<T, ENTITY> {
                    public java.util.List<? extends ENTITY>[] values;
                    public <X extends Number & Comparable<X>> X convert(X... values)
                            throws IllegalArgumentException { return null; }
                }
                """);
        assertEquals("List<? extends ENTITY>[]", JypePublicPresentation.type(cls.getFields()[0].getType()));
        String signature = JypePublicPresentation.signature(cls.findMethodsByName("convert", false)[0]);
        assertTrue(signature, signature.contains("<X extends Number & Comparable<X>> X convert(X... values)"));
        assertTrue(signature, signature.endsWith("throws IllegalArgumentException"));
        String document = new JypeDocumentationProvider().generateDoc(cls.getFields()[0], null);
        assertTrue(document, document.contains(DocumentationMarkup.DEFINITION_START));
        assertTrue(document, document.contains("List&lt;? extends ENTITY&gt;[] values"));
    }

    public void testCompletionUsesPublicParametersAndKeepsInsertion() {
        objectSystem("api", "ENTITY");
        myFixture.addClass("""
                package api;
                public class Service<ENTITY> {
                    public ENTITY accept(ENTITY value) { return value; }
                    public ENTITY acceptAll(ENTITY value) { return value; }
                }
                """);
        PsiFile file = myFixture.addFileToProject("api/Consumer.java", """
                package api;
                class Consumer { Service service; void use() { service.ac<caret> } }
                """);
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        var items = myFixture.completeBasic();
        assertNotNull(items);
        if (items != null) {
            var item = Arrays.stream(items).filter(value -> value.getLookupString().equals("accept")).findFirst().orElseThrow();
            var presentation = new com.intellij.codeInsight.lookup.LookupElementPresentation();
            item.renderElement(presentation);
            assertEquals("ENTITY", presentation.getTypeText());
            assertEquals("(ENTITY value)", presentation.getTailText());
            var expensive = item.getExpensiveRenderer();
            assertNotNull(expensive);
        } else {
            assertTrue(myFixture.getFile().getText().contains("service.accept("));
        }
    }

    public void testParameterInfoKeepsAllJypeOverloadsAndOrdinaryArguments() {
        objectSystem("api", "ENTITY");
        myFixture.addClass("""
                package api;
                public class Service<ENTITY> {
                    public void accept(ENTITY value) {}
                    public void accept(ENTITY value, int count) {}
                }
                """);
        PsiFile file = myFixture.addFileToProject("api/Consumer.java", """
                package api;
                class Consumer { Service service; void use() { service.accept(); } }
                """);
        PsiMethodCallExpression call = com.intellij.psi.util.PsiTreeUtil.findChildOfType(file, PsiMethodCallExpression.class);
        var candidates = JypeParameterInfoHandler.candidates(call.getArgumentList());
        assertEquals(2, candidates.size());
        assertTrue(candidates.stream().anyMatch(candidate -> candidate.parameters().equals(List.of("ENTITY value"))));
        assertTrue(candidates.stream().anyMatch(candidate -> candidate.parameters().equals(List.of("ENTITY value", "int count"))));
    }
}
