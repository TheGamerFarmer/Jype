package fr.thegamerfarmer.jype.plugin;

import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;

import java.util.Map;

/** Canonical identity, package inheritance and resolve-scope isolation. */
public class JypeTypeSystemTest extends JypeJavaFixture {
    public void testNamesAreDeclaredNotGuessedAndVirtualIdentityIsShared() {
        objectSystem("managed", "Player2");
        JypePsiElementFinder finder = new JypePsiElementFinder(getProject());
        PsiClass canonical = finder.findClass("managed.Player2", scope());
        assertNotNull(canonical);
        assertNull(finder.findClass("Player2", scope()));
        assertNull(finder.findClass("unrelated.Player2", scope()));
        assertNull(finder.findClass("managed.API_FAKE", scope()));
        PsiClass fromShortNames = new JypeShortNamesCache(getProject()).getClassesByName("Player2", scope())[0];
        assertSame(canonical, fromShortNames);
    }

    public void testVirtualIdentityDoesNotDependOnLookupScope() {
        objectSystem("managed", "API_ROLE");
        PsiClass owner = myFixture.addClass("package managed; public class Owner {}");
        JypePsiElementFinder finder = new JypePsiElementFinder(getProject());
        PsiClass global = finder.findClass("managed.API_ROLE", scope());
        PsiClass module = finder.findClass("managed.API_ROLE", owner.getResolveScope());
        assertNotNull(global);
        assertSame("Lookup scope must not create another Java type", global, module);
        assertTrue(com.intellij.psi.search.PsiSearchScopeUtil.isInScope(owner.getResolveScope(), global));
    }

    public void testQualifiedAndWildcardReferencesShareCanonicalType() {
        objectSystem("managed", "API_ROLE");
        PsiFile file = myFixture.addFileToProject("managed/deep/Consumer.java", """
                package managed.deep;
                import managed.*;
                public class Consumer {
                    API_ROLE role;
                    managed.API_ROLE qualified;
                }
                """);
        PsiClass cls = ((PsiJavaFile) file).getClasses()[0];
        PsiClass canonical = new JypePsiElementFinder(getProject()).findClass("managed.API_ROLE", scope());
        assertNotNull(canonical);
        for (PsiField field : cls.getFields()) {
            assertSame(canonical, ((PsiClassType) field.getType()).resolve());
        }
    }

    public void testInheritanceOverridesAndMostSpecificPackage() {
        myFixture.addClass("package bounds; public class Parent {}");
        myFixture.addClass("package bounds; public class Child {}");
        define("library", "{@fr.thegamerfarmer.jype.annotations.TypeDef(name=\"TYPE\", bound=bounds.Parent.class)}");
        define("consumer", """
                inherit="library", overrides={
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name="TYPE", bound=bounds.Child.class)
                }
                """);
        PsiClass consumer = myFixture.addClass("package consumer.deep; public class Consumer {}");
        assertEquals("bounds.Child", JypeTypeSystemReader.getTypeDefMap(consumer).get("TYPE").boundFqn());
        objectSystem("consumer.deep", "OTHER");
        assertEquals(Map.of("OTHER", new JypeTypeSystemReader.TypeDefInfo("OTHER", "java.lang.Object")),
                JypeTypeSystemReader.getTypeDefMap(consumer));
    }

    public void testSystemsDoNotLeakAndEmptySystemShadowsParent() {
        objectSystem("one", "TYPE");
        objectSystem("two", "TYPE");
        define("one.empty", "{}");
        PsiClass plain = myFixture.addClass("package plain; public class Plain {}");
        PsiClass empty = myFixture.addClass("package one.empty; public class Empty {}");
        assertTrue(JypeTypeSystemReader.getManagedNames(plain).isEmpty());
        assertTrue(JypeTypeSystemReader.getManagedNames(empty).isEmpty());
        PsiClass[] classes = new JypeShortNamesCache(getProject()).getClassesByName("TYPE", scope());
        assertEquals(2, classes.length);
        assertNotSame(classes[0], classes[1]);
    }

    public void testInterfaceBoundMethodsResolveThroughInheritance() {
        myFixture.addClass("package bounds; public interface Contract { int count(); }");
        define("managed",
                "{@fr.thegamerfarmer.jype.annotations.TypeDef(name=\"Element\", bound=bounds.Contract.class)}");
        PsiClass virtual = new JypePsiElementFinder(getProject()).findClass("managed.Element", scope());
        assertNotNull(virtual);
        assertEquals(1, virtual.getImplementsListTypes().length);
        assertEquals(1, virtual.findMethodsByName("count", true).length);
    }

    public void testInvalidSystemShadowsParentAndReportsItsError() {
        objectSystem("managed", "TYPE");
        define("managed.invalid", "{@fr.thegamerfarmer.jype.annotations.TypeDef(name=\"bad.name\", bound=Object.class)}");
        PsiClass cls = myFixture.addClass("package managed.invalid; public class Consumer {}");
        assertTrue(JypeTypeSystemReader.getManagedNames(cls).isEmpty());
        var result = JypeTypeSystemReader.findTypeSystem(getProject(), "managed.invalid", scope());
        assertNotNull(result);
        assertNotNull(result.error());
        assertTrue(result.error().contains("identifier"));
    }

    public void testScopeExcludesOtherSystemDeclarations() {
        PsiJavaFile definition = define("one", "{@fr.thegamerfarmer.jype.annotations.TypeDef(name=\"TYPE\", bound=Object.class)}");
        objectSystem("two", "TYPE");
        GlobalSearchScope excluded = scope().intersectWith(
                GlobalSearchScope.notScope(GlobalSearchScope.fileScope(definition)));
        PsiClass[] classes = new JypeShortNamesCache(getProject()).getClassesByName("TYPE", excluded);
        assertEquals(1, classes.length);
        assertEquals("two.TYPE", classes[0].getQualifiedName());
    }
}
