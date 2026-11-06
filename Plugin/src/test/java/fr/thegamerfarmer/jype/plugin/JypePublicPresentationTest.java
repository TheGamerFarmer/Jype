package fr.thegamerfarmer.jype.plugin;

import com.intellij.lang.documentation.DocumentationMarkup;
import com.intellij.psi.*;

import java.util.Arrays;
import java.util.List;

/** Public declarations retain alias names, ordinary generics and their documentation. */
public class JypePublicPresentationTest extends JypeJavaFixture {
    public void testClassAndMemberDocumentationDoNotExpandTechnicalParameters() {
        objectSystem("api", "PLAYER");
        PsiClass cls = myFixture.addClass("""
                package api;
                /** Service documentation. */
                public class Service<T extends java.util.List<PLAYER>, PLAYER> {
                    /** Player documentation. */
                    public java.util.List<PLAYER> players() { return null; }
                    /** The method's PLAYER is an ordinary parameter. */
                    public <PLAYER extends Number> PLAYER shadow(PLAYER value) { return value; }
                }
                """);
        JypeDocumentationProvider provider = new JypeDocumentationProvider();
        String document = com.intellij.codeInsight.documentation.DocumentationManager
                .getProviderFromElement(cls).generateDoc(cls, null);
        assertTrue(document, document.contains("Service documentation."));
        assertTrue(document, document.contains("Service&lt;T extends List&lt;PLAYER&gt;&gt;"));
        assertFalse(document, document.contains("Service&lt;T extends List&lt;PLAYER&gt;, PLAYER"));
        PsiMethod method = cls.findMethodsByName("players", false)[0];
        document = provider.generateDoc(method, null);
        assertTrue(document, document.contains("Player documentation."));
        assertTrue(document, document.contains("List&lt;PLAYER&gt; players()"));
        assertTrue(document, document.contains("psi_element://api.PLAYER"));
        assertTrue(provider.getQuickNavigateInfo(method, null).contains("List&lt;PLAYER&gt; players()"));
        PsiMethod shadow = cls.findMethodsByName("shadow", false)[0];
        assertTrue(provider.generateDoc(shadow, null).contains("&lt;PLAYER extends Number&gt; PLAYER shadow(PLAYER value)"));
    }

    public void testOrdinaryJavaKeepsItsDefaultDocumentation() {
        PsiClass cls = myFixture.addClass("package plain; public class Box<T> {}");
        JypeDocumentationProvider provider = new JypeDocumentationProvider();
        assertNull(provider.generateDoc(cls, null));
        assertNull(provider.getQuickNavigateInfo(cls, null));
    }

    public void testArrayWildcardAndOrdinaryBoundsPresentation() {
        objectSystem("api", "PLAYER");
        PsiClass cls = myFixture.addClass("""
                package api;
                public class Box<T, PLAYER> {
                    public java.util.List<? extends PLAYER>[] values;
                    public <X extends Number & Comparable<X>> X convert(X... values)
                            throws IllegalArgumentException { return null; }
                }
                """);
        assertEquals("List<? extends PLAYER>[]", JypePublicPresentation.type(cls.getFields()[0].getType()));
        String signature = JypePublicPresentation.signature(cls.findMethodsByName("convert", false)[0]);
        assertTrue(signature, signature.contains("<X extends Number & Comparable<X>> X convert(X... values)"));
        assertTrue(signature, signature.endsWith("throws IllegalArgumentException"));
        String document = new JypeDocumentationProvider().generateDoc(cls.getFields()[0], null);
        assertTrue(document, document.contains(DocumentationMarkup.DEFINITION_START));
        assertTrue(document, document.contains("List&lt;? extends PLAYER&gt;[] values"));
    }

    public void testCompletionUsesPublicParametersAndKeepsInsertion() {
        objectSystem("api", "PLAYER");
        myFixture.addClass("""
                package api;
                public class Service<PLAYER> {
                    public PLAYER accept(PLAYER player) { return player; }
                    public PLAYER acceptAll(PLAYER player) { return player; }
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
            assertEquals("PLAYER", presentation.getTypeText());
            assertEquals("(PLAYER player)", presentation.getTailText());
            var expensive = item.getExpensiveRenderer();
            assertNotNull(expensive);
        } else {
            assertTrue(myFixture.getFile().getText().contains("service.accept("));
        }
    }

    public void testParameterInfoKeepsAllJypeOverloadsAndOrdinaryArguments() {
        objectSystem("api", "PLAYER");
        myFixture.addClass("""
                package api;
                public class Service<PLAYER> {
                    public void accept(PLAYER value) {}
                    public void accept(PLAYER value, int count) {}
                }
                """);
        PsiFile file = myFixture.addFileToProject("api/Consumer.java", """
                package api;
                class Consumer { Service service; void use() { service.accept(); } }
                """);
        PsiMethodCallExpression call = com.intellij.psi.util.PsiTreeUtil.findChildOfType(file, PsiMethodCallExpression.class);
        var candidates = JypeParameterInfoHandler.candidates(call.getArgumentList());
        assertEquals(2, candidates.size());
        assertTrue(candidates.stream().anyMatch(candidate -> candidate.parameters().equals(List.of("PLAYER value"))));
        assertTrue(candidates.stream().anyMatch(candidate -> candidate.parameters().equals(List.of("PLAYER value", "int count"))));
    }
}
