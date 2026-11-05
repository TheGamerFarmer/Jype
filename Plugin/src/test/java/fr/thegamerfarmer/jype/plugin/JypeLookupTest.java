package fr.thegamerfarmer.jype.plugin;

import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.search.GlobalSearchScope;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Exercises the public lookup contracts used by completion and navigation. */
public class JypeLookupTest extends JypeJavaFixture {
    public void testNamesAreDistinctAndProcessorsCanStop() {
        objectSystem("one", "Item");
        objectSystem("two", "Item");
        objectSystem("three", "Other");
        JypeShortNamesCache cache = new JypeShortNamesCache(getProject());
        assertEquals(List.of("Item", "Other"), Arrays.stream(cache.getAllClassNames()).sorted().toList());
        List<PsiClass> visited = new ArrayList<>();
        assertTrue(cache.processClassesWithName("Item", value -> {
            visited.add(value);
            return true;
        }, scope(), null));
        assertEquals(2, visited.size());
        visited.clear();
        assertFalse(cache.processClassesWithName("Item", value -> {
            visited.add(value);
            return false;
        }, scope(), null));
        assertEquals(1, visited.size());
        assertTrue(cache.processClassesWithName("Missing", value -> {
            fail("No callback for an unknown class");
            return false;
        }, scope(), null));
    }

    public void testFinderArraysAndPackagesRespectScope() {
        var declaration = define("one",
                "{@fr.thegamerfarmer.jype.annotations.TypeDef(name=\"Item\", bound=Object.class)}");
        myFixture.addClass("package unrelated; public class Normal {}");
        JypePsiElementFinder finder = new JypePsiElementFinder(getProject());
        PsiClass[] classes = finder.findClasses("one.Item", scope());
        assertEquals(1, classes.length);
        assertEmpty(finder.findClasses("one.Absent", scope()));
        var pkg = JavaPsiFacade.getInstance(getProject()).findPackage("one");
        assertNotNull(pkg);
        assertSame(classes[0], finder.getClasses(pkg, scope())[0]);
        var unrelated = JavaPsiFacade.getInstance(getProject()).findPackage("unrelated");
        assertNotNull(unrelated);
        assertEmpty(finder.getClasses(unrelated, scope()));
        GlobalSearchScope excluded = scope().intersectWith(
                GlobalSearchScope.notScope(GlobalSearchScope.fileScope(declaration)));
        assertEmpty(finder.getClasses(pkg, excluded));
        assertEmpty(finder.findClasses("one.Item", excluded));
    }

    public void testClassOnlyCacheNeverInventsMembers() {
        JypeShortNamesCache cache = new JypeShortNamesCache(getProject());
        assertEmpty(cache.getMethodsByName("method", scope()));
        assertEmpty(cache.getMethodsByNameIfNotMoreThan("method", scope(), 1));
        assertEmpty(cache.getFieldsByName("field", scope()));
        assertEmpty(cache.getFieldsByNameIfNotMoreThan("field", scope(), 1));
        assertEmpty(cache.getAllMethodNames());
        assertEmpty(cache.getAllFieldNames());
        assertTrue(cache.processMethodsWithName("method", scope(), ignored -> {
            fail("Jype does not contribute methods");
            return false;
        }));
        assertTrue(cache.processFieldsWithName("field", ignored -> {
            fail("Jype does not contribute fields");
            return false;
        }, scope(), null));
    }
}
