package com.example.dto;

/**
 * An SMS notification sent to a customer.
 */
public record SmsNotification(
    String recipientId,
    String message,
    String phoneNumber
) implements Notification {
}
