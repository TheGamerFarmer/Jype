package fr.thegamerfarmer.jype.plugin;

import com.intellij.codeInsight.completion.CompletionType;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.Lookup;

import java.util.Arrays;
import java.util.List;

public class JypeAnnotationCompletionTest extends JypeJavaFixture {
    private List<String> complete(String body) {
        var file = define("consumer", body);
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        LookupElement[] elements = myFixture.complete(CompletionType.BASIC);
        return elements == null ? List.of()
                : Arrays.stream(elements).map(LookupElement::getLookupString).toList();
    }

    private void parent() {
        define("library", """
                {@fr.thegamerfarmer.jype.annotations.TypeDef(name="J_A", bound=Object.class),
                 @fr.thegamerfarmer.jype.annotations.TypeDef(name="J_B", bound=Object.class)}
                """);
    }

    public void testInheritSuggestsVisibleSystemsNotSelfOrDescendants() {
        parent();
        define("descendant", "inherit=\"consumer\"");
        define("invalid", "inherit=\"absent\"");
        objectSystem("other", "VALUE");
        var names = complete("inherit=\"<caret>\"");
        assertContainsElements(names, "library", "other");
        assertFalse(names.toString(), names.contains("consumer"));
        assertFalse(names.toString(), names.contains("descendant"));
        assertFalse(names.toString(), names.contains("invalid"));
    }

