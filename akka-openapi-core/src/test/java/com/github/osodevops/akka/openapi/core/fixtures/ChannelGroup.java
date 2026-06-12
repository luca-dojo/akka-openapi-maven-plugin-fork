package com.github.osodevops.akka.openapi.core.fixtures;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Test fixture: a record with a field typed as a nested discriminated sealed interface.
 * When reached through a wrapper type ({@link ChannelGroupsResponse}), victools renders the
 * field inline as an {@code anyOf} of the subtype refs; it must be replaced by a
 * {@code $ref} to the discriminated parent component.
 */
public record ChannelGroup(
    String groupId,
    Channel channel) {

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = EmailChannel.class, name = "EMAIL"),
        @JsonSubTypes.Type(value = SmsChannel.class, name = "SMS")
    })
    public sealed interface Channel permits EmailChannel, SmsChannel {}

    public record EmailChannel(String fromAddress) implements Channel {}

    public record SmsChannel(String fromNumber) implements Channel {}
}
