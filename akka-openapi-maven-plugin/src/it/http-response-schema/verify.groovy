File spec = new File(basedir, "target/openapi.yaml")
assert spec.isFile()

String yaml = spec.text

assert yaml.contains("/customers/{customerId}:")
assert yaml.contains("/customers/raw/{customerId}:")
assert yaml.contains("/customers/async/{customerId}:")
assert yaml.contains("/customers/direct-async/{customerId}:")

assert yaml.contains('$ref: "#/components/schemas/GetCustomerResponse"')
assert yaml.contains("GetCustomerResponse:")
assert yaml.contains("customerId:")
assert yaml.contains("displayName:")

int rawPath = yaml.indexOf("/customers/raw/{customerId}:")
assert rawPath >= 0
List<Integer> followingPathStarts = [
        yaml.indexOf("/customers/{customerId}:"),
        yaml.indexOf("/customers/async/{customerId}:"),
        yaml.indexOf("/customers/direct-async/{customerId}:")
].findAll { it > rawPath }.sort()
int nextPath = followingPathStarts ? followingPathStarts[0] : yaml.length()

String rawOperation = yaml.substring(rawPath, nextPath)
assert rawOperation.contains('"200":')
assert rawOperation.contains("description: Success")
assert !rawOperation.contains('$ref: "#/components/schemas/GetCustomerResponse"')
