# Akka OpenAPI Maven Plugin

[![Maven Central](https://img.shields.io/maven-central/v/sh.oso/akka-openapi-maven-plugin.svg)](https://search.maven.org/artifact/sh.oso/akka-openapi-maven-plugin)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Build](https://github.com/osodevops/akka-openapi-maven-plugin/actions/workflows/ci.yml/badge.svg)](https://github.com/osodevops/akka-openapi-maven-plugin/actions/workflows/ci.yml)

Generate OpenAPI 3.1 specifications from [Akka SDK](https://doc.akka.io/sdk/) HTTP endpoint annotations at compile time.

## Features

- **Zero Configuration** - Works out-of-the-box with sensible defaults
- **Compile-Time Generation** - No runtime overhead, perfect for serverless/containers
- **OpenAPI 3.1** - Latest specification with full JSON Schema support
- **Automatic Schema Generation** - POJOs converted to JSON schemas automatically
- **Optional Unwrapping** - `Optional<T>` fields and return types use the inner schema
- **JsonValue Wrappers** - Jackson `@JsonValue` records/classes are emitted as scalar schemas
- **Polymorphic Schemas** - Jackson `@JsonTypeInfo` / `@JsonSubTypes` types become `oneOf` schemas with discriminators
- **Composed Schemas** - Real `anyOf`, `oneOf`, and `allOf` compositions are preserved
- **Deterministic Output** - Paths and component schemas are sorted for stable diffs
- **Server Path Cleanup** - Optional server path prefix stripping avoids duplicated path segments
- **JavaDoc Extraction** - Uses your existing documentation for descriptions
- **Validation** - Ensures generated specs are valid before writing

## Quick Start

Add the plugin to your `pom.xml`:

```xml
<build>
    <plugins>
        <plugin>
            <groupId>sh.oso</groupId>
            <artifactId>akka-openapi-maven-plugin</artifactId>
            <version>1.4.0</version>
            <executions>
                <execution>
                    <goals>
                        <goal>generate</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

Run:

```bash
mvn compile
```

Your OpenAPI specification will be generated at `target/openapi.yaml`.

## Example

Given an Akka SDK endpoint:

```java
/**
 * Customer management endpoint.
 */
@HttpEndpoint("/customers")
public class CustomerEndpoint {

    /**
     * Get a customer by ID.
     * @param id the customer unique identifier
     * @return the customer or 404 if not found
     */
    @Get("/{id}")
    public Customer getCustomer(String id) {
        // ...
    }

    /**
     * Create a new customer.
     */
    @Post
    public Customer createCustomer(CreateCustomerRequest request) {
        // ...
    }
}
```

The plugin generates:

```yaml
openapi: 3.1.0
info:
  title: My API
  version: 1.0.0
paths:
  /customers/{id}:
    get:
      summary: Get a customer by ID.
      operationId: getCustomer
      parameters:
        - name: id
          in: path
          required: true
          description: the customer unique identifier
          schema:
            type: string
      responses:
        '200':
          description: Success
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/Customer'
        '404':
          description: Not Found
  /customers:
    post:
      summary: Create a new customer.
      operationId: createCustomer
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/CreateCustomerRequest'
      responses:
        '201':
          description: Created
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/Customer'
components:
  schemas:
    Customer:
      type: object
      properties:
        id:
          type: string
        name:
          type: string
        email:
          type: string
          format: email
    CreateCustomerRequest:
      type: object
      required:
        - name
        - email
      properties:
        name:
          type: string
        email:
          type: string
          format: email
```

## Configuration

```xml
<plugin>
    <groupId>sh.oso</groupId>
    <artifactId>akka-openapi-maven-plugin</artifactId>
    <version>1.4.0</version>
    <configuration>
        <!-- Output settings -->
        <outputFile>${project.build.directory}/openapi.yaml</outputFile>
        <outputFormat>yaml</outputFormat> <!-- yaml or json -->

        <!-- API metadata -->
        <apiTitle>${project.name}</apiTitle>
        <apiVersion>${project.version}</apiVersion>
        <apiDescription>${project.description}</apiDescription>

        <!-- Package scanning -->
        <scanPackages>
            <package>com.example.endpoints</package>
        </scanPackages>

        <!-- Server definitions -->
        <servers>
            <server>
                <url>https://api.example.com/v1</url>
                <description>Production</description>
            </server>
        </servers>
        <stripServerPathPrefix>true</stripServerPathPrefix>

        <!-- Security schemes (apiKey only; see Security Schemes section below) -->
        <security>
            <securityScheme>
                <schemeName>CustomAuthHeader</schemeName>
                <type>apiKey</type>
                <in>header</in>
                <name>x-custom-auth</name>
                <description>Custom authentication header</description>
            </securityScheme>
        </security>

        <!-- Validation -->
        <failOnValidationError>true</failOnValidationError>

        <!-- Skip generation -->
        <skip>false</skip>
    </configuration>
</plugin>
```

### Security Schemes

Declare apiKey-based security schemes via the `<security>` block. Each entry
becomes both a `components.securitySchemes` entry and a top-level `security`
requirement, applied to every operation:

```xml
<security>
    <securityScheme>
        <schemeName>CustomAuthHeader</schemeName>
        <type>apiKey</type>
        <in>header</in>            <!-- header | query | cookie -->
        <name>x-custom-auth</name> <!-- the header / query / cookie key -->
        <description>Optional human-readable description</description>
    </securityScheme>
</security>
```

Generates:

```yaml
security:
- CustomAuthHeader: []
components:
  securitySchemes:
    CustomAuthHeader:
      type: apiKey
      description: Optional human-readable description
      name: x-custom-auth
      in: header
```

Notes:
- Only `type: apiKey` is supported today. `http`, `oauth2`, and
  `openIdConnect` are rejected with a clear error and tracked for follow-up.
- Multiple `<securityScheme>` entries become separate items in the top-level
  `security` array. In OpenAPI semantics that is an OR (any one is
  sufficient), not an AND.
- Set `<includeSecuritySchemes>false</includeSecuritySchemes>` to suppress
  emission without removing the `<security>` block.

### Polymorphic Schemas

Types annotated with Jackson `@JsonTypeInfo` and `@JsonSubTypes` are emitted as
OpenAPI `oneOf` schemas with discriminator mappings:

```java
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "channel")
@JsonSubTypes({
    @JsonSubTypes.Type(value = EmailNotification.class, name = "EMAIL"),
    @JsonSubTypes.Type(value = SmsNotification.class, name = "SMS")
})
public sealed interface Notification permits EmailNotification, SmsNotification {
}
```

Generates:

```yaml
Notification:
  discriminator:
    propertyName: channel
    mapping:
      EMAIL: "#/components/schemas/EmailNotification"
      SMS: "#/components/schemas/SmsNotification"
  oneOf:
    - $ref: "#/components/schemas/EmailNotification"
    - $ref: "#/components/schemas/SmsNotification"
```

### Optional and JsonValue Schemas

`Optional<T>` fields and return types are unwrapped to the schema for `T`, so
the generated OpenAPI does not contain opaque `Optional` components:

```java
public record NotificationPreferences(
    Optional<Title> title,
    Optional<DeviceToken> deviceToken
) {
}
```

Jackson `@JsonValue` wrappers are represented as the scalar value they serialize
to, including formats for numbers, dates, UUIDs, and other known scalar types:

```java
public record Title(@JsonValue String title) {
}

public record DeviceToken(@JsonValue long tokenId) {
}
```

Generates:

```yaml
NotificationPreferences:
  type: object
  properties:
    title:
      $ref: "#/components/schemas/Title"
    deviceToken:
      $ref: "#/components/schemas/DeviceToken"
Title:
  type: string
DeviceToken:
  type: integer
  format: int64
```

### Composed Schemas

Meaningful JSON Schema compositions are preserved in the OpenAPI output:

```yaml
FlexibleIdentifier:
  anyOf:
    - type: string
    - type: integer
      format: int64
```

Nullable-only wrappers such as `anyOf: [{type: string}, {type: null}]` are still
simplified to the non-null schema so common Java nullable patterns stay concise.

## Supported Akka SDK Annotations

| Annotation | OpenAPI Mapping |
|------------|-----------------|
| `@HttpEndpoint(path)` | Base path for all operations |
| `@Get`, `@Post`, `@Put`, `@Delete`, `@Patch` | HTTP methods |
| Path parameters (e.g., `/{id}`) | `parameters[in=path]` |
| Last complex type parameter | `requestBody` |
| Method return type | Response schema |
| JavaDoc comments | `summary` and `description` |

## Custom Annotations

For additional control, use the optional custom annotations:

```java
@HttpEndpoint("/customers")
@OpenAPITag(name = "Customers", description = "Customer management")
public class CustomerEndpoint {

    @Get("/{id}")
    @OpenAPISummary("Get customer by ID")
    @OpenAPIResponse(status = "200", description = "Customer found")
    @OpenAPIResponse(status = "404", description = "Customer not found")
    public Customer getCustomer(String id) {
        // ...
    }
}
```

`@OpenAPISummary("...")` populates the operation's `summary` field — a short,
single-line label shown in tools like Swagger UI.

### Explicit Query Parameters

When query parameters are read dynamically from the request context rather than declared as
typed Java method parameters, use `@OpenAPIQueryParam` to document them.

#### Integer parameter

```java
@Get("/products")
@OpenAPISummary("List products")
@OpenAPIQueryParam(
    name = "limit",
    description = "Maximum number of products to return",
    type = Integer.class,
    format = "int32",
    minimum = "1",
    maximum = "200",
    defaultValue = "20"
)
public List<Product> listProducts() {
    int limit = requestContext().queryParams().getInteger("limit").orElse(20);
    // ...
}
```

Generates:

```yaml
parameters:
  - name: limit
    in: query
    required: false
    description: Maximum number of products to return
    schema:
      type: integer
      format: int32
      minimum: 1
      maximum: 200
      default: 20
```

#### String parameter

```java
@OpenAPIQueryParam(
    name = "search",
    description = "Filter products by keyword"
)
```

Generates:

```yaml
parameters:
  - name: search
    in: query
    required: false
    description: Filter products by keyword
    schema:
      type: string
```

`String` is the default type — omitting `type` produces the same output.

#### Boolean parameter

```java
@OpenAPIQueryParam(
    name = "includeDiscontinued",
    description = "When true, discontinued products are included in the response",
    type = Boolean.class,
    defaultValue = "false"
)
```

Generates:

```yaml
parameters:
  - name: includeDiscontinued
    in: query
    required: false
    description: When true, discontinued products are included in the response
    schema:
      type: boolean
      default: false
```

All three types may be combined on a single method by repeating the annotation:

```java
@Get("/products")
@OpenAPIQueryParam(
    name = "limit",
    description = "Maximum number of products to return",
    type = Integer.class, format = "int32", minimum = "1", maximum = "200", defaultValue = "20"
)
@OpenAPIQueryParam(
    name = "search",
    description = "Filter products by keyword"
)
@OpenAPIQueryParam(
    name = "includeDiscontinued",
    description = "When true, discontinued products are included in the response",
    type = Boolean.class, defaultValue = "false"
)
public List<Product> listProducts() { ... }
```

The annotation can also enrich a typed method parameter that the extractor has already
detected — for example to add a description or a `minimum` constraint:

```java
@Get
@OpenAPIQueryParam(name = "page", description = "0-indexed page number", minimum = "0")
public PagedResponse<Customer> listCustomers(Integer page, Integer size) { ... }
```

| Annotation attribute | OpenAPI schema field | Notes |
|---|---|---|
| `name` | `parameters[].name` | Required |
| `description` | `parameters[].description` | |
| `required` | `parameters[].required` | Default `false` |
| `type` | `schema.type` | `Void.class` (default) resolves to `String` |
| `format` | `schema.format` | e.g. `"int32"`, `"int64"`, `"date-time"` |
| `minimum` | `schema.minimum` | Parsed as `BigDecimal` |
| `maximum` | `schema.maximum` | Parsed as `BigDecimal` |
| `defaultValue` | `schema.default` | Parsed to the resolved Java type |

### Low-Level HttpResponse Return Types

When an endpoint returns a domain object directly, the plugin infers the response
schema from the method return type:

```java
@Get("/{customerId}")
public CustomerResponse getCustomer(String customerId) {
    return service.getCustomer(customerId);
}
```

The same inference works for asynchronous methods such as
`CompletionStage<CustomerResponse>`.

For low-level Akka responses, endpoint methods return
`akka.http.javadsl.model.HttpResponse`. That type does not carry the response body
type in the Java signature, even when the implementation uses
`HttpResponses.ok(payload)`, so annotate the method with `@OpenAPIResponseSchema`:

```java
@Get("/{customerId}")
@OpenAPIResponseSchema(CustomerResponse.class)
public HttpResponse getCustomer(String customerId) {
    return HttpResponses.ok(service.getCustomer(customerId));
}
```

This also works for asynchronous low-level responses:

```java
@Get("/{customerId}")
@OpenAPIResponseSchema(CustomerResponse.class)
public CompletionStage<HttpResponse> getCustomer(String customerId) {
    return service.getCustomer(customerId)
        .thenApply(HttpResponses::ok);
}
```

Both low-level examples produce:

```yaml
responses:
  "200":
    description: Success
    content:
      application/json:
        schema:
          $ref: "#/components/schemas/CustomerResponse"
```

> **Note:** If a method returns `HttpResponse` or `CompletionStage<HttpResponse>`
> without `@OpenAPIResponseSchema`, the generated response will have no content
> schema. The plugin logs a warning in this case.

## Documentation

- [Getting Started](docs/GETTING_STARTED.md)
- [Configuration Reference](docs/CONFIGURATION.md)
- [Examples](docs/EXAMPLES.md)
- [Troubleshooting](docs/TROUBLESHOOTING.md)

## Requirements

- Java 17 or later
- Maven 3.6.3 or later
- Akka SDK 3.0.0 or later. The repository tracks Akka SDK 3.5.17 as the
  current reference version.

## Contributing

Contributions are welcome! Please see [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

## License

This project is licensed under the Apache License 2.0 - see the [LICENSE](LICENSE) file for details.

## Acknowledgments

- [Akka SDK](https://doc.akka.io/sdk/) - The Akka platform for building reactive applications
- [Swagger Core](https://github.com/swagger-api/swagger-core) - OpenAPI implementation for Java
- [ClassGraph](https://github.com/classgraph/classgraph) - Fast classpath scanner
