package fr.thegamerfarmer.jype.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares or inherits a type system; place it on a {@code package-info.java}.
 *
 * <p><b>In the library — declaring the type system:</b>
 * <pre>{@code
 * @TypeSystem({
 *     @TypeDef(name = "TYPE_A", bound = BaseA.class),
 *     @TypeDef(name = "TYPE_B", bound = BaseB.class),
 *     // ...as many types as needed
 * })
 * package com.example.mylib;
 * }</pre>
 *
 * <p><b>In a consumer project — using the defaults:</b>
 * <pre>{@code
 * @TypeSystem(inherit = "com.example.mylib")
 * package com.example.myplugin;
 * }</pre>
 *
 * <p><b>In a consumer project — with overrides:</b>
 * <pre>{@code
 * @TypeSystem(
 *     inherit = "com.example.mylib",
 *     overrides = {
 *         @TypeDef(name = "TYPE_A", bound = ConcreteA.class),
 *         @TypeDef(name = "TYPE_B", bound = ConcreteB.class),
 *     }
 * )
 * package com.example.myplugin.custom;
 * }</pre>
 *
 * <p>Subpackages inherit the nearest type system unless they declare another
 * one. CLASS retention allows inheritance from a compiled library. Names are
 * ordered and unique; cycles and unknown overrides are compilation errors.
 *
 * <p>Jype completes managed generic declarations and references, then injects
 * their recursive bounds. An override replaces a mapping and is not required
 * to be a subtype of the previous bound. javac still checks Java generic
 * constraints in the resulting hierarchy.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.PACKAGE)
public @interface TypeSystem {

    /**
     * Declares the types and their default values.
     * Used on the package of the library that defines the type system.
     *
     * @return ordered definitions; must be empty if {@link #inherit()} is set
     */
    TypeDef[] value() default {};

    /**
     * Qualified name of the package carrying the parent {@code @TypeSystem}.
     * The parent package must itself have this annotation. Cannot be combined
     * with non-empty {@link #value()} definitions.
     *
     * @return parent package name, or an empty string for a standalone definition
     */
    String inherit() default "";

    /**
     * Types to override relative to the inherited type system.
     * Each name must already exist in the effective parent system, including
     * its transitive parents. Explicitly declaring this attribute requires a
     * non-empty {@link #inherit()} value, even for an empty array.
     *
     * @return ordered overrides with no duplicate names
     */
    TypeDef[] overrides() default {};
}