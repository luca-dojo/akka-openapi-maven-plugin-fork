package com.github.osodevops.akka.openapi.core.fixtures;

/**
 * Test fixture: an endpoint whose response nests a record named {@code Baz}.
 * A different endpoint ({@link BarEndpoint}) nests a differently-shaped {@code Baz},
 * exercising cross-endpoint nested same-name disambiguation.
 */
public class FooEndpoint {

    public record Foo(String id, Baz baz) {}

    public record Baz(String value) {}
}
