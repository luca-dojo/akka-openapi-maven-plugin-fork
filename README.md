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
            <version>1.1.0</version>
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
    <version>1.1.0</version>
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
                <url>https://api.example.com</url>
                <description>Production</description>
            </server>
        </servers>

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
