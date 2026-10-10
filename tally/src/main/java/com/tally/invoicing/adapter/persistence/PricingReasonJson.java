package com.tally.invoicing.adapter.persistence;

import com.tally.invoicing.PricingReason;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Stores a {@link PricingReason} as JSON in {@code invoicing.invoice.quote_reason} (jsonb). Applies to every
 * PricingReason attribute, so the domain names no adapter (F9). Inserts cast the text to jsonb
 * ({@link JpaInvoices}); reads take the column as text.
 */
@Converter(autoApply = true)
public class PricingReasonJson implements AttributeConverter<PricingReason, String> {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Override
    public String convertToDatabaseColumn(PricingReason reason) {
        return reason == null ? null : JSON.writeValueAsString(reason);
    }

    @Override
    public PricingReason convertToEntityAttribute(String json) {
        return json == null ? null : JSON.readValue(json, PricingReason.class);
    }
}
