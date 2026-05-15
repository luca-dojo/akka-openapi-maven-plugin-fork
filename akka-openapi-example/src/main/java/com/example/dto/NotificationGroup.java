package com.example.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.time.Instant;
import java.util.List;

/**
 * Represents a group of notifications batched together for delivery.
 */
public record NotificationGroup(
    String groupId,
    String recipientId,
    ChannelConfig channelConfig,
    List<NotificationItem> items,
    Status status) {

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = NotificationGroup.EmailConfig.class, name = "EMAIL"),
        @JsonSubTypes.Type(value = NotificationGroup.SmsConfig.class, name = "SMS"),
        @JsonSubTypes.Type(value = NotificationGroup.PushConfig.class, name = "PUSH")
    })
    public sealed interface ChannelConfig
        permits NotificationGroup.EmailConfig,
                NotificationGroup.SmsConfig,
                NotificationGroup.PushConfig {}

    public record EmailConfig(
        String fromAddress,
        String replyToAddress,
        String templateId) implements ChannelConfig {}

    public record SmsConfig(
        String fromNumber,
        String provider) implements ChannelConfig {}

    public record PushConfig(
        String appId,
        String platform) implements ChannelConfig {}

    public record NotificationItem(
        String itemId,
        String subject,
        Instant scheduledAt) {}

    public enum Status {
        PENDING, SENDING, DELIVERED, FAILED
    }
}


