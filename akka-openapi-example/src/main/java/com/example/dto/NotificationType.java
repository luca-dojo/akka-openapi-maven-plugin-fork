package com.example.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Sealed interface representing the type of a notification.
 *
 * <p>Polymorphic deserialisation is driven by the {@code type} property.
 * Permitted subtypes are the inner records of {@link Notification}.</p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = Notification.EmailNotification.class, name = "EMAIL"),
    @JsonSubTypes.Type(value = Notification.SmsNotification.class, name = "SMS"),
    @JsonSubTypes.Type(value = Notification.PushNotification.class, name = "PUSH"),
})
public sealed interface NotificationType
    permits Notification.EmailNotification, Notification.SmsNotification, Notification.PushNotification {}
