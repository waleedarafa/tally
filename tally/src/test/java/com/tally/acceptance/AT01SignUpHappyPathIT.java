package com.tally.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.invoicing.InvoiceStatus;
import com.tally.invoicing.InvoicingApi;
import com.tally.support.AbstractProviderIntegrationTest;
import com.tally.support.AccessService;
import com.tally.support.AccessService.Instance;
import com.tally.support.FakeProvider;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;

/**
 * AT-1. Sign-up, happy path, through {@code POST /v1/subscriptions} (ADR-014, ADR-019; changed in Slice 1R
 * PR-9, which removed checkout, and in Slice 2 PR-7). Pass when: the response promises access within 5 seconds, the
 * invoice is paid, and the <b>Access service</b> answers {@code canWatch: true} within 5 seconds. The old endpoint,
 * {@code GET /v1/accounts/{id}/access}, answers the same during this slice.
 *
 * <p>Everything goes through the HTTP API, against the fake provider in its own container, with pollers
 * running on their production settings (1 s poll interval). Access is a container reading this context's topic.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "tally.platform.polling-enabled=true")
class AT01SignUpHappyPathIT extends AbstractProviderIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    InvoicingApi invoicing;

    @Value("${tally.accessfeed.log.topic}")
    String topic;

    RestClient tally;
    AccessService access;
    Instance accessService;

    @BeforeEach
    void client() {
        tally = RestClient.create("http://localhost:" + port);
        access = AccessService.on(topic);
        accessService = access.start("at01");
    }

    @AfterEach
    void stopAccess() {
        if (access != null) {
            access.close();
        }
    }

    Map<String, Object> get(String path, Object... variables) {
        return tally.get().uri(path, variables).retrieve().body(new ParameterizedTypeReference<>() { });
    }

    @Test
    void signUpPromisesAccessWithinFiveSecondsThenTheInvoiceIsPaidAndAccessGranted() {
        // The customer's browser gets a token from the provider's hosted card form (FR-015).
        String token = FakeProvider.token();
        UUID account = UUID.fromString((String) tally.post().uri("/v1/accounts")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .body(Map.of("currency", "AED", "timeZone", "Asia/Dubai"))
                .retrieve().body(new ParameterizedTypeReference<Map<String, Object>>() { }).get("id"));

        long started = System.nanoTime();
        ResponseEntity<Map<String, Object>> response = tally.post().uri("/v1/subscriptions")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .body(Map.of("accountId", account, "planId", "STANDARD", "paymentToken", token))
                .retrieve().toEntity(new ParameterizedTypeReference<>() { });

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).containsEntry("accessWithinSeconds", 5).containsEntry("status", "ACTIVE")
                .containsKey("subscriptionId");

        // The Access service answers yes within 5 seconds of the request (ADR-014).
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50))
                .until(() -> accessService.status(account) == 200
                        && Boolean.TRUE.equals(accessService.answer(account).get("canWatch")));
        Duration toAccess = Duration.ofNanos(System.nanoTime() - started);
        assertThat(accessService.answer(account)).containsEntry("reason", "ACTIVE_SOURCE");
        // The old endpoint answers the same during this slice.
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50))
                .until(() -> Boolean.TRUE.equals(get("/v1/accounts/{id}/access", account).get("hasAccess")));
        Duration toOldEndpoint = Duration.ofNanos(System.nanoTime() - started);

        // Invoice PAID, charged exactly once.
        await().atMost(Duration.ofSeconds(10)).until(() -> invoicing.invoicesFor(account).stream()
                .anyMatch(i -> i.status() == InvoiceStatus.PAID));
        String reference = invoicing.invoicesFor(account).getFirst().paymentReference();
        assertThat(get("/v1/payments/{reference}/status", reference)).containsEntry("state", "SUCCEEDED");
        assertThat(FakeProvider.chargeCount(reference)).isEqualTo(1);

        System.out.printf("AT-1: sign-up answered; the Access service said yes %d ms after the request (the old"
                + " endpoint by %d ms)%n", toAccess.toMillis(), toOldEndpoint.toMillis());
    }
}
