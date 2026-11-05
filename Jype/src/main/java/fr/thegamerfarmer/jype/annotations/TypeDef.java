package fr.thegamerfarmer.jype.annotations;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Associates a type-parameter name with a declared class or interface bound.
 * Used in {@link TypeSystem#value()} and {@link TypeSystem#overrides()}.
 *
 * <pre>{@code
 * @TypeDef(name = "TYPE_A", bound = BaseA.class)
 * }</pre>
 *
 * {@code name} must exactly match the type-parameter name in the superclass's
 * Java declaration (for example, {@code TYPE_A} in
 * {@code Base<TYPE_A extends BaseA<TYPE_A, TYPE_B>, TYPE_B extends BaseB<TYPE_A, TYPE_B>>}).
 */
@Retention(RetentionPolicy.CLASS)
@Target({})
public @interface TypeDef {
    /**
     * Type-parameter name as it appears in the library source.
     * Invalid identifiers, keywords, and duplicate names are rejected.
     *
     * @return case-sensitive Java identifier with no whitespace
     */
    String name();
    /**
     * Bound used for managed parameters and mapping for concrete types.
     * Generic classes are allowed; their managed parameters are resolved recursively.
     *
     * @return a class or interface, never a primitive type or array
     */
    Class<?> bound();
}
