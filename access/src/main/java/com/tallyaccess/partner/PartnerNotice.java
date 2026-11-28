package com.tallyaccess.partner;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.UUID;

/** Partner notice version 1, the language Tally publishes to telecom partners (ADR-056). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PartnerNotice(String partnerId, String bundleId, UUID subscriberId, String event,
        Long partnerSequence, Instant occurredAt) {
}
