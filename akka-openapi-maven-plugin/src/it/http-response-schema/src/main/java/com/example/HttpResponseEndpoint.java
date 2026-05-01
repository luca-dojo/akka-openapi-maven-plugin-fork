package com.example;

import akka.http.javadsl.model.HttpResponse;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import com.github.osodevops.akka.openapi.annotations.OpenAPIResponseSchema;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@HttpEndpoint("/customers")
public class HttpResponseEndpoint {

    @Get("/{customerId}")
    @OpenAPIResponseSchema(GetCustomerResponse.class)
    public HttpResponse getCustomer(String customerId) {
        return new HttpResponse();
    }

    @Get("/raw/{customerId}")
    public HttpResponse getRawCustomer(String customerId) {
        return new HttpResponse();
    }

    @Get("/async/{customerId}")
    @OpenAPIResponseSchema(GetCustomerResponse.class)
    public CompletionStage<HttpResponse> getCustomerAsync(String customerId) {
        return CompletableFuture.completedFuture(new HttpResponse());
    }

    @Get("/direct-async/{customerId}")
    public CompletionStage<GetCustomerResponse> getCustomerDirectAsync(String customerId) {
        return CompletableFuture.completedFuture(new GetCustomerResponse(customerId, "Jane Customer"));
    }
}
