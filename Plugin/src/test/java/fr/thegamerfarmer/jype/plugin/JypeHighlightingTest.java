package fr.thegamerfarmer.jype.plugin;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.daemon.impl.HighlightInfoType;
import com.intellij.lang.annotation.HighlightSeverity;
import com.intellij.psi.*;

import java.util.Arrays;

/** End-to-end Java highlighting and preservation of genuine diagnostics. */
public class JypeHighlightingTest extends JypeJavaFixture {
    public void testStartHierarchyCollectionInferenceAndInspection() {
        define("managed",
                """
                {
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name="J_B", bound=managed.types.Leaf.class),
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name="J_C", bound=managed.containers.Container.class)
                }
                """);
        myFixture.addFileToProject("managed/types/Leaf.java", """
                package managed.types;
                public class Leaf {
                    public J_B newInstance() { return null; }
                    public J_C getContainer() { return null; }
                }
                """);
        myFixture.addFileToProject("managed/containers/Container.java", """
                package managed.containers;
                public class Container {
                    private final java.util.List<J_B> itemsInContainerActive = new java.util.ArrayList<>();
                    public java.util.List<J_B> getItemsInContainerActive() { return itemsInContainerActive; }
                }
                """);
        myFixture.addFileToProject("managed/utils/AbstractHub.java", """
                package managed.utils;
                public class AbstractHub {
                    protected java.util.Map<String, J_B> items;
                    protected java.util.List<J_B> activeItems;
                }
                """);
        myFixture.addFileToProject("managed/commands/CommandBase.java",
                "package managed.commands; public class CommandBase extends managed.utils.AbstractHub {}");
        PsiFile start = myFixture.addFileToProject("managed/commands/Start.java", """
                package managed.commands;
                class Start extends CommandBase {
                    void run() {
                        java.util.List<J_B> items = new java.util.ArrayList<>(this.items.values());
                        J_B item = items.get(0);
                        J_B itemClone = item.newInstance();
                        item.getContainer().getItemsInContainerActive().add(itemClone);
                        activeItems.add(itemClone);
                    }
                }
                """);
        myFixture.configureFromExistingVirtualFile(start.getVirtualFile());
        myFixture.enableInspections(new com.siyeh.ig.bugs.MismatchedCollectionQueryUpdateInspection());
        var call = com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(myFixture.getFile(), PsiMethodCallExpression.class)
                .stream().filter(c -> "add".equals(c.getMethodExpression().getReferenceName())).findFirst().orElseThrow();
        PsiClassType list = (PsiClassType) call.getMethodExpression().getQualifierExpression().getType();
        PsiClassType required = (PsiClassType) list.getParameters()[0];
        PsiClassType provided = (PsiClassType) call.getArgumentList().getExpressions()[0].getType();
        assertNotNull("Inherited collection element type must resolve", required.resolve());
        assertSame("Required " + required.getCanonicalText() + " vs provided " + provided.getCanonicalText(),
                required.resolve(), provided.resolve());
        myFixture.checkHighlighting(true, false, false);
    }

    public void testRealCollectionQueryWithoutUpdateIsStillReported() {
        objectSystem("managed", "J_B");
        myFixture.configureByText("Consumer.java", """
                package managed;
                class Consumer {
                    int count() {
                        java.util.List<J_B> items = new java.util.ArrayList<>();
                        return items.size();
                    }
                }
                """);
        myFixture.enableInspections(new com.siyeh.ig.bugs.MismatchedCollectionQueryUpdateInspection());
        assertTrue(myFixture.doHighlighting(HighlightSeverity.WARNING).stream()
                .anyMatch(info -> "Contents of collection 'items' are queried, but never updated"
                        .equals(info.getDescription())));
    }

    public void testRealErrorsMentioningManagedTypesAreNotHidden() {
        objectSystem("managed", "J_A");
        PsiFile file = myFixture.configureByText("Consumer.java", """
                package managed;
                class Consumer { J_A item; void test() { item.nonexistent(); } }
                """);
        JypeHighlightFilter filter = new JypeHighlightFilter();
        for (String message : Arrays.asList(
                "Cannot resolve method 'nonexistent' in 'J_A'",
                "Incompatible types: java.lang.String cannot be converted to managed.J_A",
                "Dereference of 'item' will produce 'NullPointerException'",
                "Condition 'item == null' is always 'true'")) {
            HighlightInfo info = HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR)
                    .range(file.getText().indexOf("nonexistent"), file.getText().indexOf("nonexistent") + 11)
                    .description(message).severity(HighlightSeverity.ERROR).create();
            assertNotNull(info);
            assertTrue(message, filter.accept(info, file));
        }
    }

    public void testActualHighlightingResolvesBoundMembersWithoutHidingWrongCalls() {
        myFixture.addClass("package bounds; public class BoundType { public int score() { return 1; } }");
        define("managed", "{@fr.thegamerfarmer.jype.annotations.TypeDef(name=\"J_TWO\", bound=bounds.BoundType.class)}");
        myFixture.configureByText("Consumer.java", """
                package managed;
                class Consumer {
                    J_TWO item;
                    int score() { return item.score(); }
                    void wrong() { item.<error descr="Cannot resolve method 'missing' in 'J_TWO'">missing</error>(); }
                }
                """);
        myFixture.checkHighlighting(false, false, false);
    }

    public void testInvalidAnnotationHasAnActualErrorHighlight() {
        PsiJavaFile definition = define("managed",
                "{@fr.thegamerfarmer.jype.annotations.TypeDef(name=\"bad.name\", bound=Object.class)}");
        myFixture.configureFromExistingVirtualFile(definition.getVirtualFile());
        var errors = myFixture.doHighlighting(HighlightSeverity.ERROR);
        assertTrue(errors.toString(), errors.stream()
                .anyMatch(error -> "@TypeDef name must be a Java identifier".equals(error.getDescription())));
    }
}
