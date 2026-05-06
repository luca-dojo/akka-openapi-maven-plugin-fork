package com.example.dto;

import java.util.Optional;

/**
 * A push notification sent to a customer's device.
 */
public record PushNotification(
    String recipientId,
    String message,
    Optional<Title> title,
    Optional<DeviceToken> deviceToken
) implements Notification {
}
