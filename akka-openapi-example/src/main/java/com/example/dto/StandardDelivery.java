package com.example.dto;

import java.math.BigDecimal;

/**
 * Standard (ground) delivery option.
 */
public record StandardDelivery(
    String carrierId,
    String serviceLevel,
    BigDecimal cost) implements DeliveryMethod {
}

