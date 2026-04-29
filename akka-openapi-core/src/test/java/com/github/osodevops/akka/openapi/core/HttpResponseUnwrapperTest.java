package com.github.osodevops.akka.openapi.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link HttpResponseUnwrapper}.
 */
class HttpResponseUnwrapperTest {

    // A stub class whose name we register as an HTTP response type for testing
    static class StubHttpResponse {}
    static class GetCustomerResponse { public String customerId; public String name; }
    static class CustomerEndpoint {
        // Raw StubHttpResponse (no type parameter)
        public StubHttpResponse getRaw(String id) { return null; }
        // Non-wrapper type
        public GetCustomerResponse getDirect(String id) { return null; }
        // Regular list (not an HTTP wrapper)
        public List<String> getList() { return null; }
    }

    @BeforeEach
    void setUp() {
        HttpResponseUnwrapper.registerHttpResponseClassName(StubHttpResponse.class.getName());
    }

    @AfterEach
    void tearDown() {
        HttpResponseUnwrapper.resetToDefaults();
    }

    @Test
    void shouldDetectRawHttpResponseType() {
        assertThat(HttpResponseUnwrapper.isHttpResponseType(StubHttpResponse.class)).isTrue();
    }

    @Test
    void shouldNotDetectNonHttpResponseType() {
        assertThat(HttpResponseUnwrapper.isHttpResponseType(GetCustomerResponse.class)).isFalse();
        assertThat(HttpResponseUnwrapper.isHttpResponseType(String.class)).isFalse();
        assertThat(HttpResponseUnwrapper.isHttpResponseType(List.class)).isFalse();
    }

    @Test
    void shouldNotDetectNullType() {
        assertThat(HttpResponseUnwrapper.isHttpResponseType(null)).isFalse();
    }

    @Test
    void shouldReturnNullForRawHttpResponseUnwrap() throws Exception {
        // Raw StubHttpResponse has no type argument
        Type returnType = CustomerEndpoint.class.getMethod("getRaw", String.class)
            .getGenericReturnType();

        assertThat(HttpResponseUnwrapper.isHttpResponseType(returnType)).isTrue();
        assertThat(HttpResponseUnwrapper.unwrapParameterizedHttpResponse(returnType)).isNull();
    }

    @Test
    void shouldReturnNullWhenUnwrappingNonWrapperType() throws Exception {
        Type returnType = CustomerEndpoint.class.getMethod("getDirect", String.class)
            .getGenericReturnType();

        assertThat(HttpResponseUnwrapper.isHttpResponseType(returnType)).isFalse();
        assertThat(HttpResponseUnwrapper.unwrapParameterizedHttpResponse(returnType)).isNull();
    }

    @Test
    void shouldDetectSubclassOfHttpResponseType() {
        // Subclass should also be detected via hierarchy walk
        class SubHttpResponse extends StubHttpResponse {}
        assertThat(HttpResponseUnwrapper.isHttpResponseType(SubHttpResponse.class)).isTrue();
    }

    /**
     * Helper to create a ParameterizedType in tests representing StubHttpResponse<T>.
     */
    private ParameterizedType buildParameterized(Class<?> raw, Type... typeArgs) {
        return new ParameterizedType() {
            @Override public Type[] getActualTypeArguments() { return typeArgs; }
            @Override public Type getRawType() { return raw; }
            @Override public Type getOwnerType() { return null; }
        };
    }

    @Test
    void shouldUnwrapParameterizedHttpResponseTypeArgument() {
        ParameterizedType parameterized = buildParameterized(
            StubHttpResponse.class, GetCustomerResponse.class);

        assertThat(HttpResponseUnwrapper.isHttpResponseType(parameterized)).isTrue();
        Type inner = HttpResponseUnwrapper.unwrapParameterizedHttpResponse(parameterized);
        assertThat(inner).isEqualTo(GetCustomerResponse.class);
    }

    @Test
    void shouldReturnNullWhenParameterizedTypeIsNotHttpResponse() {
        ParameterizedType parameterized = buildParameterized(
            List.class, GetCustomerResponse.class);

        assertThat(HttpResponseUnwrapper.isHttpResponseType(parameterized)).isFalse();
        assertThat(HttpResponseUnwrapper.unwrapParameterizedHttpResponse(parameterized)).isNull();
    }
}





