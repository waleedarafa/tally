package com.tallyaccess.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.tallyaccess.AccessTestSupport;
import com.tallyaccess.application.AccessAnswer;
import com.tallyaccess.application.AccessAnswer.Reason;
import com.tallyaccess.application.AccessCopy;
import com.tallyaccess.application.BundleFact;
import com.tallyaccess.application.BundleFact.State;
import com.tallyaccess.application.BundleStore;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Partner bundles in Access's own database: stored, and answered from them (ADR-056). */
@SpringBootTest
class PartnerBundleIT {

    @DynamicPropertySource
    static void isolate(DynamicPropertyRegistry registry) {
        AccessTestSupport.isolate(registry);
    }

    static final Instant T1 = Instant.parse("2026-11-27T08:00:00Z");
    static final Instant T2 = Instant.parse("2026-11-27T09:00:00Z");

    @Autowired
    AccessCopy copy;

    @Autowired
    BundleStore bundles;

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void deleteBundles() {
        jdbc.update("DELETE FROM access.partner_bundle");
    }

    @Test
    void anActivatedBundleAnswersYes() {
        UUID subscriber = UUID.randomUUID();

        assertThat(copy.applyBundle(new BundleFact("etisalat", "B-1", subscriber, State.ACTIVE, 1L, T1))).isTrue();

        assertThat(copy.answer(subscriber)).isEqualTo(new AccessAnswer(subscriber, true, Reason.ACTIVE_SOURCE, T1, null));
    }

    @Test
    void anEndedBundleAnswersNoActiveSource() {
        UUID subscriber = UUID.randomUUID();

        assertThat(copy.applyBundle(new BundleFact("etisalat", "B-1", subscriber, State.ACTIVE, 1L, T1))).isTrue();
        assertThat(copy.applyBundle(new BundleFact("etisalat", "B-1", subscriber, State.ENDED, 2L, T2))).isTrue();

        assertThat(copy.answer(subscriber)).isEqualTo(new AccessAnswer(subscriber, false, Reason.NO_ACTIVE_SOURCE, T2, null));
    }

    @Test
    void anOlderOrRepeatedNoticeChangesNothing() {
        UUID subscriber = UUID.randomUUID();

        assertThat(copy.applyBundle(new BundleFact("etisalat", "B-1", subscriber, State.ENDED, 2L, T2))).isTrue();
        assertThat(copy.applyBundle(new BundleFact("etisalat", "B-1", subscriber, State.ACTIVE, 1L, T1))).isFalse();
        assertThat(copy.applyBundle(new BundleFact("etisalat", "B-1", subscriber, State.ENDED, 2L, T2))).isFalse();

        assertThat(copy.answer(subscriber).canWatch()).isFalse();
        List<BundleFact> facts = bundles.findBySubscriber(subscriber);
        assertThat(facts).hasSize(1);
        assertThat(facts.get(0).state()).isEqualTo(State.ENDED);
        assertThat(facts.get(0).partnerSequence()).isEqualTo(2L);
    }

    @Test
    void aSubscriberWithNothingIsNotKnown() {
        UUID subscriber = UUID.randomUUID();

        assertThat(copy.answer(subscriber)).isEqualTo(new AccessAnswer(subscriber, false, Reason.NOT_KNOWN, null, null));
    }

    @Test
    void twoBundlesOfOneSubscriberAreBothFound() {
        UUID subscriber = UUID.randomUUID();

        assertThat(copy.applyBundle(new BundleFact("etisalat", "B-1", subscriber, State.ACTIVE, 1L, T1))).isTrue();
        assertThat(copy.applyBundle(new BundleFact("etisalat", "B-2", subscriber, State.ACTIVE, 1L, T1))).isTrue();

        List<BundleFact> facts = bundles.findBySubscriber(subscriber);
        assertThat(facts).hasSize(2);
        assertThat(facts).extracting(BundleFact::bundleId).containsExactly("B-1", "B-2");
    }
}
