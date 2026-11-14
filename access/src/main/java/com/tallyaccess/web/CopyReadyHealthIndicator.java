package com.tallyaccess.web;

import com.tallyaccess.application.Readiness;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Whether this instance's copy may answer (brief PR-6, ADR-036), as the health indicator {@code copyReady} in the readiness group
 * ({@code /actuator/health/readiness}, application.yml): OUT_OF_SERVICE, so 503, while the copy is behind the end of
 * the log seen at start. The documented way to add a check to readiness (review of PR-7).
 */
@Component("copyReadyHealthIndicator")
class CopyReadyHealthIndicator implements HealthIndicator {

    private final Readiness readiness;

    CopyReadyHealthIndicator(Readiness readiness) {
        this.readiness = readiness;
    }

    @Override
    public Health health() {
        return readiness.ready() ? Health.up().build()
                : Health.outOfService().withDetail("copy", "behind the end of the log seen at start").build();
    }
}
