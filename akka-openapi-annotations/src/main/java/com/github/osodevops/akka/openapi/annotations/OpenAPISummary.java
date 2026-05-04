package com.github.osodevops.akka.openapi.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Provides a human-readable summary for an HTTP operation in OpenAPI documentation.
 *
 * <p>Maps directly to the {@code summary} field of an OpenAPI 3.x operation object.
 * The summary is intended to be a short, single-line description that appears as the
 * operation label in tools such as Swagger UI.</p>
 *
 * <h2>Example Usage</h2>
 * <pre>{@code
 * @Get("/metrics/daily/{date}")
 * @OpenAPISummary("Get daily metrics")
 * @OpenAPIResponse(status = "200", description = "Daily metrics retrieved")
 * public MetricsResponse getDailyMetrics(String date) {
 *     // ...
 * }
 * }</pre>
 *
 * <p>The resulting OpenAPI YAML will contain:</p>
 * <pre>{@code
 * paths:
 *   /reporting/metrics/daily/{date}:
 *     get:
 *       summary: Get daily metrics
 * }</pre>
 *
 * @since 1.0.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OpenAPISummary {

    /**
     * The summary text for this operation.
     *
     * <p>Should be a short (ideally one-line) description of what the operation does.
     * Avoid duplicating the longer {@code description} — use this for a concise label.</p>
     *
     * @return the operation summary
     */
    String value();
}

