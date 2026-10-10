package com.tally.invoicing.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.invoicing.PricingReason;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PricingReasonJsonTest {

    final PricingReasonJson json = new PricingReasonJson();

    @Test
    void aReasonSurvivesTheRoundTripWithItsFlags() {
        PricingReason reason = new PricingReason("BASIC", 2, "free-trial", Map.of("new-checkout", true, "eu-vat", false));

        String stored = json.convertToDatabaseColumn(reason);

        assertThat(stored).contains("\"offerId\":\"BASIC\"").contains("\"offerVersion\":2")
                .contains("\"kind\":\"free-trial\"").contains("\"new-checkout\":true");
        assertThat(json.convertToEntityAttribute(stored)).isEqualTo(reason);
    }

    @Test
    void noReasonIsNull() {
        assertThat(json.convertToDatabaseColumn(null)).isNull();
        assertThat(json.convertToEntityAttribute(null)).isNull();
    }
}
