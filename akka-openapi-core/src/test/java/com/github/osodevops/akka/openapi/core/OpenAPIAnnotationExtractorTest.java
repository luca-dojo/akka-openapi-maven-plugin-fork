package com.github.osodevops.akka.openapi.core;

import com.github.osodevops.akka.openapi.annotations.*;
import com.github.osodevops.akka.openapi.core.fixtures.AnnotatedEndpoint;
import com.github.osodevops.akka.openapi.core.fixtures.GetCustomerResponse;
import com.github.osodevops.akka.openapi.core.fixtures.LowLevelHttpResponseEndpoint;
import com.github.osodevops.akka.openapi.core.fixtures.QueryParamEndpoint;
import com.github.osodevops.akka.openapi.core.fixtures.SimpleEndpoint;
import com.github.osodevops.akka.openapi.core.fixtures.StreamingEndpoint;
import com.github.osodevops.akka.openapi.core.model.InfoMetadata;
import com.github.osodevops.akka.openapi.core.model.ServerMetadata;
import com.github.osodevops.akka.openapi.core.model.TagMetadata;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that OpenAPI annotations (@OpenAPITag, @OpenAPIResponse, @OpenAPIExample)
 * are correctly read from classes and methods.
 *
 * These tests verify the annotation extraction at the reflection level,
 * since the full extractor pipeline requires real Akka SDK annotations.
 */
class OpenAPIAnnotationExtractorTest {

    @Test
    void shouldReadOpenAPITagFromClass() {
        OpenAPITag tag = AnnotatedEndpoint.class.getAnnotation(OpenAPITag.class);

        assertThat(tag).isNotNull();
        assertThat(tag.name()).isEqualTo("Customers");
        assertThat(tag.description()).isEqualTo("Customer management operations");
        assertThat(tag.externalDocsUrl()).isEqualTo("https://docs.example.com/customers");
        assertThat(tag.externalDocsDescription()).isEqualTo("Customer API Guide");
    }

    @Test
    void shouldReturnNullWhenNoOpenAPITag() {
        OpenAPITag tag = SimpleEndpoint.class.getAnnotation(OpenAPITag.class);

        assertThat(tag).isNull();
    }

    @Test
    void shouldReadSingleOpenAPIResponse() throws Exception {
        Method method = AnnotatedEndpoint.class.getMethod("listCustomers");
        OpenAPIResponse[] responses = method.getAnnotationsByType(OpenAPIResponse.class);

        assertThat(responses).hasSize(1);
        assertThat(responses[0].status()).isEqualTo("200");
        assertThat(responses[0].description()).isEqualTo("Successfully retrieved customer list");
        assertThat(responses[0].responseType()).isEqualTo(Void.class); // default
        assertThat(responses[0].mediaType()).isEqualTo("application/json"); // default
    }

    @Test
    void shouldReadMultipleOpenAPIResponses() throws Exception {
        Method method = AnnotatedEndpoint.class.getMethod("getCustomer", String.class);
        OpenAPIResponse[] responses = method.getAnnotationsByType(OpenAPIResponse.class);

        assertThat(responses).hasSize(2);

        assertThat(responses[0].status()).isEqualTo("200");
        assertThat(responses[0].description()).isEqualTo("Customer found");

        assertThat(responses[1].status()).isEqualTo("404");
        assertThat(responses[1].description()).isEqualTo("Customer not found");
    }

    @Test
    void shouldReadThreeOpenAPIResponsesIncluding409() throws Exception {
        Method method = AnnotatedEndpoint.class.getMethod("createCustomer",
            com.github.osodevops.akka.openapi.core.fixtures.CreateCustomerRequest.class);
        OpenAPIResponse[] responses = method.getAnnotationsByType(OpenAPIResponse.class);

        assertThat(responses).hasSize(3);
        assertThat(responses[0].status()).isEqualTo("201");
        assertThat(responses[1].status()).isEqualTo("400");
        assertThat(responses[2].status()).isEqualTo("409");
        assertThat(responses[2].description()).isEqualTo("Customer with this email already exists");
    }

    @Test
    void shouldReadOpenAPIExample() throws Exception {
        Method method = AnnotatedEndpoint.class.getMethod("createCustomer",
            com.github.osodevops.akka.openapi.core.fixtures.CreateCustomerRequest.class);
        OpenAPIExample[] examples = method.getAnnotationsByType(OpenAPIExample.class);

        assertThat(examples).hasSize(1);
        assertThat(examples[0].name()).isEqualTo("newCustomer");
        assertThat(examples[0].summary()).isEqualTo("Create a new customer");
        assertThat(examples[0].value()).isEqualTo("{\"name\": \"John Doe\", \"email\": \"john@example.com\"}");
    }

