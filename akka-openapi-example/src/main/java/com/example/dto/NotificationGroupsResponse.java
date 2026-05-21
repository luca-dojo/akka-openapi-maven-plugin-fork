package com.example.dto;

import java.util.List;

/**
 * Response returned when listing notification groups for a recipient.
 */
public record NotificationGroupsResponse(
    String recipientId,
    List<NotificationGroup> groups,
    int totalCount) {}

