package com.example.endpoint;

import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import com.example.dto.Notification;
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
     * <p>The notification type is determined by the {@code channel} discriminator
     * property which can be EMAIL, SMS, or PUSH.</p>
     *
     * @param notification the notification to send
     * @return the sent notification with delivery status
     */
    @Post
    @OpenAPISummary("Send a notification")
    @OpenAPIResponse(status = "201", description = "Notification sent successfully")
    @OpenAPIResponse(status = "400", description = "Invalid notification data")
    public Notification sendNotification(Notification notification) {
        // Implementation would go here
        return notification;
    }

    /**
     * Retrieves the latest notification for a recipient.
     *
     * @param recipientId the recipient identifier
     * @return the latest notification
     */
    @Get("/{recipientId}/latest")
    @OpenAPISummary("Get latest notification for recipient")
    @OpenAPIResponseSchema(Notification.class)
    public Notification getLatestNotification(String recipientId) {
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
    public List<Notification> listNotifications(String recipientId) {
        // Implementation would go here
        return List.of();
    }
}

