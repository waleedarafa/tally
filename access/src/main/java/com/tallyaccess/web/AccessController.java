package com.tallyaccess.web;

import com.tallyaccess.application.AccessAnswer;
import com.tallyaccess.application.AccessCopy;
import com.tallyaccess.application.Readiness;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/access/{subscriberId}} (design.md §8; docs/api/access-openapi.yaml). In Slice 2 the subscriber is the
 * account (ADR-033). An instance that has not caught up with the log answers 503 and no body (readiness).
 */
@RestController
class AccessController {

    private final AccessCopy copy;
    private final Readiness readiness;

    AccessController(AccessCopy copy, Readiness readiness) {
        this.copy = copy;
        this.readiness = readiness;
    }

    @GetMapping("/v1/access/{subscriberId}")
    ResponseEntity<AccessAnswer> access(@PathVariable String subscriberId) {
        UUID id;
        try {
            id = UUID.fromString(subscriberId);
        } catch (IllegalArgumentException notAnId) {
            return ResponseEntity.badRequest().build();
        }
        if (!readiness.ready()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return ResponseEntity.ok(copy.answer(id));
    }
}