    @Test
    void shouldReturnEmptyArrayWhenNoOpenAPIResponseAnnotations() throws Exception {
        Method method = SimpleEndpoint.class.getMethod("hello");
        OpenAPIResponse[] responses = method.getAnnotationsByType(OpenAPIResponse.class);

        assertThat(responses).isEmpty();
    }

    @Test
    void shouldReturnEmptyArrayWhenNoOpenAPIExampleAnnotations() throws Exception {
        Method method = AnnotatedEndpoint.class.getMethod("listCustomers");
        OpenAPIExample[] examples = method.getAnnotationsByType(OpenAPIExample.class);

        assertThat(examples).isEmpty();
    }

    @Test
    void shouldCreateTagMetadataFromAnnotation() {
        OpenAPITag tag = AnnotatedEndpoint.class.getAnnotation(OpenAPITag.class);
        TagMetadata metadata = new TagMetadata(
            tag.name(), tag.description(),
            tag.externalDocsUrl(), tag.externalDocsDescription()
        );

        assertThat(metadata.getName()).isEqualTo("Customers");
        assertThat(metadata.getDescription()).isEqualTo("Customer management operations");
        assertThat(metadata.getExternalDocsUrl()).isEqualTo("https://docs.example.com/customers");
        assertThat(metadata.getExternalDocsDescription()).isEqualTo("Customer API Guide");
    }

    @Test
    void shouldReadOpenAPIInfoFromClass() {
        OpenAPIInfo info = AnnotatedEndpoint.class.getAnnotation(OpenAPIInfo.class);

        assertThat(info).isNotNull();
        assertThat(info.title()).isEqualTo("Customer API");
        assertThat(info.version()).isEqualTo("2.0.0");
        assertThat(info.description()).isEqualTo("API for managing customer records");
        assertThat(info.termsOfService()).isEqualTo("https://example.com/terms");
        assertThat(info.contactName()).isEqualTo("API Support");
        assertThat(info.contactEmail()).isEqualTo("support@example.com");
        assertThat(info.contactUrl()).isEqualTo("https://example.com/support");
        assertThat(info.licenseName()).isEqualTo("Apache 2.0");
        assertThat(info.licenseUrl()).isEqualTo("https://www.apache.org/licenses/LICENSE-2.0");
    }

    @Test
    void shouldReturnNullWhenNoOpenAPIInfo() {
        OpenAPIInfo info = SimpleEndpoint.class.getAnnotation(OpenAPIInfo.class);
        assertThat(info).isNull();
    }

    @Test
    void shouldCreateInfoMetadataFromAnnotation() {
        OpenAPIInfo info = AnnotatedEndpoint.class.getAnnotation(OpenAPIInfo.class);
        InfoMetadata metadata = InfoMetadata.builder()
            .title(info.title())
            .version(info.version())
            .description(info.description())
            .termsOfService(info.termsOfService())
            .contactName(info.contactName())
            .contactEmail(info.contactEmail())
            .contactUrl(info.contactUrl())
            .licenseName(info.licenseName())
            .licenseUrl(info.licenseUrl())
            .build();

        assertThat(metadata.getTitle()).isEqualTo("Customer API");
        assertThat(metadata.getVersion()).isEqualTo("2.0.0");
        assertThat(metadata.getContactEmail()).isEqualTo("support@example.com");
        assertThat(metadata.getLicenseName()).isEqualTo("Apache 2.0");
        assertThat(metadata.hasContent()).isTrue();
    }

    @Test
    void shouldReadOpenAPIServersFromClass() {
        OpenAPIServer[] servers = AnnotatedEndpoint.class.getAnnotationsByType(OpenAPIServer.class);

        assertThat(servers).hasSize(2);
        assertThat(servers[0].url()).isEqualTo("https://api.example.com");
        assertThat(servers[0].description()).isEqualTo("Production");
        assertThat(servers[1].url()).isEqualTo("https://api-staging.example.com");
        assertThat(servers[1].description()).isEqualTo("Staging");
    }

    @Test
    void shouldReturnEmptyArrayWhenNoOpenAPIServers() {
        OpenAPIServer[] servers = SimpleEndpoint.class.getAnnotationsByType(OpenAPIServer.class);
        assertThat(servers).isEmpty();
    }

