package com.example.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.math.BigDecimal;

/**
 * Outer class that contains an inner record subtype ({@code VariantDelivery})
 */
public record DeliveryVariantGroup(
    String groupId,
    DeliveryMethod method) {

    /**
     * An inner record that implements {@link DeliveryMethod}.
     */
    public record VariantDelivery(
        String variantId,
        String carrier,
        BigDecimal estimatedCost) implements DeliveryMethod {}
}

