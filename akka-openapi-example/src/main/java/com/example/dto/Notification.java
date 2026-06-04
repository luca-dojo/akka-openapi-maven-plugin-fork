package com.example.dto;

import java.util.Optional;

/**
 * Namespace class containing the concrete notification type implementations.
 *
 * <p>Each inner record implements {@link NotificationType}, enabling polymorphic
 * serialisation via the {@code type} discriminator property.</p>
 */
public final class Notification {

    private Notification() {}

    /**
     * An email notification sent to a customer.
     */
    public record EmailNotification(
        String recipientId,
        String message,
        String subject,
        String fromAddress,
        Optional<Title> title,
        Optional<DeviceToken> deviceToken
    ) implements NotificationType {}

    /**
     * An SMS notification sent to a customer.
     */
    public record SmsNotification(
        String recipientId,
        String message,
        String phoneNumber,
        Optional<Title> title,
        Optional<DeviceToken> deviceToken
    ) implements NotificationType {}

    /**
     * A push notification sent to a customer's device.
     */
    public record PushNotification(
        String recipientId,
        String message,
        Optional<Title> title,
        Optional<DeviceToken> deviceToken
    ) implements NotificationType {}
}