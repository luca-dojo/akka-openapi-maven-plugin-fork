package com.example.dto;

/**
 * A message containing a notification to be delivered to a recipient.
 *
 * <p>The {@code notificationType} field is a polymorphic type discriminated by the
 * {@code type} property, which can be {@code EMAIL}, {@code SMS}, or {@code PUSH}.</p>
 */
public record Message(NotificationType notificationType) {}
