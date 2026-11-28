package com.tallyaccess.partner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tallyaccess.application.BundleFact;
import com.tallyaccess.application.BundleFact.State;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PartnerNoticeTranslatorTest {

    static final UUID S = UUID.fromString("6b7c8d9e-0f1a-4b2c-9d3e-4f5a6b7c8d9e");
    static final Instant T = Instant.parse("2026-11-27T08:00:00Z");

    static PartnerNotice notice(String partnerId, String bundleId, UUID subscriberId, String event,
            Long partnerSequence, Instant occurredAt) {
        return new PartnerNotice(partnerId, bundleId, subscriberId, event, partnerSequence, occurredAt);
    }

    @Test
    void activatedIsActive() {
        assertThat(PartnerNoticeTranslator.translate(notice("etisalat", "B-1", S, "ACTIVATED", 3L, T)))
                .isEqualTo(new BundleFact("etisalat", "B-1", S, State.ACTIVE, 3L, T));
    }

    @Test
    void endedIsEnded() {
        assertThat(PartnerNoticeTranslator.translate(notice("etisalat", "B-1", S, "ENDED", 3L, T)))
                .isEqualTo(new BundleFact("etisalat", "B-1", S, State.ENDED, 3L, T));
    }

    @Test
    void aBadPartnerIdIsRefused() {
        assertThatThrownBy(() -> PartnerNoticeTranslator.translate(notice("Bad_Partner", "B-1", S, "ACTIVATED", 3L, T)))
                .isInstanceOf(InvalidNoticeException.class)
                .hasMessage("partnerId is invalid");
    }

    @Test
    void aBlankBundleIdIsRefused() {
        assertThatThrownBy(() -> PartnerNoticeTranslator.translate(notice("etisalat", " ", S, "ACTIVATED", 3L, T)))
                .isInstanceOf(InvalidNoticeException.class)
                .hasMessage("bundleId is invalid");
    }

    @Test
    void aBundleIdLongerThan100CharactersIsRefused() {
        assertThatThrownBy(() -> PartnerNoticeTranslator.translate(
                notice("etisalat", "x".repeat(101), S, "ACTIVATED", 3L, T)))
                .isInstanceOf(InvalidNoticeException.class)
                .hasMessage("bundleId is invalid");
    }

    @Test
    void aMissingSubscriberIdIsRefused() {
        assertThatThrownBy(() -> PartnerNoticeTranslator.translate(notice("etisalat", "B-1", null, "ACTIVATED", 3L, T)))
                .isInstanceOf(InvalidNoticeException.class)
                .hasMessage("subscriberId is missing");
    }

    @Test
    void anUnknownEventIsRefused() {
        assertThatThrownBy(() -> PartnerNoticeTranslator.translate(notice("etisalat", "B-1", S, "PAUSED", 3L, T)))
                .isInstanceOf(InvalidNoticeException.class)
                .hasMessage("event is unknown: PAUSED");
    }

    @Test
    void aPartnerSequenceBelowOneIsRefused() {
        assertThatThrownBy(() -> PartnerNoticeTranslator.translate(notice("etisalat", "B-1", S, "ACTIVATED", 0L, T)))
                .isInstanceOf(InvalidNoticeException.class)
                .hasMessage("partnerSequence is invalid");
    }

    @Test
    void aMissingOccurredAtIsRefused() {
        assertThatThrownBy(() -> PartnerNoticeTranslator.translate(notice("etisalat", "B-1", S, "ACTIVATED", 3L, null)))
                .isInstanceOf(InvalidNoticeException.class)
                .hasMessage("occurredAt is missing");
    }

    @Test
    void aMissingNoticeIsRefused() {
        assertThatThrownBy(() -> PartnerNoticeTranslator.translate(null))
                .isInstanceOf(InvalidNoticeException.class)
                .hasMessage("notice is missing");
    }
}
