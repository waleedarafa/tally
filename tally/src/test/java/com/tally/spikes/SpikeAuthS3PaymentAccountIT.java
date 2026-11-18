package com.tally.spikes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.payment.AttemptState;
import com.tally.payment.PaymentApi;
import com.tally.payment.PaymentStatus;
import com.tally.shared.Money;
import com.tally.support.AbstractProviderIntegrationTest;
import com.tally.support.FakeProvider;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Slice 4 spike S-3: can each controller find the account of what it is asked about without a new module dependency?
 * Shown for {@code getPaymentStatus}, the hardest case: it names a payment reference, not an account.
 *
 * <ol>
 *   <li>{@code PaymentStatus} carries no account today.</li>
 *   <li>Payment already knows the account of each reference: one collection request per reference, with its
 *       account, and every attempt carries the same account. A second request for the reference with another
 *       account changes nothing. So {@code PaymentStatus} can gain the account (an added field) from data Payment
 *       holds, with no migration.</li>
 *   <li>The pinned graph lets {@code payment} and {@code account} both use {@code platform}, and {@code platform}
 *       uses neither: a caller type in {@code platform}, filled by {@code account} and read by {@code payment}'s web
 *       layer, needs no new line.</li>
 * </ol>
 */
@TestPropertySource(properties = {
        "tally.platform.polling-enabled=true",
        "tally.platform.poll-interval=100ms"
})
class SpikeAuthS3PaymentAccountIT extends AbstractProviderIntegrationTest {

    @Autowired
    PaymentApi payments;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void paymentStatusCarriesNoAccountToday() {
        assertThat(Arrays.stream(PaymentStatus.class.getRecordComponents()).map(RecordComponent::getName))
                .containsExactly("reference", "state", "failureReason");
    }

    @Test
    void paymentKnowsTheOneAccountOfEachReference() {
        String reference = "SPIKE-AUTH-S3-" + UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        UUID someoneElse = UUID.randomUUID();

        payments.requestCollection(reference, owner, Money.of(999, "USD"), FakeProvider.token());
        payments.requestCollection(reference, someoneElse, Money.of(999, "USD"), FakeProvider.token());
        await().atMost(Duration.ofSeconds(10)).until(() -> payments.status(reference)
                .map(s -> s.state() == AttemptState.SUCCEEDED).orElse(false));

        assertThat(jdbc.queryForList("SELECT account_id FROM payment.collection_request WHERE reference = ?",
                UUID.class, reference)).containsExactly(owner);
        List<UUID> attempts = jdbc.queryForList("SELECT account_id FROM payment.payment_attempt WHERE reference = ?",
                UUID.class, reference);
        assertThat(attempts).isNotEmpty().containsOnly(owner);
    }

    @Test
    void thePinnedGraphLetsPaymentAndAccountShareATypeInPlatform() throws IOException {
        List<String> graph = Files.readAllLines(Path.of("src/test/resources/architecture/module-dependencies.txt"));

        assertThat(graph).contains("platform: shared", "account: shared, platform", "payment: shared, platform");
    }
}
