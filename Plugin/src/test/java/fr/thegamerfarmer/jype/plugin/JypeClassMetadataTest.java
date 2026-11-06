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
                    managedParameters={"PLAYER", "ROLE"}, declaredParameters="T", typeSystem="api")
                public class Service<T, PLAYER, ROLE> {}
                """);
        var data = JypeClassMetadata.read(cls);
        assertNotNull(data);
        assertEquals(Set.of("PLAYER", "ROLE"), data.managed());
        assertEquals(List.of("T"), data.declared());
        assertEquals("api", data.typeSystem());
        assertSame(data, JypeClassMetadata.read(cls));
        PsiClass defaults = myFixture.addClass("""
                package library;
                @fr.thegamerfarmer.jype.annotations.JypeClass(managedParameters="PLAYER", typeSystem="api")
                public class DefaultService<PLAYER> {}
                """);
        assertEquals(List.of(), JypeClassMetadata.read(defaults).declared());
    }

    public void testConstantNamesAndExplicitVersionAreSupported() {
        myFixture.addClass("package library; public class Names { public static final String PLAYER = \"PLAYER\"; }");
        PsiClass cls = myFixture.addClass("""
                package library;
                @fr.thegamerfarmer.jype.annotations.JypeClass(version=1,
                    managedParameters=Names.PLAYER, declaredParameters={"PLAYER"}, typeSystem="api")
                public class Service<PLAYER> {}
                """);
        assertEquals(Set.of("PLAYER"), JypeClassMetadata.read(cls).managed());
        assertEquals(List.of("PLAYER"), JypeClassMetadata.read(cls).declared());
    }

    public void testMalformedOrUnknownMetadataDoesNotFallBackToNames() {
        objectSystem("api", "PLAYER");
        int index = 0;
        for (String attributes : List.of(
                "version=2, managedParameters=\"PLAYER\", typeSystem=\"api\"",
                "version=unknown, managedParameters=\"PLAYER\", typeSystem=\"api\"",
                "managedParameters={\"PLAYER\", \"PLAYER\"}, typeSystem=\"api\"",
                "managedParameters=\"MISSING\", typeSystem=\"api\"",
                "managedParameters=\"PLAYER\", declaredParameters={\"PLAYER\", \"PLAYER\"}, typeSystem=\"api\"",
                "managedParameters=\"PLAYER\", declaredParameters=\"MISSING\", typeSystem=\"api\"",
                "managedParameters=\"\", typeSystem=\"api\"",
                "managedParameters=unknown, typeSystem=\"api\"",
                "managedParameters=\"PLAYER\", typeSystem=\"\"",
                "managedParameters=\"PLAYER\", typeSystem=\"api..child\"",
                "managedParameters=\"PLAYER\", typeSystem=\"api.class\"",
                "managedParameters=\"PLAYER\", typeSystem=\"api.\"",
                "managedParameters=\"class\", typeSystem=\"api\"",
                "managedParameters=\"PLAYER\", declaredParameters=\"class\", typeSystem=\"api\"",
                "managedParameters=\"PLAYER\", typeSystem=unknown",
                "typeSystem=\"api\"")) {
            PsiClass cls = myFixture.addClass("package api; @fr.thegamerfarmer.jype.annotations.JypeClass("
                    + attributes + ") public class Invalid" + index++ + "<PLAYER> {}");
            assertNull(attributes, JypeClassMetadata.read(cls));
        }
    }

    public void testManagedOrderMatchesBinaryParametersButDeclaredOrderIsOriginal() {
        PsiClass invalid = myFixture.addClass("""
                package api;
                @fr.thegamerfarmer.jype.annotations.JypeClass(
                    managedParameters={"ROLE", "PLAYER"}, typeSystem="api")
                public class InvalidOrder<T, PLAYER, ROLE> {}
                """);
        assertNull(JypeClassMetadata.read(invalid));
        PsiClass valid = myFixture.addClass("""
                package api;
                @fr.thegamerfarmer.jype.annotations.JypeClass(managedParameters={"PLAYER", "ROLE"},
                    declaredParameters={"PLAYER", "T"}, typeSystem="api.child")
                public class OriginalOrder<T, PLAYER, ROLE> {}
                """);
        assertEquals(List.of("PLAYER", "T"), JypeClassMetadata.read(valid).declared());
    }

    public void testLegacyMetadataDoesNotManageOrdinaryNames() {
        objectSystem("api", "PLAYER");
        PsiClass cls = myFixture.addClass("package api; public class Service<T, PLAYER> {}");
        assertEquals(Set.of("PLAYER"), JypeClassMetadata.read(cls).managed());
        assertEquals(List.of("T", "PLAYER"), JypeClassMetadata.read(cls).declared());
        assertEquals("api", JypeClassMetadata.read(cls).typeSystem());
        PsiClass ordinary = myFixture.addClass("package api; public class Box<T> {}");
        assertNull(JypeClassMetadata.read(ordinary));
    }
}
