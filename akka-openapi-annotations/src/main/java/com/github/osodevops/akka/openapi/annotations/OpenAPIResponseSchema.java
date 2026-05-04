package com.github.osodevops.akka.openapi.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Explicitly declares the response body schema for an endpoint method that returns
 * a low-level HTTP response type (such as {@code HttpResponse}).
 *
 * <p>When an endpoint method returns {@code HttpResponse} (or
 * {@code CompletionStage<HttpResponse>}) the plugin cannot automatically determine
 * the payload type from the method signature. Use this annotation to explicitly
 * specify the domain type that should be used as the response body schema in the
 * generated OpenAPI specification.</p>
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
 * <p>When the method returns a domain type directly (for example
 * {@code CustomerResponse} or {@code CompletionStage<CustomerResponse>}), this
 * annotation is not required.</p>
 *
 * @since 1.0.2
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
