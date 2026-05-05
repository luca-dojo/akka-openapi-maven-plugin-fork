package com.example.dto;

/**
 * A push notification sent to a customer's device.
 */
public record PushNotification(
    String recipientId,
    String message,
    String title,
    String deviceToken
) implements Notification {
}

