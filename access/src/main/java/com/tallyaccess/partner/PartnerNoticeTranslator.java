package com.tallyaccess.partner;

import com.tallyaccess.application.BundleFact;

/**
 * Turns a partner notice into Access's own {@link BundleFact}, or refuses it (ADR-056). The checks are in one order; the
 * first failure is the reason.
 */
public final class PartnerNoticeTranslator {

    private PartnerNoticeTranslator() {
    }

    public static BundleFact translate(PartnerNotice notice) {
        if (notice == null) {
            throw new InvalidNoticeException("notice is missing");
        }
        if (notice.partnerId() == null || !notice.partnerId().matches("[a-z][a-z0-9-]*")) {
            throw new InvalidNoticeException("partnerId is invalid");
        }
        if (notice.bundleId() == null || notice.bundleId().isBlank() || notice.bundleId().length() > 100) {
            throw new InvalidNoticeException("bundleId is invalid");
        }
        if (notice.subscriberId() == null) {
            throw new InvalidNoticeException("subscriberId is missing");
        }
        BundleFact.State state;
        if ("ACTIVATED".equals(notice.event())) {
            state = BundleFact.State.ACTIVE;
        } else if ("ENDED".equals(notice.event())) {
            state = BundleFact.State.ENDED;
        } else {
            throw new InvalidNoticeException("event is unknown: " + notice.event());
        }
        if (notice.partnerSequence() == null || notice.partnerSequence() < 1) {
            throw new InvalidNoticeException("partnerSequence is invalid");
        }
        if (notice.occurredAt() == null) {
            throw new InvalidNoticeException("occurredAt is missing");
        }
        return new BundleFact(notice.partnerId(), notice.bundleId(), notice.subscriberId(), state,
                notice.partnerSequence(), notice.occurredAt());
    }
}
