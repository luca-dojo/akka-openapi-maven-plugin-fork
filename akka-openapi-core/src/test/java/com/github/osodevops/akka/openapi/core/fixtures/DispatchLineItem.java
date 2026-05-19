package com.github.osodevops.akka.openapi.core.fixtures;

import java.util.List;

/**
 * A line item within a dispatch group.
 * Contains its own inner Status enum with values that differ from ActiveDispatchGroup.Status.
 */
public record DispatchLineItem(
    String id,
    String productId,
    String name,
    Status status,
    String note,
    List<DispatchModifier> modifiers
) {

    public enum Status {
        PENDING,
        DISPATCHED,
        CANCELLED
    }

    public record DispatchModifier(
        String name,
        String value
    ) {}
}
