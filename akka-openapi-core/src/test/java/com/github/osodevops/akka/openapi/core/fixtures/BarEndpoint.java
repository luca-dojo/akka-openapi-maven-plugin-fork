package com.github.osodevops.akka.openapi.core.fixtures;

/**
 * Test fixture: an endpoint whose response nests a record named {@code Baz} with a
 * different shape than {@link FooEndpoint.Baz}.
 */
public class BarEndpoint {

    public record Bar(String id, Baz baz) {}

    public record Baz(int value) {}
}
