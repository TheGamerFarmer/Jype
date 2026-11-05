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
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name="API_ROLE", bound=managed.roles.Role.class),
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name="API_CAMP", bound=managed.camps.Camp.class)
                }
                """);
        myFixture.addFileToProject("managed/roles/Role.java", """
                package managed.roles;
                public class Role {
                    public API_ROLE newInstance() { return null; }
                    public API_CAMP getCamp() { return null; }
                }
                """);
        myFixture.addFileToProject("managed/camps/Camp.java", """
                package managed.camps;
                public class Camp {
                    private final java.util.List<API_ROLE> rolesInCampsActive = new java.util.ArrayList<>();
                    public java.util.List<API_ROLE> getRolesInCampsActive() { return rolesInCampsActive; }
                }
                """);
        myFixture.addFileToProject("managed/utils/APIClass.java", """
                package managed.utils;
                public class APIClass {
                    protected java.util.Map<String, API_ROLE> roles;
                    protected java.util.List<API_ROLE> rolesActive;
                }
                """);
        myFixture.addFileToProject("managed/commands/CommandInterface.java",
                "package managed.commands; public class CommandInterface extends managed.utils.APIClass {}");
        PsiFile start = myFixture.addFileToProject("managed/commands/Start.java", """
                package managed.commands;
                class Start extends CommandInterface {
                    void run() {
                        java.util.List<API_ROLE> roles = new java.util.ArrayList<>(this.roles.values());
                        API_ROLE role = roles.get(0);
                        API_ROLE roleClone = role.newInstance();
                        role.getCamp().getRolesInCampsActive().add(roleClone);
                        rolesActive.add(roleClone);
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
        objectSystem("managed", "API_ROLE");
        myFixture.configureByText("Consumer.java", """
                package managed;
                class Consumer {
                    int count() {
                        java.util.List<API_ROLE> roles = new java.util.ArrayList<>();
                        return roles.size();
                    }
                }
                """);
        myFixture.enableInspections(new com.siyeh.ig.bugs.MismatchedCollectionQueryUpdateInspection());
        assertTrue(myFixture.doHighlighting(HighlightSeverity.WARNING).stream()
                .anyMatch(info -> "Contents of collection 'roles' are queried, but never updated"
                        .equals(info.getDescription())));
    }

    public void testRealErrorsMentioningManagedTypesAreNotHidden() {
        objectSystem("managed", "API_PLAYER");
        PsiFile file = myFixture.configureByText("Consumer.java", """
                package managed;
                class Consumer { API_PLAYER player; void test() { player.nonexistent(); } }
                """);
        JypeHighlightFilter filter = new JypeHighlightFilter();
        for (String message : Arrays.asList(
                "Cannot resolve method 'nonexistent' in 'API_PLAYER'",
                "Incompatible types: java.lang.String cannot be converted to managed.API_PLAYER",
                "Dereference of 'player' will produce 'NullPointerException'",
                "Condition 'player == null' is always 'true'")) {
            HighlightInfo info = HighlightInfo.newHighlightInfo(HighlightInfoType.ERROR)
                    .range(file.getText().indexOf("nonexistent"), file.getText().indexOf("nonexistent") + 11)
                    .description(message).severity(HighlightSeverity.ERROR).create();
            assertNotNull(info);
            assertTrue(message, filter.accept(info, file));
        }
    }

    public void testActualHighlightingResolvesBoundMembersWithoutHidingWrongCalls() {
        myFixture.addClass("package bounds; public class Player { public int strength() { return 1; } }");
        define("managed", "{@fr.thegamerfarmer.jype.annotations.TypeDef(name=\"Player2\", bound=bounds.Player.class)}");
        myFixture.configureByText("Consumer.java", """
                package managed;
                class Consumer {
                    Player2 player;
                    int strength() { return player.strength(); }
                    void wrong() { player.<error descr="Cannot resolve method 'missing' in 'Player2'">missing</error>(); }
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
