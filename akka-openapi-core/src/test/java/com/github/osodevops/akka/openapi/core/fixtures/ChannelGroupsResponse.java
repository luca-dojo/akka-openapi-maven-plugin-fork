package com.github.osodevops.akka.openapi.core.fixtures;

import java.util.List;

/**
 * Test fixture: wrapper response that reaches {@link ChannelGroup} through a collection,
 * mirroring how a list endpoint pulls in a DTO with a polymorphic field.
 */
public record ChannelGroupsResponse(List<ChannelGroup> groups) {}
