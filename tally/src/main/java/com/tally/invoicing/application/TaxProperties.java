package com.tally.invoicing.application;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for the external tax service ({@code tally.tax.*}, ADR-050, FR-016).
 *
 * @param baseUrl       the tax service's address; blank leaves settings-backed quoting
 * @param connectTimeout the tax service connect timeout (brief section 5)
 * @param readTimeout    the tax service read timeout
 * @param retryDelay     how long a retrying worker waits before asking the tax service again
 * @param unavailable    set when the tax service is known to be down, so no call is made at all
 * @param rates          per-jurisdiction fallback rates used while the service is unreachable
 */
@ConfigurationProperties("tally.tax")
public record TaxProperties(
        @DefaultValue("") String baseUrl,
        @DefaultValue("1s") Duration connectTimeout,
        @DefaultValue("3s") Duration readTimeout,
        @DefaultValue("5s") Duration retryDelay,
        @DefaultValue("false") boolean unavailable,
        Map<String, Rate> rates) {

    public TaxProperties {
        rates = rates == null ? Map.of() : Map.copyOf(rates);
    }

    /**
     * One jurisdiction's fallback rate.
     *
     * @param ratePercent the percentage of the pre-tax amount the jurisdiction charges
     * @param taxCode     the tax code the tax service reports for the jurisdiction
     * @param status      the jurisdiction's status (ACTIVE, EXEMPT, UNAVAILABLE)
     */
    public record Rate(
            @DefaultValue("0") BigDecimal ratePercent,
            @DefaultValue("VAT") String taxCode,
            @DefaultValue("ACTIVE") String status) {
    }
}
