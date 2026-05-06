package com.example.dto;

import java.util.Optional;

/**
 * An SMS notification sent to a customer.
 */
public record SmsNotification(
    String recipientId,
    String message,
    String phoneNumber,
    Optional<Title> title,
    Optional<DeviceToken> deviceToken
) implements Notification {
}

