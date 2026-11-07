package fr.thegamerfarmer.jype.plugin;

import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.testFramework.DumbModeTestUtils;

import java.util.*;

/** Original member types survive erasure and managed method parameters never leak into presentation. */
public class JypeMemberMetadataTest extends JypeJavaFixture {
    private void family() {
        myFixture.addClass("package api; public class NodeType {}");
        define("api", "@fr.thegamerfarmer.jype.annotations.TypeDef(name=\"ENTITY\", bound=api.NodeType.class)");
    }

    public void testOriginalAliasAndImportsSurviveLoweredStorage() {
        family();
        PsiClass cls = myFixture.addClass("""
                package api;
                @fr.thegamerfarmer.jype.annotations.JypeClass(managedParameters={}, typeSystem="api")
                public class Registry {
                    @fr.thegamerfarmer.jype.annotations.JypeMember(sourceType="List<ENTITY>")
                    public java.util.List<api.NodeType> entities;
                    @fr.thegamerfarmer.jype.annotations.JypeMember(sourceType="ENTITY")
                    public static <ENTITY extends api.NodeType> ENTITY first() { return null; }
                    @fr.thegamerfarmer.jype.annotations.JypeMember(
                        sourceType="ENTITY", declaredParameters={"ENTITY"})
                    public <ENTITY extends Number> ENTITY shadow(ENTITY value) { return value; }
                }
                """);
        PsiField field = cls.getFields()[0];
        assertEquals("List<ENTITY>", JypePublicPresentation.memberType(field, field.getType()));
        PsiMethod first = cls.findMethodsByName("first", false)[0];
        assertEquals("public static ENTITY first()", JypePublicPresentation.signature(first));
        var metadata = JypeMemberMetadata.read(first);
        assertNotNull(metadata);
        assertSame(metadata, JypeMemberMetadata.read(first));
        assertEquals("ENTITY", metadata.sourceType());
        assertFalse(metadata.generated());
        String doc = new JypeDocumentationProvider().generateDoc(first, null);
        assertTrue(doc, doc.contains("public static ENTITY first()"));
        assertTrue(doc, doc.contains("psi_element://api.ENTITY"));
        String signature = JypePublicPresentation.signature(cls.findMethodsByName("shadow", false)[0]);
        assertTrue(signature, signature.contains("<ENTITY extends Number> ENTITY shadow(ENTITY value)"));
        PsiType actual = field.getType();
        DumbModeTestUtils.runInDumbModeSynchronously(getProject(),
                () -> assertSame(actual, JypeSourceMemberType.sourceType(field, actual)));
    }

    public void testNestedArraysWildcardsAndQualifiedNames() {
        family();
        PsiClass cls = myFixture.addClass("""
                package api;
                @fr.thegamerfarmer.jype.annotations.JypeClass(managedParameters={}, typeSystem="api")
                public class Registry {
                    @fr.thegamerfarmer.jype.annotations.JypeMember(sourceType="Map<String, List<? extends ENTITY[]>>")
                    public java.util.Map<String, java.util.List<? extends api.NodeType[]>> values;
                    @fr.thegamerfarmer.jype.annotations.JypeMember(sourceType="java.util.List<? super ENTITY>")
                    public java.util.List<? super api.NodeType> lower;
                    @fr.thegamerfarmer.jype.annotations.JypeMember(sourceType="java.util.List<?>")
                    public java.util.List<?> any;
                    @fr.thegamerfarmer.jype.annotations.JypeMember(sourceType="int")
                    public int count;
                }
                """);
        assertEquals("Map<String, List<? extends ENTITY[]>>",
                JypePublicPresentation.memberType(cls.getFields()[0], cls.getFields()[0].getType()));
        assertEquals("List<? super ENTITY>",
                JypePublicPresentation.memberType(cls.getFields()[1], cls.getFields()[1].getType()));
        assertEquals("List<?>", JypePublicPresentation.memberType(cls.getFields()[2], cls.getFields()[2].getType()));
        assertEquals("int", JypePublicPresentation.memberType(cls.getFields()[3], cls.getFields()[3].getType()));
    }

    public void testInvalidMetadataAndTypeSyntaxFallBackToJava() {
        family();
        int index = 0;
        for (String attributes : List.of("version=2", "version=missing", "sourceType=missing",
                "generated=missing", "declaredParameters={\"T\", \"T\"}",
                "declaredParameters=\"class\"", "declaredParameters=missing", "declaredParameters=\"OTHER\"")) {
            PsiClass cls = myFixture.addClass("package api; public class Invalid" + index++ + " { "
                    + "@fr.thegamerfarmer.jype.annotations.JypeMember(" + attributes
                    + ") public <T> T value(T value) { return value; } }");
            assertNull(attributes, JypeMemberMetadata.read(cls.getMethods()[0]));
        }
        PsiClass cls = myFixture.addClass("""
                package api;
                @fr.thegamerfarmer.jype.annotations.JypeClass(managedParameters={}, typeSystem="api")
                public class InvalidTypes {
                    @fr.thegamerfarmer.jype.annotations.JypeMember(sourceType="Missing<ENTITY>")
                    public Object missing;
                    @fr.thegamerfarmer.jype.annotations.JypeMember(sourceType="List<")
                    public Object malformed;
                    @fr.thegamerfarmer.jype.annotations.JypeMember(declaredParameters="T")
                    public Object invalidField;
                    @fr.thegamerfarmer.jype.annotations.JypeMember
                    public Object empty;
                }
                """);
        for (PsiField field : cls.getFields()) assertEquals("Object", JypePublicPresentation.memberType(field, field.getType()));
        assertNull(JypeMemberMetadata.read(cls.getFields()[2]));
        assertFalse(JypeMemberMetadata.isTechnical(cls));
    }

    public void testOnlyProvenTechnicalHelpersAreRemovedFromCompletion() {
        family();
        PsiClass cls = myFixture.addClass("""
                package api;
                @fr.thegamerfarmer.jype.annotations.JypeClass(managedParameters={}, typeSystem="api")
                public class Registry {
                    @fr.thegamerfarmer.jype.annotations.JypeMember(generated=true)
                    public static Object helperTechnical() { return null; }
                    @fr.thegamerfarmer.jype.annotations.JypeMember
                    public static Object helperBusiness() { return null; }
                    public static Object $jype$user() { return null; }
                }
                """);
        assertTrue(JypeMemberMetadata.isTechnical(cls.findMethodsByName("helperTechnical", false)[0]));
        assertFalse(JypeMemberMetadata.isTechnical(cls.findMethodsByName("$jype$user", false)[0]));
        PsiFile file = myFixture.addFileToProject("api/Consumer.java",
                "package api; class Consumer { void use() { Registry.helper<caret> } }");
        myFixture.configureFromExistingVirtualFile(file.getVirtualFile());
        var items = myFixture.completeBasic();
        if (items == null) assertTrue(myFixture.getFile().getText().contains("Registry.helperBusiness("));
        else {
            assertTrue(Arrays.stream(items).anyMatch(item -> item.getLookupString().equals("helperBusiness")));
            assertFalse(Arrays.stream(items).anyMatch(item -> item.getLookupString().equals("helperTechnical")));
        }
        PsiMethod method = cls.findMethodsByName("helperTechnical", false)[0];
        assertSame(method.getReturnType(), JypeSourceMemberType.sourceType(method, method.getReturnType()));
        assertFalse(JypeMemberMetadata.isTechnical(PsiTreeUtil.getParentOfType(method, PsiFile.class)));
    }
}
