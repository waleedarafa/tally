package com.tally.access;

import java.util.UUID;

/** Can the customer use the service? Separate from "is the customer subscribed?" (FR-013). */
public interface AccessApi {

    boolean hasAccess(UUID accountId);
}