    public void testInheritCompletionReplacesRemainingSuffix() {
        parent();
        objectSystem("library.other", "VALUE");
        var names = complete("inherit=\"lib<caret>bad\"");
        assertContainsElements(names, "library", "library.other");
        var lookup = myFixture.getLookup();
        lookup.setCurrentItem(lookup.getItems().stream()
                .filter(item -> item.getLookupString().equals("library")).findFirst().orElseThrow());
        myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);
        assertTrue(myFixture.getFile().getText(), myFixture.getFile().getText().contains("inherit=\"library\""));
    }

    public void testInheritExcludesValidDescendantWhenEditingExistingParent() {
        parent();
        define("consumer", "inherit=\"library\"");
        define("library.child", "inherit=\"consumer\"");
        objectSystem("library.other", "VALUE");
        myFixture.configureFromExistingVirtualFile(
                myFixture.findFileInTempDir("consumer/package-info.java"));
        myFixture.getEditor().getCaretModel().moveToOffset(
                myFixture.getFile().getText().indexOf("\"library\"") + "\"library".length());
        var variants = myFixture.complete(CompletionType.BASIC);
        assertNotNull(variants);
        var names = Arrays.stream(variants).map(LookupElement::getLookupString).toList();
        assertContainsElements(names, "library", "library.other");
        assertFalse(names.toString(), names.contains("library.child"));
    }

    public void testOverrideCompletionReplacesSuffixAndPreservesQuotes() {
        parent();
        var names = complete("""
                inherit="library", overrides=
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name="J<caret>bad", bound=Object.class)
                """);
        if (myFixture.getLookup() != null) {
            assertContainsElements(names, "J_A");
            myFixture.finishLookup(Lookup.NORMAL_SELECT_CHAR);
        }
        assertTrue(myFixture.getFile().getText(), myFixture.getFile().getText().contains("name=\"J_A\""));
    }

    public void testOverrideSuggestsOnlyInheritedNamesAndExcludesUsedNames() {
        parent();
        objectSystem("other", "UNRELATED");
        var names = complete("""
                inherit="library", overrides={
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name="J_A", bound=String.class),
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name="<caret>", bound=Object.class)
                }
                """);
        // IntelliJ may insert the single available candidate directly.
        assertTrue(names.toString(), names.contains("J_B")
                || myFixture.getFile().getText().contains("name=\"J_B\""));
        assertFalse(names.toString(), names.contains("J_A"));
        assertFalse(names.toString(), names.contains("UNRELATED"));
    }

    public void testOverrideIncludesDefinitionsInheritedByParent() {
        parent();
        define("derived", "inherit=\"library\"");
        var names = complete("""
                inherit="derived", overrides=
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name="<caret>", bound=Object.class)
                """);
        assertContainsElements(names, "J_A", "J_B");
    }

    public void testStandaloneDefinitionsDoNotSuggestOverrideNames() {
        parent();
        var names = complete("""
                {@fr.thegamerfarmer.jype.annotations.TypeDef(name="<caret>", bound=Object.class)}
                """);
        assertFalse(names.toString(), names.contains("J_A"));
        assertFalse(names.toString(), names.contains("J_B"));
    }

    public void testUnknownParentHasNoOverrideSuggestions() {
        parent();
        var names = complete("""
                inherit="absent", overrides=
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name="<caret>", bound=Object.class)
                """);
        assertFalse(names.toString(), names.contains("J_A"));
    }

    public void testAbsentParentHasNoOverrideSuggestions() {
        parent();
        assertEmpty(complete("""
                overrides=@fr.thegamerfarmer.jype.annotations.TypeDef(name="<caret>", bound=Object.class)
                """));
    }

    public void testEmptyParentHasNoOverrideSuggestions() {
        parent();
        assertEmpty(complete("""
                inherit="", overrides=@fr.thegamerfarmer.jype.annotations.TypeDef(name="<caret>", bound=Object.class)
                """));
    }

    public void testImportedAnnotationsAndQualifiedPackagePrefix() {
        objectSystem("fr.example.api", "J_A");
        objectSystem("fr.example.other", "J_B");
        myFixture.addFileToProject("fr/example/ordinary/Ordinary.java",
                "package fr.example.ordinary; class Ordinary {}");
        var file = myFixture.addFileToProject("consumer/package-info.java", """
                @TypeSystem(inherit="fr.example.<caret>")
                package consumer;
                import fr.thegamerfarmer.jype.annotations.TypeSystem;
                """);
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        var variants = myFixture.complete(CompletionType.BASIC);
        assertNotNull(variants);
        assertSameElements(Arrays.stream(variants).map(LookupElement::getLookupString).toList(),
                "fr.example.api", "fr.example.other");
    }

    public void testImportedOverrideSuggestsExistingCurrentNameAndUnusedNames() {
        parent();
        var file = myFixture.addFileToProject("consumer/package-info.java", """
                @TypeSystem(inherit="library", overrides={
                    @TypeDef(name="J_A<caret>", bound=Object.class)
                })
                package consumer;
                import fr.thegamerfarmer.jype.annotations.*;
                """);
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        myFixture.complete(CompletionType.BASIC);
        assertTrue(myFixture.getFile().getText(), myFixture.getFile().getText().contains("name=\"J_A\""));
    }

    public void testImportedOverrideArrayOffersOnlyInheritedNames() {
        parent();
        var file = myFixture.addFileToProject("consumer/package-info.java", """
                @TypeSystem(inherit="library", overrides={
                    @TypeDef(name="<caret>", bound=Object.class)
                })
                package consumer;
                import fr.thegamerfarmer.jype.annotations.TypeSystem;
                import fr.thegamerfarmer.jype.annotations.TypeDef;
                """);
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        var variants = myFixture.complete(CompletionType.BASIC);
        assertNotNull(variants);
        assertSameElements(Arrays.stream(variants).map(LookupElement::getLookupString).toList(), "J_A", "J_B");
    }

    public void testStandaloneNameDoesNotOfferWordsAlreadyDeclaredInFile() {
        parent();
        var names = complete("""
                value={
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name="CREATED_NAME", bound=Object.class),
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name="<caret>", bound=Object.class)
                }, inherit="library"
                """);
        assertEmpty(names);
    }

    public void testOverrideDoesNotExcludeNamesInValueArray() {
        parent();
        var names = complete("""
                value={@fr.thegamerfarmer.jype.annotations.TypeDef(name="J_A", bound=Object.class)},
                inherit="library", overrides={
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name="<caret>", bound=Object.class)
                }
                """);
        assertContainsElements(names, "J_A", "J_B");
    }
}
