package com.github.osodevops.akka.openapi.core.fixtures;

import com.github.osodevops.akka.openapi.annotations.OpenAPIResponseSchema;

/**
 * Test fixture: endpoint that returns a stub HttpResponse (raw) annotated with
 * {@code @OpenAPIResponseSchema} to declare the actual payload type.
 */
@MockHttpEndpoint("/customers")
public class HttpResponseEndpoint {

    /**
     * Raw stub HttpResponse – payload type declared via annotation.
     */
    @MockGet("/{customerId}")
    @OpenAPIResponseSchema(GetCustomerResponse.class)
    public MockHttpResponse getCustomer(String customerId) {
        return null; // stub
    }

    /**
     * Non-wrapper domain type returned directly.
     */
    @MockGet("/profile/{customerId}")
    public GetCustomerResponse getCustomerProfile(String customerId) {
        return null; // stub
    }
}



