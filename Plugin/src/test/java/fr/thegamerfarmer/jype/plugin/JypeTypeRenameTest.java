package fr.thegamerfarmer.jype.plugin;

import com.intellij.psi.*;

import java.util.Arrays;

/** Source rename origins, inherited families, conflicts and undo behavior. */
public class JypeTypeRenameTest extends JypeJavaFixture {
    public void testRenameSearchRetainsReferencesAcrossManyFiles() {
        objectSystem("managed", "TYPE");
        var files = new java.util.ArrayList<PsiFile>();
        for (int i = 0; i < 64; i++) {
            files.add(myFixture.addFileToProject("managed/Consumer" + i + ".java",
                    "package managed; class Consumer" + i + " { TYPE value; java.util.List<managed.TYPE> values; }"));
        }
        PsiClass type = new JypePsiElementFinder(getProject()).findClass("managed.TYPE", scope());
        assertNotNull(type);
        var processor = new JypeTypeRenameProcessor();
        for (int i = 0; i < 20; i++) {
            var references = processor.findReferences(type, scope(), false);
            assertEquals("Search " + i + " must retain both usages in each file and the declaration",
                    129, references.size());
        }
        new com.intellij.refactoring.rename.RenameProcessor(getProject(), type, "MARK", false, false).run();
        for (PsiFile file : files) {
            assertTrue(file.getText(), file.getText().contains("MARK value;"));
            assertTrue(file.getText(), file.getText().contains("java.util.List<managed.MARK>"));
        }
    }

    public void testRenameFromCodeUpdatesDeclarationAndOnlyRelatedUsages() {
        PsiFile definition = define("managed",
                "{@fr.thegamerfarmer.jype.annotations.TypeDef(name=\"TYPE\", bound=Object.class)}");
        objectSystem("unrelated", "TYPE");
        PsiFile other = myFixture.addFileToProject("unrelated/Other.java",
                "package unrelated; public class Other { TYPE value; }");
        PsiFile consumer = myFixture.addFileToProject("managed/deep/Consumer.java", """
                package managed.deep;
                public class Consumer {
                    TYPE value;
                    java.util.List<managed.TYPE> values;
                    <TYPE> TYPE identity(TYPE value) { return value; }
                    String text = "TYPE";
                }
                """);
        myFixture.configureFromExistingVirtualFile(consumer.getVirtualFile());
        myFixture.getEditor().getCaretModel().moveToOffset(consumer.getText().indexOf("TYPE value") + 1);
        myFixture.renameElementAtCaretUsingHandler("MARK");
        assertTrue(definition.getText(), definition.getText().replaceAll("\\s+", "").contains("name=\"MARK\""));
        assertTrue(consumer.getText(), consumer.getText().contains("MARK value;"));
        assertTrue(consumer.getText(), consumer.getText().contains("java.util.List<managed.MARK>"));
        assertTrue(consumer.getText(), consumer.getText().contains("<TYPE> TYPE identity(TYPE value)"));
        assertTrue(consumer.getText(), consumer.getText().contains("\"TYPE\""));
        assertTrue(other.getText(), other.getText().contains("TYPE value;"));
        assertNull(new JypePsiElementFinder(getProject()).findClass("managed.TYPE", scope()));
        assertNotNull(new JypePsiElementFinder(getProject()).findClass("managed.MARK", scope()));
        myFixture.checkHighlighting(false, false, false);
        var editor = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(getProject())
                .getSelectedEditor(consumer.getVirtualFile());
        assertNotNull(editor);
        var undo = com.intellij.openapi.command.undo.UndoManager.getInstance(getProject());
        assertTrue(undo.isUndoAvailable(editor));
        com.intellij.openapi.ui.TestDialog previous = com.intellij.openapi.ui.TestDialogManager.setTestDialog(
                com.intellij.openapi.ui.TestDialog.OK);
        try {
            undo.undo(editor);
        } finally {
            com.intellij.openapi.ui.TestDialogManager.setTestDialog(previous);
        }
        PsiDocumentManager.getInstance(getProject()).commitAllDocuments();
        assertTrue(definition.getText(), definition.getText().replaceAll("\\s+", "").contains("name=\"TYPE\""));
        assertTrue(consumer.getText(), consumer.getText().contains("TYPE value;"));
    }

    public void testRenameFromPackageInfoUpdatesInheritedSystemsAndOverrides() {
        myFixture.addClass("package bounds; public class Child {}");
        PsiFile parent = define("library",
                "{@fr.thegamerfarmer.jype.annotations.TypeDef(name=\"TYPE\", bound=Object.class)}");
        PsiFile child = define("consumer", """
                inherit="library",
                overrides={@fr.thegamerfarmer.jype.annotations.TypeDef(name="TYPE", bound=bounds.Child.class)}
                """);
        PsiFile usage = myFixture.addFileToProject("consumer/deep/Consumer.java",
                "package consumer.deep; public class Consumer { TYPE value; }");
        myFixture.configureFromExistingVirtualFile(parent.getVirtualFile());
        myFixture.getEditor().getCaretModel().moveToOffset(parent.getText().indexOf("\"TYPE\"") + 2);
        myFixture.renameElementAtCaretUsingHandler("MARK");
        assertTrue(parent.getText(), parent.getText().replaceAll("\\s+", "").contains("name=\"MARK\""));
        assertTrue(child.getText(), child.getText().replaceAll("\\s+", "").contains("name=\"MARK\""));
        assertTrue(usage.getText(), usage.getText().contains("MARK value;"));
        var result = JypeTypeSystemReader.findTypeSystem(getProject(), "consumer.deep", scope());
        assertNotNull(result);
        assertNull(result.error());
        assertEquals("bounds.Child", result.typeDefs().get(0).boundFqn());
    }

