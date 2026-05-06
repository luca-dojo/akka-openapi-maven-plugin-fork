package com.example.dto;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A device token identifier for push notifications.
 */
public record DeviceToken(@JsonValue long tokenId) {
}
