# ADR-054. Adjustment invoice identity: a name-based UUID of the period invoice and a sequence

**Status:** Accepted (confirmed by Waleed Arafa, 2026-11-28), 27 November 2026 (closes ADR-040 open question 1).

## Context

ADR-040 amended I2: there is still at most one *period invoice* per subscription per period, whose ID is `UUID.nameUUIDFromBytes("invoice:" + subscriptionId + ":" + periodNumber)`; an adjustment invoice is a different document with its own ID, tied to the period and to the period invoice. ADR-040 left open question 1: "the exact ID scheme ... and the column that ties it to the period invoice. The concrete schema is WAR-62's."

WAR-62 built a scheme and said in code and in the topic tracker (D-09) that question 1 was "formally still open". What the code does today:

- `Invoice.idOfAdjustment(periodInvoiceId, sequence)` = `UUID.nameUUIDFromBytes("adjustment-invoice:" + periodInvoiceId + ":" + sequence)`, sequence numbered from 1 per period invoice.
- `CreditNote.idOfUpgradeCredit(periodInvoiceId, adjustmentSequence)` = `UUID.nameUUIDFromBytes("credit-note:upgrade:" + periodInvoiceId + ":" + adjustmentSequence)`.
- `UpgradeService` takes the next sequence as `adjustmentsOf(periodInvoice).size() + 1` when the upgrade is requested, and the payment reference is `"INV-" + idOfAdjustment(...)` (stored on `upgrade_intent`, V47). Completion finds the sequence again from the reference (`sequenceOf`, a bounded search).
- V46 added `invoice_type` (`PERIOD` or `ADJUSTMENT`), `period_invoice_id` (a foreign key to the period invoice, required for an adjustment, null for a period invoice), and replaced the blanket unique constraint by `invoice_one_period_per_subscription_and_period` (period invoices only). The daily `invoicing.duplicate-invoices` check compares period invoices only.
- ADR-044 allows several upgrades in one period; ADR-042 refuses an upgrade while the period invoice is unpaid; `upgrade_intent` allows one open intent per period invoice.

## Options

- **A. Keep the scheme the code uses** (period invoice ID + sequence, name-based).
- **B. Name-based on the upgrade intent's own key** (for example the payment reference or an idempotency key). The intent is created from the ID, so this is circular today, and a document's identity would depend on a request key from outside Invoicing.
- **C. Random UUID stored on the intent.** Simple, but a retry before the intent is saved would mint a second ID, and D1's "the same inputs always spell the same document" would rest on the intent row alone.
- **D. Per-account human invoice numbers (INV-2026-000123).** A legal-numbering question (H8, finance), not an identity question; nothing in the design asks for it yet.

## Decision

We take option A, as built. An adjustment invoice's ID is `UUID.nameUUIDFromBytes("adjustment-invoice:" + periodInvoiceId + ":" + sequence)`, with sequence counted from 1 per period invoice; the upgrade's credit note is `UUID.nameUUIDFromBytes("credit-note:upgrade:" + periodInvoiceId + ":" + sequence)` with the same sequence. The tie to the period invoice is `invoicing.invoice.period_invoice_id` with `invoice_type = 'ADJUSTMENT'` (V46). The payment reference stays `"INV-" + id`, like a period invoice's.

Rules that follow:

1. The prefixes `invoice:`, `adjustment-invoice:` and `credit-note:upgrade:` keep the three kinds of document from ever sharing an ID. A future kind of adjustment (for example a downgrade refund, if one is ever decided) takes a new prefix; it does not reuse these.
2. The sequence is taken while no other upgrade of that period invoice is open (one open intent per period invoice, V47), so two requests cannot take the same number. The ID is the primary key, so a second insert of the same sequence fails rather than creating a second document (D1).
3. The strings are part of stored data: every issued adjustment, credit note and payment reference depends on them. They are pinned by golden-value unit tests and must never change. Changing them needs a new ADR and a migration plan for existing rows.
4. No new column or migration is needed: V46 already holds the tie.

Golden values (Java `UUID.nameUUIDFromBytes` over UTF-8, checked independently with MD5 version 3), for period invoice `11111111-1111-1111-1111-111111111111`:

| Input | ID |
|---|---|
| adjustment, sequence 1 | `ed44aa56-9157-30de-bc61-a64cb11f1da8` |
| adjustment, sequence 2 | `0034f080-93c8-3e5e-8d3d-a82b09cebb6a` |
| upgrade credit note, sequence 1 | `a70ee233-711d-3b89-8f74-306d065df0e3` |

## Consequences

- ADR-040 open question 1 is closed. ADR-040's text is not edited; the topic tracker records the resolution (D-09).
- The code comment that called question 1 "still open" is updated to point here; behaviour does not change.
- Human-readable, gap-free legal invoice numbers remain undecided (option D). If finance asks for them they are a separate number on the document, not its ID.

## Rejected

- **B:** circular with the intent's creation, and puts an outside key into a legal document's identity.
- **C:** loses determinism before the intent is stored; D1 is cheaper to keep with a name-based ID.
- **D:** answers a different question; recorded above as open.

## Drivers

ADR-040 (I2 amendment, open question 1); ADR-041; ADR-042; ADR-044; ADR-003 and D1 (same inputs, same document); D7; V46, V47.

## Revisit when

Finance asks for legal invoice numbers, or a second kind of mid-period adjustment (other than an upgrade) is decided.
