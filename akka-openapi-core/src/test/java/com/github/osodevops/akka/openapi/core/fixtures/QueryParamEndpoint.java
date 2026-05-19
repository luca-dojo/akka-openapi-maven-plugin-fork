package com.github.osodevops.akka.openapi.core.fixtures;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import com.github.osodevops.akka.openapi.annotations.OpenAPIQueryParam;
import com.github.osodevops.akka.openapi.annotations.OpenAPISummary;

/**
 * Endpoint fixture demonstrating {@code @OpenAPIQueryParam} for dynamic query parameters.
 *
 * <p>Covers Integer, String, and Boolean parameter types so that the integration tests
 * can verify each type is correctly extracted and serialised into the OpenAPI spec.</p>
 */
@HttpEndpoint("/reports")
public class QueryParamEndpoint {

    @Get("/list")
    @OpenAPISummary("List reports")
    @OpenAPIQueryParam(
        name = "limit",
        description = "Maximum number of results to return",
        type = Integer.class,
        format = "int32",
        minimum = "1",
        defaultValue = "20"
    )
    @OpenAPIQueryParam(
        name = "search",
        description = "Filter by keyword"
    )
    @OpenAPIQueryParam(
        name = "includeArchived",
        description = "Include archived records in the response",
        type = Boolean.class,
        defaultValue = "false"
    )
    public HttpResponse listReports() {
        return null;
    }

    @Get("/paged")
    @OpenAPISummary("List paged results")
    @OpenAPIQueryParam(
        name = "page",
        description = "Page number",
        type = Integer.class,
        format = "int32",
        minimum = "1",
        defaultValue = "1"
    )
    @OpenAPIQueryParam(
        name = "size",
        description = "Page size",
        type = Integer.class,
        format = "int32",
        minimum = "1",
        maximum = "100",
        defaultValue = "10"
    )
    public HttpResponse getPagedResults() {
        return null;
    }
}
