package com.example.dto;

import java.math.BigDecimal;

/**
 * Express (next-day) delivery option.
 */
public record ExpressDelivery(
    String carrierId,
    boolean signatureRequired,
    BigDecimal cost) implements DeliveryMethod {
}

