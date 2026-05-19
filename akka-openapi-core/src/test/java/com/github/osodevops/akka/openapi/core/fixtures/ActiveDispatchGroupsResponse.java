package com.github.osodevops.akka.openapi.core.fixtures;

import java.util.List;

/**
 * Response wrapper containing a list of active dispatch groups.
 * This is the root type that triggers generation of the full hierarchy,
 * including ActiveDispatchGroup and DispatchLineItem with their clashing Status enums.
 */
public record ActiveDispatchGroupsResponse(
    List<ActiveDispatchGroup> dispatchGroups
) {}
