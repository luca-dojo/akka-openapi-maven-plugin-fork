package com.github.osodevops.akka.openapi.core;

import com.github.osodevops.akka.openapi.core.config.PluginConfiguration;
import com.github.osodevops.akka.openapi.core.fixtures.QueryParamEndpoint;
import com.github.osodevops.akka.openapi.core.model.EndpointMetadata;
import com.github.osodevops.akka.openapi.core.model.OperationMetadata;
import com.github.osodevops.akka.openapi.core.model.ParameterMetadata;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.ParameterizedType;
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

    // --- List / array query parameter tests ---

    @Test
    void shouldExtractListStringQueryParam() {
        EndpointMetadata endpoint = extractor.extractEndpoint(QueryParamEndpoint.class);

        OperationMetadata operation = findOperation(endpoint, "getFilteredResults");
        assertThat(operation).isNotNull();
        assertThat(operation.getParameters()).hasSize(2);

        ParameterMetadata filters = findParam(operation, "filters");
        assertThat(filters.getName()).isEqualTo("filters");
        assertThat(filters.getLocation()).isEqualTo(ParameterMetadata.ParameterLocation.QUERY);
        assertThat(filters.isRequired()).isFalse();
        assertThat(filters.getDescription()).isEqualTo("Active filters to apply (repeated: ?filters=a&filters=b)");

        // javaType should be a ParameterizedType representing List<String>
        assertThat(filters.getJavaType()).isInstanceOf(ParameterizedType.class);
        ParameterizedType pt = (ParameterizedType) filters.getJavaType();
        assertThat(pt.getRawType()).isEqualTo(List.class);
        assertThat(pt.getActualTypeArguments()).hasSize(1);
        assertThat(pt.getActualTypeArguments()[0]).isEqualTo(String.class);
    }

    @Test
    void shouldExtractMultipleListQueryParams() {
        EndpointMetadata endpoint = extractor.extractEndpoint(QueryParamEndpoint.class);
        OperationMetadata operation = findOperation(endpoint, "getFilteredResults");

        ParameterMetadata statuses = findParam(operation, "statuses");
        assertThat(statuses.getJavaType()).isInstanceOf(ParameterizedType.class);
        ParameterizedType pt = (ParameterizedType) statuses.getJavaType();
        assertThat(pt.getRawType()).isEqualTo(List.class);
        assertThat(pt.getActualTypeArguments()[0]).isEqualTo(String.class);
    }

    @Test
    void shouldNotSetScalarConstraintsOnListParam() {
        EndpointMetadata endpoint = extractor.extractEndpoint(QueryParamEndpoint.class);
        OperationMetadata operation = findOperation(endpoint, "getFilteredResults");

        ParameterMetadata filters = findParam(operation, "filters");
        assertThat(filters.getMinimum()).isNull();
        assertThat(filters.getMaximum()).isNull();
        assertThat(filters.getDefaultValue()).isNull();
        assertThat(filters.getFormat()).isNull();
    }

    @Test
    void shouldGenerateArraySchemaForListStringQueryParam() {
        EndpointMetadata endpoint = extractor.extractEndpoint(QueryParamEndpoint.class);
        OpenAPI openAPI = modelBuilder.build(List.of(endpoint));

        Parameter filtersParam = openAPI.getPaths()
            .get("/reports/filtered")
            .getGet()
            .getParameters()
            .stream()
            .filter(p -> "filters".equals(p.getName()))
            .findFirst()
            .orElseThrow();

        assertThat(filtersParam.getIn()).isEqualTo("query");
        assertThat(filtersParam.getRequired()).isFalse();
        assertThat(filtersParam.getDescription())
            .isEqualTo("Active filters to apply (repeated: ?filters=a&filters=b)");

        Schema<?> schema = filtersParam.getSchema();
        assertThat(schema).isInstanceOf(ArraySchema.class);
        assertThat(schema.getType()).isEqualTo("array");

        Schema<?> items = ((ArraySchema) schema).getItems();
        assertThat(items).isNotNull();
        assertThat(items.getType()).isEqualTo("string");
    }

    @Test
    void shouldLogListTypeResolution() {
        extractor.extractEndpoint(QueryParamEndpoint.class);

        assertThat(logMessages).anyMatch(msg ->
            msg.contains("List<String>") && msg.contains("filters"));
    }

    @Test
    void shouldWarnWhenListTypeHasNoItemType() {
        // Validate that the extractor logs a warning when type=List without itemType
        // We use a dedicated inline fixture for this edge case
        @akka.javasdk.annotations.http.HttpEndpoint("/edge")
        class EdgeEndpoint {
            @akka.javasdk.annotations.http.Get("/noitem")
            @com.github.osodevops.akka.openapi.annotations.OpenAPIQueryParam(
                name = "tags",
                type = List.class
                // no itemType
            )
            public akka.http.javadsl.model.HttpResponse get() { return null; }
        }

        extractor.extractEndpoint(EdgeEndpoint.class);

        assertThat(logMessages).anyMatch(msg ->
            msg.contains("without itemType") && msg.contains("tags"));
    }

    @Test
    void shouldWarnWhenItemTypeOnScalarParam() {
        @akka.javasdk.annotations.http.HttpEndpoint("/edge")
        class EdgeEndpoint {
            @akka.javasdk.annotations.http.Get("/scalar")
            @com.github.osodevops.akka.openapi.annotations.OpenAPIQueryParam(
                name = "limit",
                type = Integer.class,
                itemType = String.class   // invalid: itemType on a scalar
            )
            public akka.http.javadsl.model.HttpResponse get() { return null; }
        }

        extractor.extractEndpoint(EdgeEndpoint.class);

        assertThat(logMessages).anyMatch(msg ->
            msg.contains("itemType is specified but type is not a Collection") && msg.contains("limit"));
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
