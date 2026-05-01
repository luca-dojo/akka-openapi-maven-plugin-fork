package com.github.osodevops.akka.openapi.core;

import com.github.osodevops.akka.openapi.core.config.PluginConfiguration;
import com.github.osodevops.akka.openapi.core.fixtures.GetCustomerResponse;
import com.github.osodevops.akka.openapi.core.fixtures.LowLevelHttpResponseEndpoint;
import com.github.osodevops.akka.openapi.core.model.EndpointMetadata;
import com.github.osodevops.akka.openapi.core.model.OperationMetadata;
import com.github.osodevops.akka.openapi.core.model.ResponseMetadata;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests for low-level HttpResponse schema extraction.
 */
class HttpResponseSchemaIntegrationTest {

    private List<String> logMessages;
    private AkkaAnnotationExtractor extractor;
    private OpenAPIModelBuilder modelBuilder;

    @BeforeEach
    void setUp() {
        logMessages = new ArrayList<>();
        extractor = new AkkaAnnotationExtractor(logMessages::add);
        modelBuilder = new OpenAPIModelBuilder(PluginConfiguration.builder()
            .apiTitle("Test API")
            .apiVersion("1.0.0")
            .build(), logMessages::add);
    }

    @Test
    void shouldUseExplicitSchemaForRawHttpResponse() {
        EndpointMetadata endpoint = extractor.extractEndpoint(LowLevelHttpResponseEndpoint.class);

        ResponseMetadata response = findSuccessResponse(endpoint, "getCustomer");

        assertThat(response.getResponseType()).isEqualTo(GetCustomerResponse.class);
        assertThat(responseSchema(endpoint, "/customers/{customerId}", "200").get$ref())
            .isEqualTo("#/components/schemas/GetCustomerResponse");
    }

    @Test
    void shouldOmitContentSchemaForRawHttpResponseWithoutAnnotation() {
        EndpointMetadata endpoint = extractor.extractEndpoint(LowLevelHttpResponseEndpoint.class);

        ResponseMetadata response = findSuccessResponse(endpoint, "getRawCustomer");
        ApiResponse apiResponse = apiResponse(endpoint, "/customers/{customerId}/raw", "200");

        assertThat(response.getResponseType()).isNull();
        assertThat(apiResponse.getContent()).isNull();
        assertThat(logMessages)
            .anyMatch(message -> message.contains("Raw HttpResponse return type on getRawCustomer"));
    }

    @Test
    void shouldUseExplicitSchemaForAsyncRawHttpResponse() {
        EndpointMetadata endpoint = extractor.extractEndpoint(LowLevelHttpResponseEndpoint.class);

        ResponseMetadata response = findSuccessResponse(endpoint, "getCustomerAsync");

        assertThat(response.getResponseType()).isEqualTo(GetCustomerResponse.class);
        assertThat(responseSchema(endpoint, "/customers/{customerId}/async", "200").get$ref())
            .isEqualTo("#/components/schemas/GetCustomerResponse");
    }

    @Test
    void shouldUnwrapCompletionStageResponsePayload() {
        EndpointMetadata endpoint = extractor.extractEndpoint(LowLevelHttpResponseEndpoint.class);

        ResponseMetadata response = findSuccessResponse(endpoint, "getCustomerDirectAsync");

        assertThat(response.getResponseType()).isEqualTo(GetCustomerResponse.class);
        assertThat(responseSchema(endpoint, "/customers/{customerId}/async-direct", "200").get$ref())
            .isEqualTo("#/components/schemas/GetCustomerResponse");
    }

    private ResponseMetadata findSuccessResponse(EndpointMetadata endpoint, String methodName) {
        OperationMetadata operation = endpoint.getOperations().stream()
            .filter(candidate -> candidate.getMethodName().equals(methodName))
            .findFirst()
            .orElseThrow();

        return operation.getResponses().get("200");
    }

    private ApiResponse apiResponse(EndpointMetadata endpoint, String path, String statusCode) {
        OpenAPI openAPI = modelBuilder.build(List.of(endpoint));
        return openAPI.getPaths().get(path).getGet().getResponses().get(statusCode);
    }

    private Schema<?> responseSchema(EndpointMetadata endpoint, String path, String statusCode) {
        return apiResponse(endpoint, path, statusCode)
            .getContent()
            .get("application/json")
            .getSchema();
    }
}
