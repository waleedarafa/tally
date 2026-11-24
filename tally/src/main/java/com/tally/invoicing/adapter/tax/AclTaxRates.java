package com.tally.invoicing.adapter.tax;

import com.tally.invoicing.application.TaxProperties;
import com.tally.invoicing.domain.TaxQuote;
import com.tally.invoicing.domain.TaxRates;
import com.tally.invoicing.domain.TaxRequest;
import com.tally.invoicing.domain.TaxUnavailableException;
import com.tally.shared.Ratio;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.http.HttpClient;
import java.util.Locale;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Anticorruption layer to the external tax service (ADR-050): translates the service's wire model
 * into {@link TaxQuote} or throws {@link TaxUnavailableException}. The domain never sees the wire.
 * With no base URL configured, quoting is settings-backed: it reads the jurisdiction's fallback rate
 * from {@link TaxProperties}.
 */
@Component
class AclTaxRates implements TaxRates {

    /** The tax service's wire model, translated into {@link TaxQuote} or {@link TaxUnavailableException}. */
    record ExternalTaxResponse(String jurisdiction, BigDecimal ratePercent, String taxCode, String status) {
    }

    private final TaxProperties properties;
    private final RestClient http;

    @Autowired
    AclTaxRates(TaxProperties properties) {
        this.properties = properties;
        if (properties.baseUrl() != null && !properties.baseUrl().isBlank()) {
            JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                    HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build());
            requests.setReadTimeout(properties.readTimeout());
            this.http = RestClient.builder().baseUrl(properties.baseUrl()).requestFactory(requests).build();
        } else {
            this.http = null;
        }
    }

    AclTaxRates(TaxProperties properties, RestClient http) {
        this.properties = properties;
        this.http = http;
    }

    @Override
    public TaxQuote quote(TaxRequest request) {
        Objects.requireNonNull(request, "request");
        if (properties.unavailable()) {
            throw new TaxUnavailableException("Tax service is unavailable for " + request.country());
        }
        if (http != null) {
            ExternalTaxResponse response;
            try {
                response = http.get().uri("/tax/rates?jurisdiction={country}&currency={currency}&amount={amount}&date={date}",
                        request.country().toUpperCase(Locale.ROOT), request.currency().getCurrencyCode(),
                        request.netAmount().minorUnits(), request.taxPointDate())
                        .retrieve()
                        .body(ExternalTaxResponse.class);
            } catch (HttpStatusCodeException | ResourceAccessException e) {
                throw new TaxUnavailableException("External tax service unavailable for " + request.country(), e);
            }
            if (response == null) {
                throw new TaxUnavailableException("Empty tax response for " + request.country());
            }
            return translate(response);
        }
        TaxProperties.Rate configured = properties.rates().get(request.country().toUpperCase(Locale.ROOT));
        if (configured == null) {
            throw new TaxUnavailableException("No tax rate configured for jurisdiction " + request.country());
        }
        ExternalTaxResponse wire = new ExternalTaxResponse(request.country().toUpperCase(Locale.ROOT),
                configured.ratePercent(), configured.taxCode(), configured.status());
        return translate(wire);
    }

    TaxQuote translate(ExternalTaxResponse wire) {
        if (wire == null || wire.status() == null) {
            throw new TaxUnavailableException("Missing tax status");
        }
        switch (wire.status().toUpperCase(Locale.ROOT)) {
            case "UNAVAILABLE" -> throw new TaxUnavailableException("Tax rate unavailable for " + wire.jurisdiction());
            case "EXEMPT" -> {
                return TaxQuote.zero();
            }
            case "ACTIVE" -> {
                if (wire.ratePercent() == null || wire.ratePercent().compareTo(BigDecimal.ZERO) < 0) {
                    throw new IllegalStateException("Tax rate cannot be negative: " + wire.ratePercent());
                }
                if (wire.ratePercent().compareTo(BigDecimal.ZERO) == 0) {
                    return TaxQuote.zero();
                }
                BigDecimal scaled = wire.ratePercent().stripTrailingZeros();
                int decimalPlaces = Math.max(0, scaled.scale());
                long numerator = scaled.movePointRight(decimalPlaces).longValueExact();
                long denominator = 100L * BigInteger.TEN.pow(decimalPlaces).longValueExact();
                return new TaxQuote(Ratio.of(numerator, denominator), "VAT (" + scaled.toPlainString() + "%)");
            }
            default -> throw new TaxUnavailableException("Unknown tax service status: " + wire.status());
        }
    }
}
