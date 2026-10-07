package com.tally.payment.adapter.provider;

import com.tally.payment.FailureReason;
import com.tally.payment.domain.PaymentGateway;
import com.tally.payment.application.PaymentProperties;
import com.tally.shared.Money;
import java.net.http.HttpClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Anticorruption layer to the provider's HTTP API (ADR-014). Connect and read timeouts follow
 * brief section 5. Any error, including a timeout, is thrown: the outcome is unknown.
 */
@Component
class HttpPaymentGateway implements PaymentGateway {

    record ChargeRequest(String token, long amount, String currency, String reference) {
    }

    record ChargeResponse(String chargeId, String reference, String outcome) {
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
    public ChargeResult charge(String paymentToken, Money amount, String reference) {
        ChargeResponse response = http.post().uri("/charges")
                .body(new ChargeRequest(paymentToken, amount.minorUnits(), amount.currency().getCurrencyCode(),
                        reference))
                .retrieve()
                .body(ChargeResponse.class);
        if (response == null || response.outcome() == null) {
            throw new IllegalStateException("Provider returned no outcome for " + reference);
        }
        return switch (response.outcome()) {
            case "SUCCEEDED" -> new Charged(response.chargeId());
            case "DECLINED_SOFT" -> new Declined(FailureReason.SOFT);
            case "DECLINED_HARD" -> new Declined(FailureReason.HARD);
            default -> throw new IllegalStateException("Unknown provider outcome " + response.outcome());
        };
    }
}
