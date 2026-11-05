package fr.thegamerfarmer.jype.plugin;

/** Invalid declarations must produce diagnostics rather than usable partial systems. */
public class JypeDeclarationValidationTest extends JypeJavaFixture {
    public void testExplicitOverridesRequireNonEmptyInheritanceBeforeValidatingDefinitions() {
        String[] bodies = {
                "overrides={}", "inherit=\"\", overrides={}", "value={}, overrides={}",
                "overrides=@fr.thegamerfarmer.jype.annotations.TypeDef(name=\"Item\", bound=Object.class)",
                "overrides=@fr.thegamerfarmer.jype.annotations.TypeDef(name=\"\", bound=Absent.class)"
        };
        for (int i = 0; i < bodies.length; i++) {
            String pkg = "orphan" + i;
            define(pkg, bodies[i]);
            var result = JypeTypeSystemReader.findTypeSystem(getProject(), pkg, scope());
            assertNotNull(result);
            assertEquals(JypeTypeSystemResolver.MISSING_INHERIT, result.error());
            assertEmpty(result.typeDefs());
        }
        define("standalone", "");
        assertNull(JypeTypeSystemReader.findTypeSystem(getProject(), "standalone", scope()).error());
    }

    public void testTransitiveOverridesAndExplicitEmptyOverrides() {
        objectSystem("base", "Item");
        define("middle", "inherit=\"base\"");
        define("child", """
                inherit="middle",
                overrides=@fr.thegamerfarmer.jype.annotations.TypeDef(name="Item", bound=String.class)
                """);
        var child = JypeTypeSystemReader.findTypeSystem(getProject(), "child", scope());
        assertNotNull(child);
        assertNull(child.error());
        assertEquals("java.lang.String", child.typeDefs().get(0).boundFqn());
        define("empty", "inherit=\"middle\", overrides={}");
        var empty = JypeTypeSystemReader.findTypeSystem(getProject(), "empty", scope());
        assertNotNull(empty);
        assertNull(empty.error());
        assertEquals("Item", empty.typeDefs().get(0).name());
        define("unknown", """
                inherit="middle",
                overrides=@fr.thegamerfarmer.jype.annotations.TypeDef(name="Other", bound=Object.class)
                """);
        var unknown = JypeTypeSystemReader.findTypeSystem(getProject(), "unknown", scope());
        assertNotNull(unknown);
        assertEquals("Unknown @TypeSystem override: Other", unknown.error());
    }

    public void testMissingAndUnresolvedBoundsAreRejected() {
        assertInvalid("missing",
                "{@fr.thegamerfarmer.jype.annotations.TypeDef(name=\"Item\")}");
        assertInvalid("unknown",
                "{@fr.thegamerfarmer.jype.annotations.TypeDef(name=\"Item\", bound=Absent.class)}");
    }

    public void testDuplicateAndInvalidNamesAreRejected() {
        assertInvalid("duplicate", """
                {
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name="Item", bound=Object.class),
                    @fr.thegamerfarmer.jype.annotations.TypeDef(name="Item", bound=String.class)
                }
                """);
        assertInvalid("empty",
                "{@fr.thegamerfarmer.jype.annotations.TypeDef(name=\"\", bound=Object.class)}");
        assertInvalid("punctuation",
                "{@fr.thegamerfarmer.jype.annotations.TypeDef(name=\"not-a-type\", bound=Object.class)}");
    }

    public void testUnknownParentsAndOverridesAreRejected() {
        assertInvalid("orphan", "inherit=\"absent\"");
        objectSystem("base", "Item");
        assertInvalid("override", """
                inherit="base",
                overrides=@fr.thegamerfarmer.jype.annotations.TypeDef(name="Other", bound=Object.class)
                """);
    }

    public void testOverrideReplacesTheInheritedBound() {
        objectSystem("base", "Item");
        define("derived", """
                inherit="base",
                overrides=@fr.thegamerfarmer.jype.annotations.TypeDef(name="Item", bound=String.class)
                """);
        var result = JypeTypeSystemReader.findTypeSystem(getProject(), "derived", scope());
        assertNotNull(result);
        assertNull(result.error());
        assertEquals(1, result.typeDefs().size());
        assertEquals("java.lang.String", result.typeDefs().get(0).boundFqn());
    }

    private void assertInvalid(String pkg, String body) {
        define(pkg, body);
        var result = JypeTypeSystemReader.findTypeSystem(getProject(), pkg, scope());
        assertNotNull(pkg, result);
        assertNotNull("Missing diagnostic for " + pkg, result.error());
        assertTrue("Invalid systems must not leak definitions: " + pkg, result.typeDefs().isEmpty());
    }
}
