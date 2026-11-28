package com.tally.invoicing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class TaxPropertiesTest {

    private static TaxProperties bind(Map<String, Object> source) {
        return new Binder(new MapConfigurationPropertySource(source)).bind("tally.tax", TaxProperties.class).get();
    }

    private static String rootCauseMessage(Map<String, Object> source) {
        Throwable e = catchThrowable(() -> bind(source));
        assertThat(e).isInstanceOf(BindException.class);
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage();
    }

    @Test
    void aMisspeltRatePercentStopsStartup() {
        Map<String, Object> source = new HashMap<>();
        source.put("tally.tax.rates.AE.rate-percnt", "5");
        source.put("tally.tax.rates.AE.tax-code", "VAT-AE");
        source.put("tally.tax.rates.AE.status", "ACTIVE");

        assertThat(rootCauseMessage(source)).contains("tally.tax.rates.AE.rate-percent is required for an ACTIVE rate");
    }

    @Test
    void aMissingStatusStopsStartup() {
        Map<String, Object> source = new HashMap<>();
        source.put("tally.tax.rates.AE.rate-percent", "5");
        source.put("tally.tax.rates.AE.tax-code", "VAT-AE");

        assertThat(rootCauseMessage(source)).contains("tally.tax.rates.AE.status is required");
    }

    @Test
    void anExemptRateNeedsNoPercent() {
        Map<String, Object> source = new HashMap<>();
        source.put("tally.tax.rates.US.tax-code", "EXEMPT-US");
        source.put("tally.tax.rates.US.status", "EXEMPT");

        TaxProperties props = bind(source);

        assertThat(props.rates().get("US").ratePercent()).isNull();
        assertThat(props.rates().get("US").status()).isEqualTo("EXEMPT");
    }

    @Test
    void theShippedSettingsBind() {
        Map<String, Object> source = new HashMap<>();
        source.put("tally.tax.rates.AE.rate-percent", "5");
        source.put("tally.tax.rates.AE.tax-code", "VAT-AE");
        source.put("tally.tax.rates.AE.status", "ACTIVE");
        source.put("tally.tax.rates.SA.rate-percent", "15");
        source.put("tally.tax.rates.SA.tax-code", "VAT-SA");
        source.put("tally.tax.rates.SA.status", "ACTIVE");
        source.put("tally.tax.rates.US.rate-percent", "0");
        source.put("tally.tax.rates.US.tax-code", "EXEMPT-US");
        source.put("tally.tax.rates.US.status", "EXEMPT");

        TaxProperties props = bind(source);

        assertThat(props.rates().get("AE").ratePercent().compareTo(new BigDecimal("5"))).isZero();
    }
}
