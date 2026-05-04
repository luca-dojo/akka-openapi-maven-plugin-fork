package com.github.osodevops.akka.openapi.core.fixtures;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import com.github.osodevops.akka.openapi.annotations.OpenAPIResponseSchema;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Endpoint fixture using exact Akka annotation class names from akka-sdk-shim.
 */
@HttpEndpoint("/customers")
public class LowLevelHttpResponseEndpoint {

    @Get("/{customerId}")
    @OpenAPIResponseSchema(GetCustomerResponse.class)
    public HttpResponse getCustomer(String customerId) {
        return null;
    }

    @Get("/{customerId}/raw")
    public HttpResponse getRawCustomer(String customerId) {
        return null;
    }

    @Get("/{customerId}/async")
    @OpenAPIResponseSchema(GetCustomerResponse.class)
    public CompletionStage<HttpResponse> getCustomerAsync(String customerId) {
        return null;
    }

    @Get("/{customerId}/async-direct")
    public CompletableFuture<GetCustomerResponse> getCustomerDirectAsync(String customerId) {
        return null;
    }
}
