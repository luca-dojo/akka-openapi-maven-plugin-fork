package com.github.osodevops.akka.openapi.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Explicitly declares the response body schema for an endpoint method that returns
 * an HTTP wrapper type (such as {@code HttpResponse}).
 *
 * <p>When an endpoint method returns a raw {@code HttpResponse} (or similar HTTP-level
 * wrapper) the plugin cannot automatically determine the payload type. Use this
 * annotation to explicitly specify the domain type that should be used as the
 * response body schema in the generated OpenAPI specification.</p>
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * @Get("/{customerId}")
 * @OpenAPIResponseSchema(CustomerResponse.class)
 * public HttpResponse getCustomer(String customerId) {
 *     return HttpResponses.ok(service.getCustomer(customerId));
 * }
 * }</pre>
 *
 * <p>When the method return type is a parameterized generic wrapper (e.g.
 * {@code HttpResponse<CustomerResponse>}), the plugin will automatically infer the
 * inner type without requiring this annotation.</p>
 *
 * @since 1.0.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OpenAPIResponseSchema {

    /**
     * The Java class to use as the response body schema.
     *
     * @return the response payload type
     */
    Class<?> value();
}