    @Test
    void shouldCreateServerMetadataFromAnnotation() {
        OpenAPIServer[] servers = AnnotatedEndpoint.class.getAnnotationsByType(OpenAPIServer.class);
        ServerMetadata metadata = new ServerMetadata(servers[0].url(), servers[0].description());

        assertThat(metadata.getUrl()).isEqualTo("https://api.example.com");
        assertThat(metadata.getDescription()).isEqualTo("Production");
    }

    // ---------------------------------------------------------------------------
    // @OpenAPISummary tests
    // ---------------------------------------------------------------------------

    @Test
    void shouldReadOpenAPISummaryFromMethod() throws Exception {
        Method method = AnnotatedEndpoint.class.getMethod("listCustomers");
        OpenAPISummary summary = method.getAnnotation(OpenAPISummary.class);

        assertThat(summary).isNotNull();
        assertThat(summary.value()).isEqualTo("List all customers");
    }

    @Test
    void shouldReadDifferentOpenAPISummaryPerMethod() throws Exception {
        Method listMethod = AnnotatedEndpoint.class.getMethod("listCustomers");
        Method getMethod = AnnotatedEndpoint.class.getMethod("getCustomer", String.class);

        assertThat(listMethod.getAnnotation(OpenAPISummary.class).value())
            .isEqualTo("List all customers");
        assertThat(getMethod.getAnnotation(OpenAPISummary.class).value())
            .isEqualTo("Get customer by ID");
    }

    @Test
    void shouldReturnNullOpenAPISummaryWhenNotAnnotated() throws Exception {
        Method method = AnnotatedEndpoint.class.getMethod("deleteCustomer", String.class);
        OpenAPISummary summary = method.getAnnotation(OpenAPISummary.class);

        assertThat(summary).isNull();
    }

    @Test
    void openAPISummaryShouldTargetMethodOnly() {
        java.lang.annotation.Target target =
            OpenAPISummary.class.getAnnotation(java.lang.annotation.Target.class);

        assertThat(target).isNotNull();
        assertThat(target.value()).containsExactly(java.lang.annotation.ElementType.METHOD);
    }

    // ---------------------------------------------------------------------------
    // @OpenAPIResponseSchema annotation tests
    // ---------------------------------------------------------------------------

    @Test
    void shouldReadOpenAPIResponseSchemaAnnotation() throws Exception {
        Method method = LowLevelHttpResponseEndpoint.class.getMethod("getCustomer", String.class);
        OpenAPIResponseSchema annotation = method.getAnnotation(OpenAPIResponseSchema.class);

        assertThat(annotation).isNotNull();
        assertThat(annotation.value()).isEqualTo(GetCustomerResponse.class);
    }

    @Test
    void shouldReturnNullWhenNoOpenAPIResponseSchemaAnnotation() throws Exception {
        Method method = LowLevelHttpResponseEndpoint.class.getMethod("getRawCustomer", String.class);
        OpenAPIResponseSchema annotation = method.getAnnotation(OpenAPIResponseSchema.class);

        assertThat(annotation).isNull();
    }

    @Test
    void shouldHaveRuntimeRetention() throws Exception {
        // Verify the annotation is retained at runtime so AkkaAnnotationExtractor can read it
        Method method = LowLevelHttpResponseEndpoint.class.getMethod("getCustomer", String.class);
        OpenAPIResponseSchema annotation = method.getAnnotation(OpenAPIResponseSchema.class);

        // Simply verifying we can read it at runtime confirms RUNTIME retention
        assertThat(annotation).isNotNull();
        assertThat(annotation.value().getSimpleName()).isEqualTo("GetCustomerResponse");
    }

    @Test
    void shouldReadContentTypeFromOpenAPIResponse() throws Exception {
        Method method = StreamingEndpoint.class.getMethod("streamEvents");
        OpenAPIResponse[] responses = method.getAnnotationsByType(OpenAPIResponse.class);

        assertThat(responses).hasSize(1);
        assertThat(responses[0].status()).isEqualTo("200");
        assertThat(responses[0].description()).isEqualTo("Live feed established");
        assertThat(responses[0].mediaType()).isEqualTo("text/event-stream");
        assertThat(responses[0].responseType()).isEqualTo(String.class);
    }

