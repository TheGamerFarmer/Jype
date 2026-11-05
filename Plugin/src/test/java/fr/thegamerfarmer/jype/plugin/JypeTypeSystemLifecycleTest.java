package fr.thegamerfarmer.jype.plugin;

import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.psi.*;
import com.intellij.testFramework.DumbModeTestUtils;

/** Cache invalidation, recursion guards, indexing and cancellation. */
public class JypeTypeSystemLifecycleTest extends JypeJavaFixture {
    public void testCacheTracksEditedBounds() {
        myFixture.addClass("package bounds; public class First {}");
        myFixture.addClass("package bounds; public class Second {}");
        PsiJavaFile definition = define("managed",
                "{@fr.thegamerfarmer.jype.annotations.TypeDef(name=\"TYPE\", bound=bounds.First.class)}");
        JypePsiElementFinder finder = new JypePsiElementFinder(getProject());
        PsiClass before = finder.findClass("managed.TYPE", scope());
        assertNotNull(before);
        PsiDocumentManager manager = PsiDocumentManager.getInstance(getProject());
        var document = manager.getDocument(definition);
        assertNotNull(document);
        WriteCommandAction.runWriteCommandAction(getProject(), () -> {
            document.setText(document.getText().replace("bounds.First", "bounds.Second"));
            manager.commitDocument(document);
        });
        PsiClass after = finder.findClass("managed.TYPE", scope());
        assertNotNull(after);
        assertNotSame(before, after);
        assertEquals("bounds.Second", after.getExtendsListTypes()[0].getCanonicalText());
    }

    public void testUnrelatedAnnotationsAndInheritanceCyclesAreIgnored() {
        myFixture.addClass("package unrelated; public @interface TypeSystem { String value(); }");
        myFixture.addFileToProject("fake/package-info.java", "@unrelated.TypeSystem(\"TYPE\") package fake;");
        define("cycle.first", "inherit=\"cycle.second\"");
        define("cycle.second", "inherit=\"cycle.first\"");
        var systems = JypeTypeSystemReader.getTypeSystems(getProject(), scope());
        assertEquals(2, systems.size());
        assertTrue(systems.stream().allMatch(system -> system.error().contains("Cyclic")));
    }

    public void testDumbModeDoesNotAccessIndexes() {
        objectSystem("managed", "TYPE");
        DumbModeTestUtils.runInDumbModeSynchronously(getProject(), () -> {
            assertTrue(JypeTypeSystemReader.getTypeSystems(getProject(), scope()).isEmpty());
            assertNull(new JypePsiElementFinder(getProject()).findClass("managed.TYPE", scope()));
            assertEmpty(new JypeShortNamesCache(getProject()).getAllClassNames());
        });
        assertNotNull(new JypePsiElementFinder(getProject()).findClass("managed.TYPE", scope()));
    }

    public void testCancellationIsPropagated() {
        objectSystem("managed", "TYPE");
        var indicator = new com.intellij.openapi.progress.EmptyProgressIndicator();
        try {
            com.intellij.openapi.progress.ProgressManager.getInstance().runProcess(() -> {
                indicator.cancel();
                JypeTypeSystemReader.getTypeSystems(getProject(), scope());
            }, indicator);
            fail("Cancellation must not be swallowed");
        } catch (com.intellij.openapi.progress.ProcessCanceledException expected) {
            assertNotNull(new JypePsiElementFinder(getProject()).findClass("managed.TYPE", scope()));
        }
    }
}
