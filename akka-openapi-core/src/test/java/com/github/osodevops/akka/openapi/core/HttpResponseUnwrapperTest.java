package com.github.osodevops.akka.openapi.core;

import akka.http.javadsl.model.HttpResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletionStage;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link HttpResponseUnwrapper}.
 */
class HttpResponseUnwrapperTest {

    static class SubHttpResponse extends HttpResponse {
    }

    @Test
    void shouldDetectAkkaHttpResponseTypeByClassName() {
        assertThat(HttpResponseUnwrapper.isHttpResponseType(HttpResponse.class)).isTrue();
    }

    @Test
    void shouldDetectSubclassOfHttpResponseType() {
        assertThat(HttpResponseUnwrapper.isHttpResponseType(SubHttpResponse.class)).isTrue();
    }

    @Test
    void shouldNotDetectNonHttpResponseTypes() {
        assertThat(HttpResponseUnwrapper.isHttpResponseType(String.class)).isFalse();
        assertThat(HttpResponseUnwrapper.isHttpResponseType(List.class)).isFalse();
        assertThat(HttpResponseUnwrapper.isHttpResponseType(CompletionStage.class)).isFalse();
    }

    @Test
    void shouldNotDetectNullType() {
        assertThat(HttpResponseUnwrapper.isHttpResponseType(null)).isFalse();
    }
}
