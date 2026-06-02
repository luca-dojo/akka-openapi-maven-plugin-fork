package com.github.osodevops.akka.openapi.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares an explicit query parameter for an endpoint operation.
 *
 * <p>Use this annotation when query parameters are accessed dynamically at runtime
 * (e.g. via {@code requestContext().queryParams()}) rather than as typed Java method
 * parameters, or when you need to document constraints such as a minimum/maximum value
 * or a default that the generated spec would otherwise miss.</p>
 *
 * <p>When applied to a method that already has a typed Java parameter with the same
 * name inferred as a query parameter, the annotation enriches the inferred entry
 * (description, minimum, maximum, default value, format) rather than duplicating it.</p>
 *
 * @since 1.5.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Repeatable(OpenAPIQueryParams.class)
public @interface OpenAPIQueryParam {

    /**
     * The query parameter name as it appears in the request URL.
     *
     * @return the parameter name
     */
    String name();

    /**
     * A human-readable description of the parameter.
     *
     * @return the description (empty string means no description)
     */
    String description() default "";

    /**
     * Whether this parameter must be present in the request.
     *
     * @return {@code true} if required, {@code false} (default) if optional
     */
    boolean required() default false;

    /**
     * The Java type of the parameter value.
     *
     * <p>Use {@code Void.class} (the default) to inherit the type from an already-inferred
     * parameter with the same name, or to default to {@code String.class} when no inferred
     * parameter exists.</p>
     *
     * @return the parameter type class
     */
    Class<?> type() default Void.class;

    /**
     * An optional OpenAPI format hint for the schema (e.g. {@code "int32"}, {@code "int64"},
     * {@code "date-time"}, {@code "uuid"}).
     *
     * @return the format string (empty string means no format override)
     */
    String format() default "";

    /**
     * The default value of the parameter, expressed as a string.
     *
     * <p>The string is parsed into the resolved Java {@link #type()} by the annotation
     * extractor.  For example, {@code defaultValue = "20"} with {@code type = Integer.class}
     * produces an integer default of {@code 20} in the generated spec.</p>
     *
     * @return the default value string (empty string means no default)
     */
    String defaultValue() default "";

    /**
     * The inclusive lower bound for numeric parameters, expressed as a string.
     *
     * <p>Example: {@code minimum = "1"} adds {@code minimum: 1} to the schema.
     * The value is parsed as a {@link java.math.BigDecimal}.</p>
     *
     * @return the minimum value string (empty string means no minimum)
     */
    String minimum() default "";

    /**
     * The inclusive upper bound for numeric parameters, expressed as a string.
     *
     * <p>Example: {@code maximum = "100"} adds {@code maximum: 100} to the schema.
     * The value is parsed as a {@link java.math.BigDecimal}.</p>
     *
     * @return the maximum value string (empty string means no maximum)
     */
    String maximum() default "";
}
