package com.tally.payment.adapter.provider;

import com.tally.payment.FailureReason;
import com.tally.payment.application.PaymentProperties;
import com.tally.payment.domain.PaymentGateway;
import com.tally.shared.Money;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Anticorruption layer to the provider's HTTP API (FR-014). Connect and read timeouts follow brief
 * section 5. It tells apart failures where the provider certainly did not charge (429, 503, request
 * never sent) from those where it may have (read timeout, any other error): see {@link PaymentGateway}.
 */
@Component
class HttpPaymentGateway implements PaymentGateway {

    private static final Duration DEFAULT_RETRY_AFTER_RATE_LIMIT = Duration.ofSeconds(1);
    private static final Duration RETRY_AFTER_UNAVAILABLE = Duration.ofSeconds(5);

    record ChargeRequest(String token, long amount, String currency, String reference) {
    }

    record ChargeResponse(String chargeId, String reference, String outcome) {
    }

    record ChargeRecord(String chargeId, String reference, String outcome) {
    }

    private final RestClient http;

    HttpPaymentGateway(PaymentProperties properties) {
        PaymentProperties.Provider provider = properties.provider();
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(provider.connectTimeout()).build());
        requests.setReadTimeout(provider.readTimeout());
        this.http = RestClient.builder().baseUrl(provider.baseUrl()).requestFactory(requests).build();
    }

    @Override
    public ChargeResult charge(String paymentToken, Money amount, String reference, String idempotencyKey) {
        ChargeResponse response;
        try {
            response = http.post().uri("/charges").header("Idempotency-Key", idempotencyKey)
                    .body(new ChargeRequest(paymentToken, amount.minorUnits(), amount.currency().getCurrencyCode(),
                            reference))
                    .retrieve()
                    .body(ChargeResponse.class);
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode().value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
                throw new NotChargedException(NotChargedException.Why.RATE_LIMITED, retryAfter(e),
                        "Provider rate limit for " + reference, e);
            }
            if (e.getStatusCode().value() == HttpStatus.SERVICE_UNAVAILABLE.value()) {
                throw new NotChargedException(NotChargedException.Why.UNAVAILABLE, RETRY_AFTER_UNAVAILABLE,
                        "Provider unavailable for " + reference, e);
            }
            throw e;
        } catch (ResourceAccessException e) {
            if (neverSent(e)) {
                throw new NotChargedException(NotChargedException.Why.UNAVAILABLE, RETRY_AFTER_UNAVAILABLE,
                        "Provider unreachable for " + reference, e);
            }
            // Includes the read timeout: the provider may have charged.
            throw e;
        }
        if (response == null || response.outcome() == null) {
            throw new IllegalStateException("Provider returned no outcome for " + reference);
        }
        return result(response.chargeId(), response.outcome());
    }

    @Override
    public List<RecordedCharge> chargesFor(String reference) {
        ChargeRecord[] records = http.get().uri("/charges?reference={reference}", reference).retrieve()
                .body(ChargeRecord[].class);
        return records == null ? List.of() : Arrays.stream(records)
                .map(r -> new RecordedCharge(r.chargeId(), result(r.chargeId(), r.outcome())))
                .toList();
    }

    private static ChargeResult result(String chargeId, String outcome) {
        return switch (outcome) {
            case "SUCCEEDED" -> new Charged(chargeId);
            case "DECLINED_SOFT" -> new Declined(FailureReason.SOFT);
            case "DECLINED_HARD" -> new Declined(FailureReason.HARD);
            default -> throw new IllegalStateException("Unknown provider outcome " + outcome);
        };
    }

    /** The connection was refused or never opened: the request did not reach the provider. */
    private static boolean neverSent(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConnectException || t instanceof HttpConnectTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static Duration retryAfter(HttpStatusCodeException e) {
        String header = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("Retry-After");
        try {
            return header == null ? DEFAULT_RETRY_AFTER_RATE_LIMIT : Duration.ofSeconds(Long.parseLong(header.strip()));
        } catch (NumberFormatException notSeconds) {
            return DEFAULT_RETRY_AFTER_RATE_LIMIT;
        }
    }
}
