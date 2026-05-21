package com.github.osodevops.akka.openapi.core.fixtures;

import java.time.Instant;

/**
 * SMS notification domain object for testing clashing $ref resolution.
 * Contains an inner Status enum that clashes with EmailNotification.Status.
 */
public class SmsNotification {

    public enum Status {
        QUEUED,
        SENT,
        FAILED
    }

    private String id;
    private String phoneNumber;
    private String message;
    private Status status;
    private Instant createdAt;

    public SmsNotification() {}

    public SmsNotification(String id, String phoneNumber, String message, Status status, Instant createdAt) {
        this.id = id;
        this.phoneNumber = phoneNumber;
        this.message = message;
        this.status = status;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
