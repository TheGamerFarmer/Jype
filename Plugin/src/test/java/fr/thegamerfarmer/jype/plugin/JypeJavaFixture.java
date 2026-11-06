package fr.thegamerfarmer.jype.plugin;

import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.testFramework.LightProjectDescriptor;
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase;

/** Java 17 PSI fixture with the annotation API used by Jype integration tests. */
public abstract class JypeJavaFixture extends LightJavaCodeInsightFixtureTestCase {
    @Override
    protected LightProjectDescriptor getProjectDescriptor() {
        return JAVA_17;
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        assertNotNull("Java fixture must provide java.lang.Object",
                JavaPsiFacade.getInstance(getProject()).findClass("java.lang.Object", scope()));
        myFixture.addClass("""
                package fr.thegamerfarmer.jype.annotations;
                public @interface TypeDef { String name(); Class<?> bound(); }
                """);
        myFixture.addClass("""
                package fr.thegamerfarmer.jype.annotations;
                public @interface TypeSystem {
                    TypeDef[] value() default {};
                    String inherit() default "";
                    TypeDef[] overrides() default {};
                }
                """);
        myFixture.addClass("""
                package fr.thegamerfarmer.jype.annotations;
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
                @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)
                public @interface JypeClass {
                    int version() default 1;
                    String[] managedParameters();
                    String[] declaredParameters() default {};
                    String typeSystem();
                }
                """);
        myFixture.addClass("""
                package fr.thegamerfarmer.jype.annotations;
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
                @java.lang.annotation.Target({java.lang.annotation.ElementType.METHOD, java.lang.annotation.ElementType.FIELD})
                public @interface JypeMember {
                    int version() default 1;
                    String sourceType() default "";
                    String[] declaredParameters() default {};
                    boolean generated() default false;
                }
                """);
    }

    protected PsiJavaFile define(String pkg, String body) {
        return (PsiJavaFile) myFixture.addFileToProject(pkg.replace('.', '/') + "/package-info.java",
                "@fr.thegamerfarmer.jype.annotations.TypeSystem(" + body + ") package " + pkg + ";");
    }

    protected void objectSystem(String pkg, String name) {
        define(pkg, "{@fr.thegamerfarmer.jype.annotations.TypeDef(name=\"" + name + "\", bound=Object.class)}");
    }

    protected GlobalSearchScope scope() {
        return GlobalSearchScope.allScope(getProject());
    }
}
