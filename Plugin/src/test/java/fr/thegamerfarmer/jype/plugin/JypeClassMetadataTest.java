package fr.thegamerfarmer.jype.plugin;

import com.intellij.psi.PsiClass;

import java.util.List;
import java.util.Set;

/** Compiled-view metadata is authoritative, validated and modification-tracked. */
public class JypeClassMetadataTest extends JypeJavaFixture {
    public void testVersionOneReadsArraysAndDefaults() {
        PsiClass cls = myFixture.addClass("""
                package library;
                @fr.thegamerfarmer.jype.annotations.JypeClass(
                    managedParameters={"ENTITY", "CLUSTER"}, declaredParameters="T", typeSystem="api")
                public class Service<T, ENTITY, CLUSTER> {}
                """);
        var data = JypeClassMetadata.read(cls);
        assertNotNull(data);
        assertEquals(Set.of("ENTITY", "CLUSTER"), data.managed());
        assertEquals(List.of("T"), data.declared());
        assertEquals("api", data.typeSystem());
        assertSame(data, JypeClassMetadata.read(cls));
        PsiClass defaults = myFixture.addClass("""
                package library;
                @fr.thegamerfarmer.jype.annotations.JypeClass(managedParameters="ENTITY", typeSystem="api")
                public class DefaultService<ENTITY> {}
                """);
        assertEquals(List.of(), JypeClassMetadata.read(defaults).declared());
    }

    public void testConstantNamesAndExplicitVersionAreSupported() {
        myFixture.addClass("package library; public class Names { public static final String ENTITY = \"ENTITY\"; }");
        PsiClass cls = myFixture.addClass("""
                package library;
                @fr.thegamerfarmer.jype.annotations.JypeClass(version=1,
                    managedParameters=Names.ENTITY, declaredParameters={"ENTITY"}, typeSystem="api")
                public class Service<ENTITY> {}
                """);
        assertEquals(Set.of("ENTITY"), JypeClassMetadata.read(cls).managed());
        assertEquals(List.of("ENTITY"), JypeClassMetadata.read(cls).declared());
    }

    public void testMalformedOrUnknownMetadataDoesNotFallBackToNames() {
        objectSystem("api", "ENTITY");
        int index = 0;
        for (String attributes : List.of(
                "version=2, managedParameters=\"ENTITY\", typeSystem=\"api\"",
                "version=unknown, managedParameters=\"ENTITY\", typeSystem=\"api\"",
                "managedParameters={\"ENTITY\", \"ENTITY\"}, typeSystem=\"api\"",
                "managedParameters=\"MISSING\", typeSystem=\"api\"",
                "managedParameters=\"ENTITY\", declaredParameters={\"ENTITY\", \"ENTITY\"}, typeSystem=\"api\"",
                "managedParameters=\"ENTITY\", declaredParameters=\"MISSING\", typeSystem=\"api\"",
                "managedParameters=\"\", typeSystem=\"api\"",
                "managedParameters=unknown, typeSystem=\"api\"",
                "managedParameters=\"ENTITY\", typeSystem=\"\"",
                "managedParameters=\"ENTITY\", typeSystem=\"api..child\"",
                "managedParameters=\"ENTITY\", typeSystem=\"api.class\"",
                "managedParameters=\"ENTITY\", typeSystem=\"api.\"",
                "managedParameters=\"class\", typeSystem=\"api\"",
                "managedParameters=\"ENTITY\", declaredParameters=\"class\", typeSystem=\"api\"",
                "managedParameters=\"ENTITY\", typeSystem=unknown",
                "typeSystem=\"api\"")) {
            PsiClass cls = myFixture.addClass("package api; @fr.thegamerfarmer.jype.annotations.JypeClass("
                    + attributes + ") public class Invalid" + index++ + "<ENTITY> {}");
            assertNull(attributes, JypeClassMetadata.read(cls));
        }
    }

    public void testManagedOrderMatchesBinaryParametersButDeclaredOrderIsOriginal() {
        PsiClass invalid = myFixture.addClass("""
                package api;
                @fr.thegamerfarmer.jype.annotations.JypeClass(
                    managedParameters={"CLUSTER", "ENTITY"}, typeSystem="api")
                public class InvalidOrder<T, ENTITY, CLUSTER> {}
                """);
        assertNull(JypeClassMetadata.read(invalid));
        PsiClass valid = myFixture.addClass("""
                package api;
                @fr.thegamerfarmer.jype.annotations.JypeClass(managedParameters={"ENTITY", "CLUSTER"},
                    declaredParameters={"ENTITY", "T"}, typeSystem="api.child")
                public class OriginalOrder<T, ENTITY, CLUSTER> {}
                """);
        assertEquals(List.of("ENTITY", "T"), JypeClassMetadata.read(valid).declared());
    }

    public void testLegacyMetadataDoesNotManageOrdinaryNames() {
        objectSystem("api", "ENTITY");
        PsiClass cls = myFixture.addClass("package api; public class Service<T, ENTITY> {}");
        assertEquals(Set.of("ENTITY"), JypeClassMetadata.read(cls).managed());
        assertEquals(List.of("T", "ENTITY"), JypeClassMetadata.read(cls).declared());
        assertEquals("api", JypeClassMetadata.read(cls).typeSystem());
        PsiClass ordinary = myFixture.addClass("package api; public class Box<T> {}");
        assertNull(JypeClassMetadata.read(ordinary));
    }
}
