package com.github.osodevops.akka.openapi.core.fixtures;

/**
 * Test fixture: a record with a field typed as a top-level discriminated sealed interface
 * ({@link Shape}). When the parent schema already exists, victools renders the field inline
 * as an {@code anyOf} of suffixed duplicate refs that must collapse back to a single
 * {@code $ref} to the parent.
 */
public record ShipmentCommand(String orderId, Shape shape) {}
