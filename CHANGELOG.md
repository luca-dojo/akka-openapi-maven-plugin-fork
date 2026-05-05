# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.1.0](https://github.com/osodevops/akka-openapi-maven-plugin/compare/v1.0.1...v1.1.0) (2026-05-05)


### Features

* **security:** honour includeSecuritySchemes flag ([10455ee](https://github.com/osodevops/akka-openapi-maven-plugin/commit/10455eee8f6f663b5a925d5f2d1c21ac8720e924))
* **security:** Mojo-side validation for missing schemeName/name ([8b491eb](https://github.com/osodevops/akka-openapi-maven-plugin/commit/8b491ebc343fbd82cd425acd29de6ee8e897c423))
* **security:** restrict scheme types to apiKey with explicit validation ([f16d5ef](https://github.com/osodevops/akka-openapi-maven-plugin/commit/f16d5ef0f30815545cc0198eb3d411f241640c36))


### Bug Fixes

* auto-publish releases end-to-end ([b9077f6](https://github.com/osodevops/akka-openapi-maven-plugin/commit/b9077f6eea01f8e084af6f44151c94ae71d1b7cd))
* auto-publish releases end-to-end, no manual click needed ([d2df6e3](https://github.com/osodevops/akka-openapi-maven-plugin/commit/d2df6e38caba3bd555c4117dc07f9299b2aa5a23))
* **ci:** skip invoker ITs in coverage job ([923d729](https://github.com/osodevops/akka-openapi-maven-plugin/commit/923d729e498db2b7843bbe36715d003cf9421bcc))
* **ci:** skip invoker ITs in coverage job (jacoco/argLine collision) ([f07e45a](https://github.com/osodevops/akka-openapi-maven-plugin/commit/f07e45a8f9ae49e65d0695de5bd6e00f45455f58))
* close openAPISummaryShouldTargetMethodOnly test method ([b3c1a5c](https://github.com/osodevops/akka-openapi-maven-plugin/commit/b3c1a5c1466d9be4dd52ac84110d8fbbad33e93d))
* document raw Akka HttpResponse schemas ([36433b3](https://github.com/osodevops/akka-openapi-maven-plugin/commit/36433b3076c01fd1d8d52ba59d85a26b2871f7e8))
* **release:** publish Maven Central releases from release-please ([f6a9953](https://github.com/osodevops/akka-openapi-maven-plugin/commit/f6a995373b9afefbe79ea9bb210fbca8788b7957))
* **release:** publish Maven Central releases from release-please ([6556db6](https://github.com/osodevops/akka-openapi-maven-plugin/commit/6556db6b3dc0ac7a1401a4bc30efeaf89d0f0edc))
* resolve merge-conflict markers in AnnotationRetentionTest ([52b62ce](https://github.com/osodevops/akka-openapi-maven-plugin/commit/52b62cec62f0b7939b570da53332537341469f13))


### Documentation

* document &lt;security&gt; block + @OpenAPISummary; expose includeSecuritySchemes flag ([dccdaee](https://github.com/osodevops/akka-openapi-maven-plugin/commit/dccdaee86fe659ce52f231aa6e93684aa171d42c))

## [Unreleased]

### Added
- New annotation `@OpenAPIResponseSchema(Class<?>)` to explicitly declare the
  response payload type for low-level `HttpResponse` and
  `CompletionStage<HttpResponse>` endpoint methods.
- Response schema inference now unwraps `CompletionStage<T>` return types before
  generating OpenAPI response content.
- Tests and documentation were added for low-level Akka `HttpResponse` handling.
- The Akka SDK reference version is updated to 3.5.17, the current Akka SDK
  release listed by the official Akka release notes.
- New annotation `@OpenAPISummary("...")` maps to the OpenAPI `summary` field
  on an operation. Use it to give operations a short, single-line label that
  appears in tools such as Swagger UI.
- New `<security>` configuration block on the Maven plugin lets users declare
  apiKey-based security schemes (header, query, or cookie). The plugin emits
  them under `components.securitySchemes` and adds matching top-level
  `security` requirements. The existing `<includeSecuritySchemes>` flag is now
  honoured to suppress emission without removing config.

### Changed
- Misconfigured `<security>` blocks now fail fast with an actionable
  `MojoExecutionException` instead of producing spec-invalid YAML. Currently
  only `type: apiKey` is supported; `http`, `oauth2`, and `openIdConnect` are
  rejected with a clear error pending a follow-up.

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
- Akka SDK 3.5.17
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
