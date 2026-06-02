package com.github.osodevops.akka.openapi.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Container annotation for multiple {@link OpenAPIQueryParam} annotations.
 *
 * <p>This annotation is used automatically when multiple {@code @OpenAPIQueryParam}
 * annotations are placed on the same method.</p>
 *
 * @since 1.5.0
 * @see OpenAPIQueryParam
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OpenAPIQueryParams {

    /**
     * The array of query parameter annotations.
     *
     * @return the query parameter annotations
     */
    OpenAPIQueryParam[] value();
}
