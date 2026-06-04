package com.example.endpoint;

import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import com.example.dto.DeliveryMethod;
import com.example.dto.Message;
import com.example.dto.NotificationGroup;
import com.example.dto.NotificationGroupsResponse;
import com.example.dto.NotificationType;
import com.example.dto.ScheduleDeliveryCommand;
import com.example.dto.SendNotificationCommand;
import com.github.osodevops.akka.openapi.annotations.OpenAPIResponse;
import com.github.osodevops.akka.openapi.annotations.OpenAPIResponseSchema;
import com.github.osodevops.akka.openapi.annotations.OpenAPISummary;
import com.github.osodevops.akka.openapi.annotations.OpenAPITag;

import java.util.List;

/**
 * Notification management endpoint.
 *
 * <p>Demonstrates polymorphic request/response types using a sealed interface
 * with Jackson {@code @JsonTypeInfo} and {@code @JsonSubTypes}.</p>
 */
@HttpEndpoint("/api/v1/notifications")
@OpenAPITag(name = "Notifications", description = "Notification delivery operations")
public class NotificationEndpoint {

    /**
     * Sends a notification to a customer.
     *
     * <p>The notification type is determined by the {@code type} discriminator
     * property which can be EMAIL, SMS, or PUSH.</p>
     *
     * @param command the notification command to send
     * @return the sent notification with delivery status
     */
    @Post
    @OpenAPISummary("Send a notification")
    @OpenAPIResponse(status = "201", description = "Notification sent successfully")
    @OpenAPIResponse(status = "400", description = "Invalid notification data")
    public NotificationType sendNotification(SendNotificationCommand command) {
        // Implementation would go here
        return command.notification();
    }

    /**
     * Retrieves the latest notification for a recipient.
     *
     * @param recipientId the recipient identifier
     * @return the latest notification
     */
    @Get("/{recipientId}/latest")
    @OpenAPISummary("Get latest notification for recipient")
    @OpenAPIResponseSchema(NotificationType.class)
    public NotificationType getLatestNotification(String recipientId) {
        // Implementation would go here
        return null;
    }

    /**
     * Retrieves all notifications for a recipient.
     *
     * @param recipientId the recipient identifier
     * @return list of notifications
     */
    @Get("/{recipientId}")
    @OpenAPISummary("List notifications for recipient")
    @OpenAPIResponse(status = "200", description = "Notifications retrieved successfully")
    public List<NotificationType> listNotifications(String recipientId) {
        // Implementation would go here
        return List.of();
    }

    /**
     * Retrieves notification groups for a recipient.
     *
     * <p>This endpoint uses {@link NotificationGroup} which contains an inner sealed
     * interface {@code ChannelConfig} with inner record subtypes ({@code EmailConfig},
     * {@code SmsConfig}, {@code PushConfig}). This reproduces the bug where inner-class
     * polymorphic subtypes are missing from {@code components/schemas} in the generated spec.</p>
     *
     * @param recipientId the recipient identifier
     * @return grouped notifications
     */
    @Get("/{recipientId}/groups")
    @OpenAPISummary("List notification groups for recipient")
    @OpenAPIResponseSchema(NotificationGroupsResponse.class)
    public NotificationGroupsResponse listNotificationGroups(String recipientId) {
        // Implementation would go here
        return new NotificationGroupsResponse(recipientId, List.of(), 0);
    }

    /**
     * Schedules a delivery for an order.
     *
     * <p>Reproduces the {@code VariantProductGroup.VariantProduct} pattern: the
     * {@code DeliveryMethod} sealed interface has subtypes {@code StandardDelivery},
     * {@code ExpressDelivery} (top-level), and {@code DeliveryVariantGroup.VariantDelivery}
     * (inner class of a different outer class). This triggers victools to generate
     * numbered defs like {@code VariantDelivery-2} that must be aliased away.</p>
     *
     * @param command the delivery scheduling command
     * @return the scheduled delivery method
     */
    @Post("/deliveries")
    @OpenAPISummary("Schedule a delivery")
    @OpenAPIResponse(status = "201", description = "Delivery scheduled successfully")
    @OpenAPIResponse(status = "400", description = "Invalid delivery data")
    public DeliveryMethod scheduleDelivery(ScheduleDeliveryCommand command) {
        return command.method();
    }

    /**
     * Gets the delivery method for an order.
     *
     * @param orderId the order identifier
     * @return the delivery method
     */
    @Get("/deliveries/{orderId}")
    @OpenAPISummary("Get delivery method for order")
    @OpenAPIResponseSchema(DeliveryMethod.class)
    public DeliveryMethod getDelivery(String orderId) {
        return null;
    }

    /**
     * Sends a message containing a typed notification.
     *
     * <p>Demonstrates the {@link Message}/{@link NotificationType} pattern where
     * polymorphic subtypes are inner records of the {@link com.example.dto.Notification}
     * namespace class, discriminated by the {@code type} property.</p>
     *
     * @param message the message to send
     * @return the accepted message
     */
    @Post("/messages")
    @OpenAPISummary("Send a message")
    @OpenAPIResponse(status = "201", description = "Message sent successfully")
    @OpenAPIResponse(status = "400", description = "Invalid message data")
    public Message sendMessage(Message message) {
        // Implementation would go here
        return message;
    }
}
