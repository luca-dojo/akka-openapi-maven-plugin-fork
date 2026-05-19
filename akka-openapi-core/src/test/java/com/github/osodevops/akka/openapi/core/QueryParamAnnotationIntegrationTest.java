package com.github.osodevops.akka.openapi.core;

import com.github.osodevops.akka.openapi.core.config.PluginConfiguration;
import com.github.osodevops.akka.openapi.core.fixtures.QueryParamEndpoint;
import com.github.osodevops.akka.openapi.core.model.EndpointMetadata;
import com.github.osodevops.akka.openapi.core.model.OperationMetadata;
import com.github.osodevops.akka.openapi.core.model.ParameterMetadata;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@code @OpenAPIQueryParam} annotation extraction and
 * OpenAPI model generation.
 */
class QueryParamAnnotationIntegrationTest {

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
    void shouldExtractQueryParamFromAnnotation() {
        EndpointMetadata endpoint = extractor.extractEndpoint(QueryParamEndpoint.class);

        OperationMetadata operation = findOperation(endpoint, "listReports");
        assertThat(operation).isNotNull();
        assertThat(operation.getParameters()).hasSize(3);

        ParameterMetadata param = findParam(operation, "limit");
        assertThat(param.getName()).isEqualTo("limit");
        assertThat(param.getLocation()).isEqualTo(ParameterMetadata.ParameterLocation.QUERY);
        assertThat(param.isRequired()).isFalse();
        assertThat(param.getDescription()).isEqualTo("Maximum number of results to return");
        assertThat(param.getJavaType()).isEqualTo(Integer.class);
        assertThat(param.getFormat()).isEqualTo("int32");
        assertThat(param.getDefaultValue()).isEqualTo(20);
        assertThat(param.getMinimum()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(param.getMaximum()).isNull();
    }

    @Test
    void shouldExtractStringQueryParam() {
        EndpointMetadata endpoint = extractor.extractEndpoint(QueryParamEndpoint.class);
        OperationMetadata operation = findOperation(endpoint, "listReports");

        ParameterMetadata search = findParam(operation, "search");
        assertThat(search.getJavaType()).isEqualTo(String.class);
        assertThat(search.getDescription()).isEqualTo("Filter by keyword");
        assertThat(search.getDefaultValue()).isNull();
        assertThat(search.getMinimum()).isNull();
        assertThat(search.getMaximum()).isNull();
    }

    @Test
    void shouldExtractBooleanQueryParam() {
        EndpointMetadata endpoint = extractor.extractEndpoint(QueryParamEndpoint.class);
        OperationMetadata operation = findOperation(endpoint, "listReports");

        ParameterMetadata includeArchived = findParam(operation, "includeArchived");
        assertThat(includeArchived.getJavaType()).isEqualTo(Boolean.class);
        assertThat(includeArchived.getDescription()).isEqualTo("Include archived records in the response");
        assertThat(includeArchived.getDefaultValue()).isEqualTo(false);
    }

    @Test
    void shouldExtractMultipleQueryParamsFromAnnotation() {
        EndpointMetadata endpoint = extractor.extractEndpoint(QueryParamEndpoint.class);

        OperationMetadata operation = findOperation(endpoint, "getPagedResults");
        assertThat(operation).isNotNull();
        assertThat(operation.getParameters()).hasSize(2);

        ParameterMetadata page = findParam(operation, "page");
        assertThat(page.getDefaultValue()).isEqualTo(1);
        assertThat(page.getMinimum()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(page.getMaximum()).isNull();

        ParameterMetadata size = findParam(operation, "size");
        assertThat(size.getDefaultValue()).isEqualTo(10);
        assertThat(size.getMinimum()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(size.getMaximum()).isEqualByComparingTo(new BigDecimal("100"));
    }

    @Test
    void shouldGenerateCorrectOpenAPISpecForQueryParam() {
        EndpointMetadata endpoint = extractor.extractEndpoint(QueryParamEndpoint.class);
        OpenAPI openAPI = modelBuilder.build(List.of(endpoint));

        List<Parameter> params = openAPI.getPaths()
            .get("/reports/list")
            .getGet()
            .getParameters();

        Parameter limitParam = params.stream()
            .filter(p -> "limit".equals(p.getName()))
            .findFirst()
            .orElseThrow();

        assertThat(limitParam.getIn()).isEqualTo("query");
        assertThat(limitParam.getRequired()).isFalse();
        assertThat(limitParam.getDescription()).isEqualTo("Maximum number of results to return");

        Schema<?> schema = limitParam.getSchema();
        assertThat(schema.getType()).isEqualTo("integer");
        assertThat(schema.getFormat()).isEqualTo("int32");
        assertThat(schema.getDefault()).isEqualTo(20);
        assertThat(schema.getMinimum()).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void shouldGenerateCorrectOpenAPISpecForStringQueryParam() {
        EndpointMetadata endpoint = extractor.extractEndpoint(QueryParamEndpoint.class);
        OpenAPI openAPI = modelBuilder.build(List.of(endpoint));

        Parameter searchParam = openAPI.getPaths()
            .get("/reports/list")
            .getGet()
            .getParameters()
            .stream()
            .filter(p -> "search".equals(p.getName()))
            .findFirst()
            .orElseThrow();

        assertThat(searchParam.getIn()).isEqualTo("query");
        assertThat(searchParam.getSchema().getType()).isEqualTo("string");
    }

    @Test
    void shouldGenerateCorrectOpenAPISpecForBooleanQueryParam() {
        EndpointMetadata endpoint = extractor.extractEndpoint(QueryParamEndpoint.class);
        OpenAPI openAPI = modelBuilder.build(List.of(endpoint));

        Parameter archivedParam = openAPI.getPaths()
            .get("/reports/list")
            .getGet()
            .getParameters()
            .stream()
            .filter(p -> "includeArchived".equals(p.getName()))
            .findFirst()
            .orElseThrow();

        assertThat(archivedParam.getIn()).isEqualTo("query");
        assertThat(archivedParam.getSchema().getType()).isEqualTo("boolean");
        assertThat(archivedParam.getSchema().getDefault()).isEqualTo(false);
    }

    @Test
    void shouldGenerateCorrectOpenAPISpecForPageSizeParams() {
        EndpointMetadata endpoint = extractor.extractEndpoint(QueryParamEndpoint.class);
        OpenAPI openAPI = modelBuilder.build(List.of(endpoint));

        List<Parameter> params = openAPI.getPaths()
            .get("/reports/paged")
            .getGet()
            .getParameters();

        assertThat(params).hasSize(2);

        Parameter sizeParam = params.stream()
            .filter(p -> "size".equals(p.getName()))
            .findFirst()
            .orElseThrow();

        Schema<?> schema = sizeParam.getSchema();
        assertThat(schema.getMaximum()).isEqualByComparingTo(new BigDecimal("100"));
    }

    @Test
    void shouldLogQueryParamAnnotationProcessing() {
        extractor.extractEndpoint(QueryParamEndpoint.class);

        assertThat(logMessages).anyMatch(msg -> msg.contains("@OpenAPIQueryParam"));
    }

    private OperationMetadata findOperation(EndpointMetadata endpoint, String methodName) {
        return endpoint.getOperations().stream()
            .filter(op -> op.getMethodName().equals(methodName))
            .findFirst()
            .orElse(null);
    }

    private ParameterMetadata findParam(OperationMetadata operation, String name) {
        return operation.getParameters().stream()
            .filter(p -> p.getName().equals(name))
            .findFirst()
            .orElseThrow();
    }
}
