package com.github.osodevops.akka.openapi.core.fixtures;

import java.time.Instant;

/**
 * Email notification domain object for testing clashing $ref resolution.
 * Contains an inner Status enum that clashes with SmsNotification.Status.
 */
public class EmailNotification {

    public enum Status {
        QUEUED,
        SENT,
        DELIVERED,
        BOUNCED
    }

    private String id;
    private String recipient;
    private String subject;
    private String body;
    private Status status;
    private Instant createdAt;
    private Instant deliveredAt;

    public EmailNotification() {}

    public EmailNotification(String id, String recipient, String subject, String body, Status status, Instant createdAt, Instant deliveredAt) {
        this.id = id;
        this.recipient = recipient;
        this.subject = subject;
        this.body = body;
        this.status = status;
        this.createdAt = createdAt;
        this.deliveredAt = deliveredAt;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getRecipient() { return recipient; }
    public void setRecipient(String recipient) { this.recipient = recipient; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getDeliveredAt() { return deliveredAt; }
    public void setDeliveredAt(Instant deliveredAt) { this.deliveredAt = deliveredAt; }
}