    public void testRenameConflictsAreReportedBeforeEditingSources() {
        objectSystem("managed", "TYPE");
        objectSystem("managed.child", "ENTITY");
        PsiClass type = new JypePsiElementFinder(getProject()).findClass("managed.TYPE", scope());
        assertNotNull(type);
        var conflicts = new com.intellij.util.containers.MultiMap<PsiElement, String>();
        new JypeTypeRenameProcessor().findExistingNameConflicts(type, "TYPE", conflicts);
        assertTrue(conflicts.toString(), conflicts.isEmpty());
        myFixture.addClass("package managed; public class ENTITY {}");
        new JypeTypeRenameProcessor().findExistingNameConflicts(type, "ENTITY", conflicts);
        assertFalse(conflicts.isEmpty());
    }

    public void testRenameDetectsConflictsInUsageSubpackages() {
        objectSystem("managed", "TYPE");
        myFixture.addClass("package managed.deep; public class MARK {}");
        myFixture.addFileToProject("managed/deep/Consumer.java",
                "package managed.deep; public class Consumer { TYPE value; }");
        PsiClass type = new JypePsiElementFinder(getProject()).findClass("managed.TYPE", scope());
        assertNotNull(type);
        var conflicts = new com.intellij.util.containers.MultiMap<PsiElement, String>();
        new JypeTypeRenameProcessor().findExistingNameConflicts(type, "MARK", conflicts);
        assertFalse(conflicts.isEmpty());
        assertTrue(conflicts.toString(), conflicts.values().stream().anyMatch(text -> text.contains("managed.deep")));
    }

    public void testRenameRejectsConstantNamesWithoutChangingTheConstant() {
        PsiClass names = myFixture.addClass(
                "package names; public class Names { public static final String TYPE_NAME = \"TYPE\"; }");
        PsiFile definition = define("managed",
                "{@fr.thegamerfarmer.jype.annotations.TypeDef(name=names.Names.TYPE_NAME, bound=Object.class)}");
        PsiClass type = new JypePsiElementFinder(getProject()).findClass("managed.TYPE", scope());
        assertNotNull(type);
        var conflicts = new com.intellij.util.containers.MultiMap<PsiElement, String>();
        new JypeTypeRenameProcessor().findExistingNameConflicts(type, "MARK", conflicts);
        assertFalse(conflicts.isEmpty());
        assertTrue(definition.getText().contains("names.Names.TYPE_NAME"));
        assertTrue(names.getText().contains("\"TYPE\""));
    }

    public void testRenameFromOverrideKeepsInheritedFamilyConsistent() {
        PsiFile parent = define("library",
                "{@fr.thegamerfarmer.jype.annotations.TypeDef(name=\"TYPE\", bound=Object.class)}");
        PsiFile child = define("consumer", """
                inherit="library",
                overrides={@fr.thegamerfarmer.jype.annotations.TypeDef(name="TYPE", bound=String.class)}
                """);
        PsiFile usage = myFixture.addFileToProject("library/deep/Consumer.java",
                "package library.deep; public class Consumer { TYPE value; }");
        myFixture.configureFromExistingVirtualFile(child.getVirtualFile());
        myFixture.getEditor().getCaretModel().moveToOffset(child.getText().indexOf("\"TYPE\"") + 2);
        var target = myFixture.getFile().findReferenceAt(myFixture.getCaretOffset()).resolve();
        assertNotNull(target);
        var references = new JypeTypeRenameProcessor().findReferences(target, scope(), false);
        assertEquals(references.stream().map(ref -> ref.getElement().getContainingFile().getName() + ":"
                + ref.getElement().getText()).toList().toString(), 3, references.size());
        var usages = new com.intellij.refactoring.rename.RenameProcessor(
                getProject(), target, "MARK", false, false).findUsages();
        assertEquals(Arrays.stream(usages).map(info -> info.getElement() + ":" + info.getReference()).toList().toString(),
                3, usages.length);
        var classified = com.intellij.refactoring.rename.RenameProcessor.classifyUsages(
                java.util.List.of(target), java.util.List.of(usages));
        assertEquals(classified.toString(), 3, classified.get(target).size());
        myFixture.renameElementAtCaretUsingHandler("MARK");
        assertTrue(parent.getText(), parent.getText().replaceAll("\\s+", "").contains("name=\"MARK\""));
        assertTrue(child.getText(), child.getText().replaceAll("\\s+", "").contains("name=\"MARK\""));
        assertTrue(usage.getText(), usage.getText().contains("MARK value;"));
    }
}
