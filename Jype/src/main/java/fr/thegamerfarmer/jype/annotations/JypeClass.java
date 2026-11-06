package fr.thegamerfarmer.jype.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Versioned public-view origin metadata. Generic JVM signatures remain authoritative.
 * Readers must reject unsupported versions rather than guess managed positions.
 */
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface JypeClass {
    /** Metadata format version. */
    int version() default 1;
    /** Names of technical parameters in final JVM declaration order. */
    String[] managedParameters();
    /** Class parameters written in the source, before any Jype transformation. */
    String[] declaredParameters() default {};
    /** Package declaring the effective type system. */
    String typeSystem();
}
