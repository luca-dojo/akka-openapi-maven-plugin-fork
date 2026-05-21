package com.github.osodevops.akka.openapi.annotations;

import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that all custom annotations have RUNTIME retention.
 */
class AnnotationRetentionTest {

    @Test
    void openAPIInfoShouldHaveRuntimeRetention() {
        assertThat(OpenAPIInfo.class.getAnnotation(Retention.class).value())
            .isEqualTo(RetentionPolicy.RUNTIME);
    }

    @Test
    void openAPIResponseShouldHaveRuntimeRetention() {
        assertThat(OpenAPIResponse.class.getAnnotation(Retention.class).value())
            .isEqualTo(RetentionPolicy.RUNTIME);
    }

    @Test
    void openAPITagShouldHaveRuntimeRetention() {
        assertThat(OpenAPITag.class.getAnnotation(Retention.class).value())
            .isEqualTo(RetentionPolicy.RUNTIME);
    }

    @Test
    void openAPIExampleShouldHaveRuntimeRetention() {
        assertThat(OpenAPIExample.class.getAnnotation(Retention.class).value())
            .isEqualTo(RetentionPolicy.RUNTIME);
    }

    @Test
    void openAPIServerShouldHaveRuntimeRetention() {
        assertThat(OpenAPIServer.class.getAnnotation(Retention.class).value())
            .isEqualTo(RetentionPolicy.RUNTIME);
    }

    @Test
    void openAPISummaryShouldHaveRuntimeRetention() {
        assertThat(OpenAPISummary.class.getAnnotation(Retention.class).value())
            .isEqualTo(RetentionPolicy.RUNTIME);
    }

    @Test
    void openAPIResponseSchemaShouldHaveRuntimeRetention() {
        assertThat(OpenAPIResponseSchema.class.getAnnotation(Retention.class).value())
            .isEqualTo(RetentionPolicy.RUNTIME);
    }

    @Test
    void openAPIQueryParamShouldHaveRuntimeRetention() {
        assertThat(OpenAPIQueryParam.class.getAnnotation(Retention.class).value())
            .isEqualTo(RetentionPolicy.RUNTIME);
    }

    @Test
    void openAPIQueryParamsShouldHaveRuntimeRetention() {
        assertThat(OpenAPIQueryParams.class.getAnnotation(Retention.class).value())
            .isEqualTo(RetentionPolicy.RUNTIME);
    }
}
