package com.example.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Represents a notification that can be sent to a customer.
 *
 * <p>This is a polymorphic type using Jackson annotations to support
 * multiple notification channels (email, SMS, push).</p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "channel")
@JsonSubTypes({
    @JsonSubTypes.Type(value = EmailNotification.class, name = "EMAIL"),
    @JsonSubTypes.Type(value = SmsNotification.class, name = "SMS"),
    @JsonSubTypes.Type(value = PushNotification.class, name = "PUSH")
})
public sealed interface Notification
    permits EmailNotification, SmsNotification, PushNotification {

    String recipientId();

    String message();
}
