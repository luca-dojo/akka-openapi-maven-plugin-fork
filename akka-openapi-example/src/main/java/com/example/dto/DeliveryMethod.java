package com.example.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Polymorphic delivery method sealed interface.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = StandardDelivery.class, name = "STANDARD"),
    @JsonSubTypes.Type(value = ExpressDelivery.class, name = "EXPRESS"),
    @JsonSubTypes.Type(value = DeliveryVariantGroup.VariantDelivery.class, name = "VARIANT")
})
public sealed interface DeliveryMethod
    permits StandardDelivery,
            ExpressDelivery,
            DeliveryVariantGroup.VariantDelivery {
}

