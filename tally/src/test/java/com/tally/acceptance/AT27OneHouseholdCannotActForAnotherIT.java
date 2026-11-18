package com.tally.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.account.AccountView;
import com.tally.collections.CaseView;
import com.tally.invoicing.InvoiceView;
import com.tally.support.FakeProvider;
import com.tally.support.Quiesce;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

/**
 * AT-27. One household cannot act for another (Slice 4 brief; ADR-032; CLAUDE.md rule 16). Users A and B each have an
 * account and a subscription, and B has a debt open. A asks for B's account, signs B up, presses "pay now" for B, and
 * reads B's payment status. Each gets 404, as an ID that does not exist would. B's data and B's card are not touched:
 * no new attempt at the fake provider. B's own requests still answer.
 */
class AT27OneHouseholdCannotActForAnotherIT extends AbstractCollectionsAcceptanceTest {

    static int status(RestClient.RequestHeadersSpec<?> request) {
        return request.retrieve().toBodilessEntity().getStatusCode().value();
    }

    int paymentAttempts(UUID account) {
        return jdbc.queryForObject("SELECT count(*) FROM payment.payment_attempt WHERE account_id = ?", Integer.class,
                account);
    }

    @Test
    void eachRequestForAnotherHouseholdAnswers404AndChangesNothing() {
        UUID b = signUpAtTheStartOfAMonth();
        UUID a = openAccount();
        assertThat(signUp(a)).isEqualTo(201);
        await().atMost(Duration.ofSeconds(15)).until(() -> invoicing.invoicesFor(a).stream()
                .anyMatch(invoice -> invoice.paymentReference() != null));
        renewalDeclined(b);
        FakeProvider.mode("NORMAL");
        Quiesce.untilIdle(jdbc, clock, List.of(a, b), Duration.ofSeconds(30));

        RestClient asA = tally(a);
        String referenceOfB = invoicing.invoicesFor(b).stream().map(InvoiceView::paymentReference).findFirst()
                .orElseThrow();
        AccountView accountOfB = accounts.get(b).orElseThrow();
        CaseView caseOfB = theCase(b);
        int attemptsOfB = paymentAttempts(b);
        int chargesOfB = FakeProvider.chargeCount(referenceOfB);

        assertThat(status(asA.get().uri("/v1/accounts/{id}", b))).as("A reads B's account").isEqualTo(404);
        assertThat(status(asA.post().uri("/v1/subscriptions").header("Idempotency-Key", UUID.randomUUID()
                .toString()).body(Map.of("accountId", b, "planId", "PREMIUM", "paymentToken", FakeProvider.token()))))
                .as("A signs B up").isEqualTo(404);
        assertThat(status(asA.post().uri("/v1/accounts/{id}/collections/payment-requests", b)
                .header("Idempotency-Key", UUID.randomUUID().toString()))).as("A presses pay now for B").isEqualTo(404);
        assertThat(status(asA.get().uri("/v1/payments/{reference}/status", referenceOfB)))
                .as("A reads B's payment status").isEqualTo(404);
        Quiesce.untilIdle(jdbc, clock, List.of(a, b), Duration.ofSeconds(30));

        assertThat(accounts.get(b)).as("B's account, its card included").contains(accountOfB);
        assertThat(theCase(b)).as("B's case").isEqualTo(caseOfB);
        assertThat(paymentAttempts(b)).as("B's payment attempts").isEqualTo(attemptsOfB);
        assertThat(FakeProvider.chargeCount(referenceOfB)).as("charges of B's card at the provider")
                .isEqualTo(chargesOfB);

        // B's own requests answer: the 404s above are about the caller, not about the objects.
        RestClient asB = tally(b);
        assertThat(asB.get().uri("/v1/accounts/{id}", b).retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() { })).containsEntry("id", b.toString());
        assertThat(status(asB.get().uri("/v1/payments/{reference}/status", referenceOfB))).isEqualTo(200);
        ResponseEntity<Map<String, Object>> paid = payNow(b);
        assertThat(paid.getStatusCode().value()).as("B presses pay now").isEqualTo(202);
    }

    /** A's own objects are answered as before; an ID that exists nowhere answers as another household's does. */
    @Test
    void anotherHouseholdsAccountAnswersAsAnAccountThatDoesNotExist() {
        UUID a = openAccount();
        UUID b = openAccount();
        RestClient asA = tally(a);

        ResponseEntity<String> others = asA.get().uri("/v1/accounts/{id}", b).retrieve().toEntity(String.class);
        ResponseEntity<String> nobodys = asA.get().uri("/v1/accounts/{id}", UUID.randomUUID()).retrieve()
                .toEntity(String.class);

        assertThat(others.getStatusCode()).isEqualTo(nobodys.getStatusCode());
        assertThat(others.getBody()).isEqualTo(nobodys.getBody());
        assertThat(status(asA.get().uri("/v1/accounts/{id}", a))).isEqualTo(200);
    }
}
