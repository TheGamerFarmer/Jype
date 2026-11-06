package fr.thegamerfarmer.jype.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Original signature spelling and technical helper provenance for public-view readers. */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.FIELD})
public @interface JypeMember {
    /** Metadata format version. */
    int version() default 1;
    /** Original field or return type, before alias expansion and static lowering. */
    String sourceType() default "";
    /** Method type parameters actually declared by the author. */
    String[] declaredParameters() default {};
    /** True only for engine-generated helpers, not annotation-processor generated business methods. */
    boolean generated() default false;
}
