package com.tally.invoicing.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Golden values for the document IDs of ADR-054: the strings are stored data and must never change. */
class DocumentIdentityTest {

    UUID p = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    void adjustmentSequenceOne() {
        assertThat(Invoice.idOfAdjustment(p, 1))
                .isEqualTo(UUID.fromString("ed44aa56-9157-30de-bc61-a64cb11f1da8"));
    }

    @Test
    void adjustmentSequenceTwo() {
        assertThat(Invoice.idOfAdjustment(p, 2))
                .isEqualTo(UUID.fromString("0034f080-93c8-3e5e-8d3d-a82b09cebb6a"));
    }

    @Test
    void upgradeCreditNoteSequenceOne() {
        assertThat(CreditNote.idOfUpgradeCredit(p, 1))
                .isEqualTo(UUID.fromString("a70ee233-711d-3b89-8f74-306d065df0e3"));
    }
}
