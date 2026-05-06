package com.example.dto;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * A notification title.
 */
public record Title(@JsonValue String title) {
}
