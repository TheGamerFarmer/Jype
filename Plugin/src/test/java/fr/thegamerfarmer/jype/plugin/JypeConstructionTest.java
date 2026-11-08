package fr.thegamerfarmer.jype.plugin;

import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.psi.PsiFile;

import java.util.Arrays;

/** {@code new ALIAS(...)} uses the constructors of the alias binding with their access, as the engine does. */
public class JypeConstructionTest extends JypeJavaFixture {
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.addClass("package api; public class ApiEntity { public ApiEntity() {} public ApiEntity(ENTITY parent) {} }");
        myFixture.addClass("""
                package api;
                public class Link {
                    public Link(ENTITY entity) {}
                    public Link(ENTITY entity, int level, String... tags) {}
                    public <T> Link(T ignored, T other) {}
                }
                """);
        myFixture.addClass("package api; public interface Kind {}");
        myFixture.addClass("""
                package api;
                public class Service {
                    protected Service() {}
                    public static SERVICE create() { return new SERVICE(); }
                }
                """);
        myFixture.addClass("""
                package api;
                public class Secret {
                    private Secret(String value) {}
                    public static SECRET of(String value) { return new SECRET(value); }
                }
                """);
        define("api", """
                {@fr.thegamerfarmer.jype.annotations.TypeDef(name="ENTITY", bound=ApiEntity.class),
                 @fr.thegamerfarmer.jype.annotations.TypeDef(name="LINK", bound=Link.class),
                 @fr.thegamerfarmer.jype.annotations.TypeDef(name="KIND", bound=Kind.class),
                 @fr.thegamerfarmer.jype.annotations.TypeDef(name="SERVICE", bound=Service.class),
                 @fr.thegamerfarmer.jype.annotations.TypeDef(name="SECRET", bound=Secret.class)}
                """);
    }

    public void testCreationsUseTheBindingConstructors() {
        PsiFile file = myFixture.addFileToProject("api/Cluster.java", """
                package api;
                import java.util.function.Function;
                import java.util.function.Supplier;
                public class Cluster {
                    ENTITY create() { return new ENTITY(); }
                    ENTITY child(ENTITY parent) { return new ENTITY(parent); }
                    LINK link(ENTITY entity) { return new LINK(entity); }
                    LINK tagged(ENTITY entity) { return new LINK(entity, 2, "a", "b"); }
                    KIND kind() { return new KIND(); }
                    Supplier<ENTITY> entities() { return ENTITY::new; }
                    Function<ENTITY, LINK> links() { return LINK::new; }
                }
                """);
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        myFixture.checkHighlighting(false, false, false);
    }

    public void testNonPublicConstructorsFollowJavaAccess() {
        myFixture.configureFromExistingVirtualFile(
                myFixture.findClass("api.Service").getContainingFile().getVirtualFile());
        myFixture.checkHighlighting(false, false, false);
        myFixture.configureFromExistingVirtualFile(
                myFixture.findClass("api.Secret").getContainingFile().getVirtualFile());
        myFixture.checkHighlighting(false, false, false);
        PsiFile file = myFixture.addFileToProject("api/Outsider.java", """
                package api;
                public class Outsider {
                    Object commands() { return new SERVICE(); }
                    Object secret() { return new SECRET("x"); }
                }
                """);
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        var errors = myFixture.doHighlighting(HighlightSeverity.ERROR).stream()
                .map(info -> info.getDescription()).toList();
        assertEquals(errors.toString(), 1, errors.size());
        assertTrue(errors.toString(), errors.get(0).contains(
                "SECRET is bound to api.Secret, whose constructor with 1 parameter(s) is not accessible from api.Outsider"));
    }

    public void testMissingConstructorIsReported() {
        PsiFile file = myFixture.addFileToProject("api/Broken.java", """
                package api;
                public class Broken { Object link() { return new LINK(); } }
                """);
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        var errors = myFixture.doHighlighting(HighlightSeverity.ERROR);
        assertFalse(errors.toString(), errors.isEmpty());
    }

    public void testGeneratedFactoriesAreHiddenFromCompletion() {
        myFixture.addClass("package api; public final class Jype$New$ENTITY {}");
        myFixture.addClass("package api; public final class Jype$Install {}");
        myFixture.addClass("package api; public final class JypeVisible {}");
        PsiFile file = myFixture.addFileToProject("api/Use.java", "package api; class Use { Jype<caret> value; }");
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        var variants = myFixture.complete(CompletionType.BASIC, 2);
        var names = variants == null ? java.util.List.of(myFixture.getFile().getText())
                : Arrays.stream(variants).map(item -> item.getLookupString()).toList();
        assertFalse(names.toString(), names.stream().anyMatch(name -> name.contains("Jype$New$") || name.contains("Jype$Install")));
        assertTrue(names.toString(), names.stream().anyMatch(name -> name.contains("JypeVisible")));
    }
}
