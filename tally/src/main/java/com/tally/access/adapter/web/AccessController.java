package com.tally.access.adapter.web;

import com.tally.access.AccessApi;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AccessController {

    record AccessResponse(UUID accountId, boolean hasAccess) {
    }

    private final AccessApi access;

    AccessController(AccessApi access) {
        this.access = access;
    }

    @GetMapping("/v1/accounts/{id}/access")
    AccessResponse access(@PathVariable UUID id) {
        return new AccessResponse(id, access.hasAccess(id));
    }
}
