package com.example.dto;

import java.util.Optional;

/**
 * An email notification sent to a customer.
 */
public record EmailNotification(
    String recipientId,
    String message,
    String subject,
    String fromAddress,
    Optional<String> title,
    Optional<String> deviceToken
) implements Notification {
}

