package com.example.dto;

/**
 * An email notification sent to a customer.
 */
public record EmailNotification(
    String recipientId,
    String message,
    String subject,
    String fromAddress
) implements Notification {
}
