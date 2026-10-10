package com.tally.entitlement;

import java.util.UUID;

/** Can the customer use the service? Separate from "is the customer subscribed?" (FR-013). */
public interface EntitlementApi {

    boolean hasAccess(UUID accountId);
}
