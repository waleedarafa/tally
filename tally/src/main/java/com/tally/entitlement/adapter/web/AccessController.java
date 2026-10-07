package com.tally.entitlement.adapter.web;

import com.tally.entitlement.EntitlementApi;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AccessController {

    record AccessResponse(UUID accountId, boolean hasAccess) {
    }

    private final EntitlementApi entitlements;

    AccessController(EntitlementApi entitlements) {
        this.entitlements = entitlements;
    }

    @GetMapping("/v1/accounts/{id}/access")
    AccessResponse access(@PathVariable UUID id) {
        return new AccessResponse(id, entitlements.hasAccess(id));
    }
}
