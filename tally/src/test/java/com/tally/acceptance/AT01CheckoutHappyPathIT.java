package com.tally.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.invoicing.InvoiceStatus;
import com.tally.invoicing.InvoicingApi;
import com.tally.support.AbstractProviderIntegrationTest;
import com.tally.support.FakeProvider;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;

/**
 * AT-1. Checkout, happy path (D3, FR-019). Pass when: invoice PAID, access granted, and the
 * response shows SUCCEEDED within 3 s.
 *
 * <p>Everything goes through the HTTP API, against the fake provider in its own container, with
 * pollers running on their production settings (1 s poll interval, 3 s checkout wait).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "tally.platform.polling-enabled=true")
class AT01CheckoutHappyPathIT extends AbstractProviderIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    InvoicingApi invoicing;

    RestClient tally;

    @BeforeEach
    void client() {
        tally = RestClient.create("http://localhost:" + port);
    }

    Map<String, Object> post(String path, Map<String, Object> body) {
        return tally.post().uri(path).body(body).retrieve().body(new ParameterizedTypeReference<>() { });
    }

    Map<String, Object> get(String path, Object... variables) {
        return tally.get().uri(path, variables).retrieve().body(new ParameterizedTypeReference<>() { });
    }

    @Test
    void checkoutSucceedsWithinThreeSecondsThenTheInvoiceIsPaidAndAccessGranted() {
        // The customer's browser gets a token from the provider's hosted card form (FR-015).
        String token = FakeProvider.token();
        UUID account = UUID.fromString((String) post("/v1/accounts",
                Map.of("currency", "AED", "timeZone", "Asia/Dubai")).get("id"));

        long started = System.nanoTime();
        ResponseEntity<Map<String, Object>> response = tally.post().uri("/v1/subscriptions")
                .body(Map.of("accountId", account, "planId", "STANDARD", "paymentToken", token))
                .retrieve().toEntity(new ParameterizedTypeReference<>() { });
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        // Response shows SUCCEEDED within 3 s.
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).containsEntry("status", "SUCCEEDED");
        assertThat(elapsed).isLessThan(Duration.ofSeconds(3));
        String reference = (String) response.getBody().get("paymentReference");

        // The status endpoint reads the same truth from Payment.
        assertThat(get("/v1/payments/{reference}/status", reference)).containsEntry("state", "SUCCEEDED");

        // Invoice PAID.
        await().atMost(Duration.ofSeconds(5)).until(() -> invoicing.invoicesFor(account).stream()
                .anyMatch(i -> i.status() == InvoiceStatus.PAID && i.paymentReference().equals(reference)));

        // Access granted.
        await().atMost(Duration.ofSeconds(5))
                .until(() -> Boolean.TRUE.equals(get("/v1/accounts/{id}/access", account).get("hasAccess")));

        // Charged exactly once.
        assertThat(FakeProvider.chargeCount(reference)).isEqualTo(1);

        System.out.printf("AT-1: checkout answered SUCCEEDED in %d ms%n", elapsed.toMillis());
    }
}
