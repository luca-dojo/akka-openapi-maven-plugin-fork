File spec = new File(basedir, "target/openapi.yaml")
assert spec.isFile() : "Generated OpenAPI spec not found at ${spec}"

String yaml = spec.text

// components.securitySchemes block exists with all three entries
assert yaml.contains("securitySchemes:")
assert yaml.contains("CustomAuthHeader:")
assert yaml.contains("ApiKeyQuery:")
assert yaml.contains("SessionCookie:")

// All entries are apiKey type with their declared names and locations
assert yaml.contains("type: apiKey")
assert yaml.contains("name: x-custom-auth")
assert yaml.contains("name: api_key")
assert yaml.contains("name: session_id")
assert yaml.contains("in: header")
assert yaml.contains("in: query")
assert yaml.contains("in: cookie")

// Optional description is emitted when set, omitted when null
assert yaml.contains("description: Custom authentication header")

// Top-level security: array references each scheme
int securityIdx = yaml.indexOf("\nsecurity:")
assert securityIdx >= 0 : "Top-level security: array not found"
String topLevelSecurity = yaml.substring(securityIdx, Math.min(yaml.length(), securityIdx + 400))
assert topLevelSecurity.contains("- CustomAuthHeader:")
assert topLevelSecurity.contains("- ApiKeyQuery:")
assert topLevelSecurity.contains("- SessionCookie:")

// Confirm @OpenAPISummary made it through to the operation summary field
assert yaml.contains("summary: Get a widget by id")

// Path was scanned and emitted
assert yaml.contains("/widgets/{id}:")
