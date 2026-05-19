package com.github.osodevops.akka.openapi.core.fixtures;

import java.time.Instant;
import java.util.List;

/**
 * Represents an active dispatch group containing line items.
 * Has its own inner Status enum that clashes with DispatchLineItem.Status.
 */
public record ActiveDispatchGroup(
    String dispatchGroupId,
    String orderId,
    String orderReference,
    List<DispatchLineItem> items,
    Status status,
    Instant createdAt,
    Instant updatedAt
) {

    public enum Status {
        PENDING,
        DISPATCHED
    }
}
