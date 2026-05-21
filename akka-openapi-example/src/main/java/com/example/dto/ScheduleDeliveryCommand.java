package com.example.dto;

/**
 * Command to schedule a delivery.
 */
public record ScheduleDeliveryCommand(
    String orderId,
    DeliveryMethod method) {
}

