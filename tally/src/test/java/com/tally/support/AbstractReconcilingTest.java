package com.tally.support;

import static org.awaitility.Awaitility.await;

import com.tally.account.AccountApi;
import com.tally.invoicing.InvoiceView;
import com.tally.invoicing.InvoicingApi;
import com.tally.payment.AttemptState;
import com.tally.payment.PaymentApi;
import com.tally.subscription.SubscriptionApi;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;


/** End-to-end tests that need reconciliation (FR-018) running on this context's clock. */
@TestPropertySource(properties = {
        "tally.platform.polling-enabled=true",
        "tally.platform.poll-interval=100ms",
        "tally.test.context=reconciliation"
})
public abstract class AbstractReconcilingTest extends AbstractProviderIntegrationTest {

    @Autowired
    protected AccountApi accounts;

    @Autowired
    protected SubscriptionApi subscriptions;

    @Autowired
    protected InvoicingApi invoicing;

    @Autowired
    protected PaymentApi payments;

    @Autowired
    protected MeterRegistry meters;

    @Autowired
    protected JdbcTemplate jdbc;

    /**
     * The class has a context and a database of its own (ADR-029, proposed): reconciliation runs once as the context
     * starts, and its next run is set from the clock when that run ends. Let it end before a test moves time.
     */
    @BeforeEach
    void firstReconciliationEnded() {
        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.queryForObject(
                "SELECT last_success_at FROM payment.jobs WHERE queue_key = 'payment-reconciliation'",
                java.time.OffsetDateTime.class) != null);
    }

    protected UUID subscribedAccount() {
        UUID account = accounts.open(Currency.getInstance("USD"), ZoneId.of("Europe/London")).id();
        accounts.recordPaymentToken(account, FakeProvider.token());
        subscriptions.subscribe(account, "STANDARD");
        return account;
    }

    protected InvoiceView firstInvoice(UUID account) {
        await().atMost(Duration.ofSeconds(10)).until(() -> !invoicing.invoicesFor(account).isEmpty());
        return invoicing.invoicesFor(account).getFirst();
    }

    protected void awaitAttempt(String reference, AttemptState state, Duration atMost) {
        await().atMost(atMost).until(() -> payments.status(reference).map(s -> s.state() == state).orElse(false));
    }

    /** Waits until scheduled reconciliation has brought the attempt to {@code state}. */
    protected void awaitReconciled(String reference, AttemptState state, Duration atMost) {
        await().atMost(atMost).until(() -> payments.status(reference).map(s -> s.state() == state).orElse(false));
    }

    protected double corrected(String from, String to) {
        Counter counter = meters.find("tally.payment.outcome_corrected").tags("from", from, "to", to).counter();
        return counter == null ? 0 : counter.count();
    }
}
