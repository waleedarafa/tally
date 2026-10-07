package com.tally.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.account.AccountApi;
import com.tally.invoicing.InvoiceStatus;
import com.tally.invoicing.InvoiceView;
import com.tally.invoicing.InvoicingApi;
import com.tally.subscription.SubscriptionApi;
import com.tally.support.AbstractProviderIntegrationTest;
import com.tally.support.FakeProvider;
import com.tally.support.Quiesce;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;

/**
 * AT-4. Provider outage mid-run (D3, D7). Pass when: the breaker opens; nothing is claimed while it
 * is open; invoices are still created; everything is charged after recovery; checkout returns
 * PROCESSING. The breaker runs on its production settings (opens at 50% of at least 5 calls, 30 s).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "tally.platform.polling-enabled=true",
        "tally.platform.poll-interval=100ms",
        "tally.test.context=at04"
})
class AT04ProviderOutageIT extends AbstractProviderIntegrationTest {

    static final int ACCOUNTS = 20;

    @LocalServerPort
    int port;

    @Autowired
    AccountApi accounts;

    @Autowired
    SubscriptionApi subscriptions;

    @Autowired
    InvoicingApi invoicing;

    @Autowired
    MeterRegistry meters;

    @Autowired
    JdbcTemplate jdbc;

    RestClient tally;

    @BeforeEach
    void client() {
        tally = RestClient.create("http://localhost:" + port);
    }

    double breakerState() {
        return meters.get("tally.payment.breaker.state").gauge().value();
    }

    List<String> renewalReferences(List<UUID> all) {
        return all.stream().map(a -> invoicing.invoicesFor(a)).filter(i -> i.size() == 2)
                .map(i -> i.get(1).paymentReference()).toList();
    }

    int requests(List<String> references) {
        return references.stream().mapToInt(FakeProvider::requestCount).sum();
    }

    @Test
    void anOutageMidRunOpensTheBreakerAndEverythingIsChargedAfterRecovery() {
        // A normal first month.
        List<UUID> all = new ArrayList<>();
        for (int i = 0; i < ACCOUNTS; i++) {
            UUID account = accounts.open(Currency.getInstance("USD"), ZoneId.of("UTC")).id();
            accounts.recordPaymentToken(account, FakeProvider.token());
            subscriptions.subscribe(account, "BASIC");
            all.add(account);
        }
        await().atMost(Duration.ofSeconds(30)).until(() -> all.stream().allMatch(a ->
                invoicing.invoicesFor(a).stream().allMatch(i -> i.status() == InvoiceStatus.PAID)));
        Quiesce.untilIdle(jdbc, clock, all, Duration.ofSeconds(20));

        // The provider goes down; a month later the renewals run.
        FakeProvider.mode("OUTAGE");
        clock.set(clock.instant().atZone(ZoneOffset.UTC).plusMonths(1).toInstant());

        // Invoices are still created.
        await().atMost(Duration.ofSeconds(30)).until(() -> renewalReferences(all).size() == ACCOUNTS);
        assertThat(all).allSatisfy(a -> assertThat(invoicing.invoicesFor(a).get(1).status())
                .isEqualTo(InvoiceStatus.PAYMENT_PENDING));

        // The breaker opens.
        await().atMost(Duration.ofSeconds(30)).until(() -> breakerState() == 1);

        // Checkout during the outage answers PROCESSING after its 3 s wait.
        UUID newcomer = accounts.open(Currency.getInstance("USD"), ZoneId.of("UTC")).id();
        ResponseEntity<Map<String, Object>> checkout = tally.post().uri("/v1/subscriptions")
                .body(Map.of("accountId", newcomer, "planId", "BASIC", "paymentToken", FakeProvider.token()))
                .retrieve().toEntity(new ParameterizedTypeReference<>() { });
        assertThat(checkout.getStatusCode().value()).isEqualTo(202);
        assertThat(checkout.getBody()).containsEntry("status", "PROCESSING");

        // Nothing is claimed while the breaker is open: no request reaches the provider.
        List<String> references = new ArrayList<>(renewalReferences(all));
        references.add(invoicing.invoicesFor(newcomer).getFirst().paymentReference());
        int requestsWhileOpen = requests(references);
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5))
                .until(() -> requests(references) == requestsWhileOpen && breakerState() == 1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment.collection_queue WHERE status = 'IN_PROCESSING'",
                Integer.class)).isZero();
        assertThat(FakeProvider.chargeCount(references.getLast())).isZero();

        // The provider recovers. Time passes: the breaker's 30 s wait, then trial calls, then all the rest.
        FakeProvider.mode("NORMAL");
        List<UUID> everyone = new ArrayList<>(all);
        everyone.add(newcomer);
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(300)).until(() -> {
            clock.advance(Duration.ofSeconds(2));
            return everyone.stream().allMatch(a -> invoicing.invoicesFor(a).stream()
                    .allMatch(i -> i.status() == InvoiceStatus.PAID));
        });

        assertThat(breakerState()).isZero();
        for (UUID account : everyone) {
            for (InvoiceView invoice : invoicing.invoicesFor(account)) {
                assertThat(FakeProvider.chargeCount(invoice.paymentReference())).isEqualTo(1);
            }
        }
        System.out.printf("AT-4: %d provider requests for %d renewals and 1 checkout during the outage%n",
                requestsWhileOpen, ACCOUNTS);
    }
}
