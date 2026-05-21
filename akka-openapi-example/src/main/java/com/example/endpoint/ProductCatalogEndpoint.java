package com.example.endpoint;

import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import com.example.dto.Product;
import com.github.osodevops.akka.openapi.annotations.OpenAPIQueryParam;
import com.github.osodevops.akka.openapi.annotations.OpenAPIResponse;
import com.github.osodevops.akka.openapi.annotations.OpenAPISummary;
import com.github.osodevops.akka.openapi.annotations.OpenAPITag;

import java.util.List;

/**
 * Product catalogue endpoint demonstrating {@code @OpenAPIQueryParam} for query
 * parameters that are read dynamically from the request context.
 *
 * <p>Three query parameter types are shown:</p>
 * <ul>
 *   <li><b>Integer</b> — {@code limit} with a minimum, maximum, and default value</li>
 *   <li><b>String</b>  — {@code search} for keyword filtering</li>
 *   <li><b>Boolean</b> — {@code includeDiscontinued} to toggle discontinued results</li>
 * </ul>
 */
@HttpEndpoint("/api/v1/products")
@OpenAPITag(name = "Products", description = "Product catalogue operations")
public class ProductCatalogEndpoint {

    @Get
    @OpenAPISummary("List products")
    @OpenAPIResponse(status = "200", description = "Products retrieved successfully")
    @OpenAPIQueryParam(
        name = "search",
        description = "Filter products by name or description"
    )
    @OpenAPIQueryParam(
        name = "includeDiscontinued",
        description = "When true, discontinued products are included in the response",
        type = Boolean.class,
        defaultValue = "false"
    )
    public List<Product> listProducts() {
        return List.of();
    }

    @Get("/{productId}")
    @OpenAPISummary("Get a product by ID")
    @OpenAPIResponse(status = "200", description = "Product found")
    @OpenAPIResponse(status = "404", description = "Product not found")
    public Product getProduct(String productId) {
        return new Product();
    }
}