    @Test
    void shouldReadBinaryContentTypeFromOpenAPIResponse() throws Exception {
        Method method = StreamingEndpoint.class.getMethod("downloadFile");
        OpenAPIResponse[] responses = method.getAnnotationsByType(OpenAPIResponse.class);

        assertThat(responses).hasSize(1);
        assertThat(responses[0].mediaType()).isEqualTo("application/octet-stream");
    }

    @Test
    void shouldDefaultContentTypeToApplicationJson() throws Exception {
        Method method = AnnotatedEndpoint.class.getMethod("listCustomers");
        OpenAPIResponse[] responses = method.getAnnotationsByType(OpenAPIResponse.class);

        assertThat(responses[0].mediaType()).isEqualTo("application/json");
    }

    // ---------------------------------------------------------------------------
    // @OpenAPIQueryParam annotation tests
    // ---------------------------------------------------------------------------

    @Test
    void shouldReadSingleOpenAPIQueryParam() throws Exception {
        Method method = QueryParamEndpoint.class.getMethod("listReports");
        OpenAPIQueryParam[] params = method.getAnnotationsByType(OpenAPIQueryParam.class);

        assertThat(params).hasSize(3);

        OpenAPIQueryParam limitParam = java.util.Arrays.stream(params)
            .filter(p -> "limit".equals(p.name()))
            .findFirst()
            .orElseThrow();
        assertThat(limitParam.description()).isEqualTo("Maximum number of results to return");
        assertThat(limitParam.required()).isFalse();
        assertThat(limitParam.type()).isEqualTo(Integer.class);
        assertThat(limitParam.format()).isEqualTo("int32");
        assertThat(limitParam.minimum()).isEqualTo("1");
        assertThat(limitParam.defaultValue()).isEqualTo("20");
        assertThat(limitParam.maximum()).isEqualTo(""); // not set
    }

    @Test
    void shouldReadStringOpenAPIQueryParam() throws Exception {
        Method method = QueryParamEndpoint.class.getMethod("listReports");
        OpenAPIQueryParam[] params = method.getAnnotationsByType(OpenAPIQueryParam.class);

        OpenAPIQueryParam searchParam = java.util.Arrays.stream(params)
            .filter(p -> "search".equals(p.name()))
            .findFirst()
            .orElseThrow();
        assertThat(searchParam.type()).isEqualTo(Void.class); // default — will resolve to String
        assertThat(searchParam.defaultValue()).isEqualTo("");
    }

    @Test
    void shouldReadBooleanOpenAPIQueryParam() throws Exception {
        Method method = QueryParamEndpoint.class.getMethod("listReports");
        OpenAPIQueryParam[] params = method.getAnnotationsByType(OpenAPIQueryParam.class);

        OpenAPIQueryParam archivedParam = java.util.Arrays.stream(params)
            .filter(p -> "includeArchived".equals(p.name()))
            .findFirst()
            .orElseThrow();
        assertThat(archivedParam.type()).isEqualTo(Boolean.class);
        assertThat(archivedParam.defaultValue()).isEqualTo("false");
    }

    @Test
    void shouldReadMultipleOpenAPIQueryParams() throws Exception {
        Method method = QueryParamEndpoint.class.getMethod("getPagedResults");
        OpenAPIQueryParam[] params = method.getAnnotationsByType(OpenAPIQueryParam.class);

        assertThat(params).hasSize(2);
        assertThat(params[0].name()).isEqualTo("page");
        assertThat(params[1].name()).isEqualTo("size");
        assertThat(params[1].maximum()).isEqualTo("100");
    }

    @Test
    void openAPIQueryParamShouldHaveExpectedDefaults() throws Exception {
        Method method = QueryParamEndpoint.class.getMethod("listReports");
        OpenAPIQueryParam[] params = method.getAnnotationsByType(OpenAPIQueryParam.class);
        OpenAPIQueryParam limitParam = java.util.Arrays.stream(params)
            .filter(p -> "limit".equals(p.name()))
            .findFirst()
            .orElseThrow();

        // Verify maximum is not set for the limit param
        assertThat(limitParam.maximum()).isEqualTo("");
    }

    @Test
    void openAPIQueryParamShouldTargetMethodOnly() {
        java.lang.annotation.Target target =
            OpenAPIQueryParam.class.getAnnotation(java.lang.annotation.Target.class);

        assertThat(target).isNotNull();
        assertThat(target.value()).containsExactly(java.lang.annotation.ElementType.METHOD);
    }
}
