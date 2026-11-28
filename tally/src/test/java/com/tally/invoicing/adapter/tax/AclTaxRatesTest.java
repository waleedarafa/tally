package com.tally.invoicing.adapter.tax;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.tally.invoicing.application.TaxProperties;
import com.tally.invoicing.domain.TaxQuote;
import com.tally.invoicing.domain.TaxQuoteRefusedException;
import com.tally.invoicing.domain.TaxRequest;
import com.tally.invoicing.domain.TaxUnavailableException;
import com.tally.shared.Money;
import com.tally.shared.Ratio;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AclTaxRatesTest {

    private static final Duration ONE_SECOND = Duration.ofSeconds(1);
    private static final Duration THREE_SECONDS = Duration.ofSeconds(3);
    private static final Duration FIVE_SECONDS = Duration.ofSeconds(5);

    static TaxProperties settingsBacked(Map<String, TaxProperties.Rate> rates) {
        return new TaxProperties("", ONE_SECOND, THREE_SECONDS, FIVE_SECONDS, false, rates);
    }

    static TaxProperties unavailableSettings(Map<String, TaxProperties.Rate> rates) {
        return new TaxProperties("", ONE_SECOND, THREE_SECONDS, FIVE_SECONDS, true, rates);
    }

    static TaxRequest request(String country) {
        return new TaxRequest(country, Currency.getInstance("SAR"), Money.of(1000, "SAR"), LocalDate.parse("2026-01-15"));
    }

    @Test
    void uaeFivePercent() {
        TaxProperties props = settingsBacked(Map.of("AE", new TaxProperties.Rate(new BigDecimal("5"), "VAT-AE", "ACTIVE")));

        TaxQuote quote = new AclTaxRates(props).quote(request("AE"));

        assertThat(quote.rate()).isEqualTo(Ratio.of(5, 100));
        assertThat(quote.description()).isEqualTo("VAT (5%)");
    }

    @Test
    void saudiArabiaFifteenPercent() {
        TaxProperties props = settingsBacked(Map.of("SA", new TaxProperties.Rate(new BigDecimal("15"), "VAT-SA", "ACTIVE")));

        TaxQuote quote = new AclTaxRates(props).quote(request("SA"));

        assertThat(quote.rate()).isEqualTo(Ratio.of(15, 100));
        assertThat(quote.description()).isEqualTo("VAT (15%)");
    }

    @Test
    void exemptJurisdictionQuotesNothing() {
        TaxProperties props = settingsBacked(Map.of("US", new TaxProperties.Rate(BigDecimal.ZERO, "EXEMPT-US", "EXEMPT")));

        TaxQuote quote = new AclTaxRates(props).quote(request("US"));

        assertThat(quote).isEqualTo(TaxQuote.zero());
    }

    @Test
    void fractionalRate() {
        TaxProperties props = settingsBacked(Map.of("DE", new TaxProperties.Rate(new BigDecimal("7.5"), "VAT", "ACTIVE")));

        TaxQuote quote = new AclTaxRates(props).quote(request("DE"));

        assertThat(quote.rate()).isEqualTo(Ratio.of(75, 1000));
        assertThat(quote.description()).isEqualTo("VAT (7.5%)");
    }

    @Test
    void unavailableFlagRefusesQuoting() {
        TaxProperties props = unavailableSettings(Map.of("AE", new TaxProperties.Rate(new BigDecimal("5"), "VAT-AE", "ACTIVE")));

        assertThatThrownBy(() -> new AclTaxRates(props).quote(request("AE")))
                .isInstanceOf(TaxUnavailableException.class)
                .hasMessageContaining("unavailable for AE");
    }

    @Test
    void unavailableStatusRefusesQuoting() {
        TaxProperties props = settingsBacked(Map.of("AE", new TaxProperties.Rate(new BigDecimal("5"), "VAT-AE", "UNAVAILABLE")));

        assertThatThrownBy(() -> new AclTaxRates(props).quote(request("AE")))
                .isInstanceOf(TaxUnavailableException.class)
                .hasMessageContaining("Tax rate unavailable for AE");
    }

    @Test
    void unknownJurisdictionRefusesQuoting() {
        TaxProperties props = settingsBacked(Map.of("AE", new TaxProperties.Rate(new BigDecimal("5"), "VAT-AE", "ACTIVE")));

        assertThatThrownBy(() -> new AclTaxRates(props).quote(request("DE")))
                .isInstanceOf(TaxQuoteRefusedException.class)
                .hasMessageContaining("No tax rate configured for jurisdiction DE");
    }

    @Test
    void activeRateFromWire() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AclTaxRates adapter = new AclTaxRates(settingsBacked(Map.of()), builder.build());
        server.expect(requestTo("/tax/rates?jurisdiction=SA&currency=SAR&amount=1000&date=2026-01-15"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"jurisdiction\":\"SA\",\"ratePercent\":15,\"taxCode\":\"VAT-SA\",\"status\":\"ACTIVE\"}", MediaType.APPLICATION_JSON));

        TaxQuote quote = adapter.quote(request("SA"));

        assertThat(quote.rate()).isEqualTo(Ratio.of(15, 100));
        assertThat(quote.description()).isEqualTo("VAT (15%)");
        server.verify();
    }

    @Test
    void unavailableStatusFromWireIsRefused() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AclTaxRates adapter = new AclTaxRates(settingsBacked(Map.of()), builder.build());
        server.expect(requestTo("/tax/rates?jurisdiction=AE&currency=SAR&amount=1000&date=2026-01-15"))
                .andRespond(withSuccess("{\"jurisdiction\":\"AE\",\"ratePercent\":5,\"taxCode\":\"VAT-AE\",\"status\":\"UNAVAILABLE\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.quote(request("AE")))
                .isInstanceOf(TaxUnavailableException.class)
                .hasMessageContaining("Tax rate unavailable for AE");
        server.verify();
    }

    @Test
    void serviceUnavailableThroughTheWireIsRefused() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AclTaxRates adapter = new AclTaxRates(settingsBacked(Map.of()), builder.build());
        server.expect(requestTo("/tax/rates?jurisdiction=SA&currency=SAR&amount=1000&date=2026-01-15"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> adapter.quote(request("SA")))
                .isInstanceOf(TaxUnavailableException.class)
                .hasMessageContaining("External tax service unavailable for SA");
        server.verify();
    }

    @Test
    void aClientErrorIsRefused() {
        for (HttpStatus status : new HttpStatus[] {HttpStatus.BAD_REQUEST, HttpStatus.NOT_FOUND}) {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            AclTaxRates adapter = new AclTaxRates(settingsBacked(Map.of()), builder.build());
            server.expect(requestTo("/tax/rates?jurisdiction=AE&currency=SAR&amount=1000&date=2026-01-15"))
                    .andRespond(withStatus(status));

            assertThatThrownBy(() -> adapter.quote(request("AE")))
                    .isInstanceOf(TaxQuoteRefusedException.class)
                    .hasMessageContaining("Tax service refused the request for AE: " + status.value());
            server.verify();
        }
    }

    @Test
    void aServerErrorOrTooManyRequestsIsUnavailable() {
        for (HttpStatus status : new HttpStatus[] {HttpStatus.SERVICE_UNAVAILABLE, HttpStatus.TOO_MANY_REQUESTS}) {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            AclTaxRates adapter = new AclTaxRates(settingsBacked(Map.of()), builder.build());
            server.expect(requestTo("/tax/rates?jurisdiction=SA&currency=SAR&amount=1000&date=2026-01-15"))
                    .andRespond(withStatus(status));

            assertThatThrownBy(() -> adapter.quote(request("SA")))
                    .isInstanceOf(TaxUnavailableException.class)
                    .hasMessageContaining("External tax service unavailable for SA");
            server.verify();
        }
    }

    @Test
    void anUnreadableBodyIsRefused() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        AclTaxRates adapter = new AclTaxRates(settingsBacked(Map.of()), builder.build());
        server.expect(requestTo("/tax/rates?jurisdiction=AE&currency=SAR&amount=1000&date=2026-01-15"))
                .andRespond(withSuccess("not json", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> adapter.quote(request("AE")))
                .isInstanceOf(TaxQuoteRefusedException.class);
        server.verify();
    }

    @Test
    void anActiveRateWithNoPercentIsRefused() {
        AclTaxRates adapter = new AclTaxRates(settingsBacked(Map.of()));

        assertThatThrownBy(() -> adapter.translate(new AclTaxRates.ExternalTaxResponse("AE", null, "VAT", "ACTIVE"), "AE"))
                .isInstanceOf(TaxQuoteRefusedException.class);
    }

    @Test
    void anUnknownStatusIsRefused() {
        AclTaxRates adapter = new AclTaxRates(settingsBacked(Map.of()));

        assertThatThrownBy(() -> adapter.translate(
                new AclTaxRates.ExternalTaxResponse("AE", new BigDecimal("5"), "VAT", "PAUSED"), "AE"))
                .isInstanceOf(TaxQuoteRefusedException.class)
                .hasMessageContaining("Unknown tax service status: PAUSED");
    }

    @Test
    void anAnswerForAnotherJurisdictionIsRefused() {
        AclTaxRates adapter = new AclTaxRates(settingsBacked(Map.of()));

        assertThatThrownBy(() -> adapter.translate(
                new AclTaxRates.ExternalTaxResponse("SA", new BigDecimal("15"), "VAT", "ACTIVE"), "AE"))
                .isInstanceOf(TaxQuoteRefusedException.class)
                .hasMessageContaining("Tax answer for SA does not match the requested AE");
    }

    @Test
    void aJurisdictionWithNoConfiguredRateIsRefused() {
        TaxProperties props = settingsBacked(Map.of("AE", new TaxProperties.Rate(new BigDecimal("5"), "VAT-AE", "ACTIVE")));

        assertThatThrownBy(() -> new AclTaxRates(props).quote(request("FR")))
                .isInstanceOf(TaxQuoteRefusedException.class)
                .hasMessageContaining("No tax rate configured for jurisdiction FR");
    }
}
