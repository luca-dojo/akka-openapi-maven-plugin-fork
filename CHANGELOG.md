# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.2] - 2026-04-29

### Added
- Support for extracting the inner payload type from Akka / Pekko HTTP wrapper return types (e.g. `HttpResponse<T>`). When an endpoint declares `HttpResponse<CustomerResponse>` the OpenAPI response content will now use `CustomerResponse` as the response schema instead of the HTTP wrapper.
- New annotation `@OpenAPIResponseSchema(Class<?>)` (in `akka-openapi-annotations`) to explicitly declare the response payload type for methods that return a raw `HttpResponse` with no generic type parameter.
- Unit tests and integration test coverage for the new HttpResponse unwrapping behaviour and the explicit annotation fallback were added to `akka-openapi-core` and `akka-openapi-example`.
- Documentation updated (README) to describe the two supported approaches: parameterized `HttpResponse<T>` and the `@OpenAPIResponseSchema` annotation for raw `HttpResponse` returns.

## [1.0.1] - 2026-04-20

### Fixed
- OpenAPI annotation extraction is now wired up end-to-end. The `@OpenAPITag`,
  `@OpenAPIResponse`, `@OpenAPIInfo`, and `@OpenAPIExample` annotations
  shipped in 1.0.0 were not actually read during generation; they now
  populate the generated spec as documented ([#17], [#18]).

### Security
- Pinned all GitHub Actions to immutable commit SHAs to mitigate supply-chain
  risk from mutable tags ([#19]).

### CI
- Granted `checks: write` / `pull-requests: write` permissions to the CI
  workflow so that test-result publishing no longer fails with a 403 on push
  to `main`.

## [1.0.0] - 2026-01-25

### Added
- Initial release of Akka OpenAPI Maven Plugin
- OpenAPI 3.1 specification generation from Akka SDK `@HttpEndpoint` annotations
- Automatic schema generation from Java POJOs using jsonschema-generator
- Support for Akka SDK HTTP method annotations (`@Get`, `@Post`, `@Put`, `@Delete`, `@Patch`)
- Path parameter extraction from URL patterns
- Request body inference from complex type parameters
- Response schema generation from method return types
- Custom OpenAPI annotations for enhanced documentation:
  - `@OpenAPITag` for endpoint grouping
  - `@OpenAPIResponse` for documenting response codes
  - `@OpenAPIInfo` for API metadata
  - `@OpenAPIExample` for request/response examples
- Jakarta Validation annotation support for schema constraints
- Jackson annotation support for property naming and formatting
- Server configuration in plugin settings
- YAML and JSON output format options
- OpenAPI specification validation using swagger-parser
- Circular reference detection and handling
- Example project demonstrating plugin usage

### Dependencies
- Akka SDK 3.0.2
- Swagger Core 2.2.25
- Swagger Parser 2.1.22
- ClassGraph 4.8.182
- jsonschema-generator 4.38.0
- Jackson 2.18.2
- Java 17+

[Unreleased]: https://github.com/osodevops/akka-openapi-maven-plugin/compare/v1.0.1...HEAD
[1.0.1]: https://github.com/osodevops/akka-openapi-maven-plugin/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/osodevops/akka-openapi-maven-plugin/releases/tag/v1.0.0
[#17]: https://github.com/osodevops/akka-openapi-maven-plugin/issues/17
[#18]: https://github.com/osodevops/akka-openapi-maven-plugin/pull/18
[#19]: https://github.com/osodevops/akka-openapi-maven-plugin/pull/19
