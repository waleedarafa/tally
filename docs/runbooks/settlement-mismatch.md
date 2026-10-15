# Runbook: settlement mismatch

Every hour, Tally reads the provider's new settlement batches. A batch lists the charges whose money the provider moved. Tally compares them with its own payment attempts, in both directions (proposed 021; D14).

Some cases Tally settles by itself:
- A charge in a batch matches a SUCCEEDED attempt: nothing to do.
- A charge in a batch for the reference of an UNKNOWN attempt, with the same amount: the attempt becomes SUCCEEDED, as if the lookup had found the charge.
- A mismatch whose cause goes away, such as a charge that settles late, closes by itself.

Everything else is a mismatch, and waits for a person.

## Alert

`SettlementMismatch`: `tally.payment.settlement.mismatches{kind}` > 0. Severity: ticket. D14 asks for every mismatch to be found within 24 hours; it does not ask for a page.

## Who

The billing team's on-call engineer, with finance for anything that moves money (Design Record §11).

## What to do

1. List the open mismatches:

   ```sql
   SELECT id, kind, subject, reference, detail, found_at
   FROM payment.settlement_mismatch
   WHERE resolved_at IS NULL
   ORDER BY found_at;
   ```

   `subject` is the provider's charge ID for the first two kinds below, and Tally's attempt ID for the last two.

2. Act on the kind:

   | Kind | What it means | What to do |
   |---|---|---|
   | `CHARGED_NOT_RECORDED` | The provider moved money for a charge that no SUCCEEDED attempt holds. If the reference's latest attempt is DECLINED, the provider reported a false decline. | Find the reference in the provider's dashboard. If the latest attempt is DECLINED, the 48-hour re-check missed a false decline, and Tally has not corrected it: [provider-false-decline.md](provider-false-decline.md) says what the correction means for the customer. A reference Tally never sent is a provider error: raise it with the provider. |
   | `AMOUNT_DIFFERS` | The amount settled is not the amount Tally asked for. An UNKNOWN attempt is **not** decided by a charge of another amount. | Compare the amounts in the provider's dashboard. A partial approval or a currency conversion is not modelled (CONTRACT.md §7): raise it with the provider and finance. |
   | `RECORDED_NOT_SETTLED` | Tally recorded a success, and no batch shows the charge three days after it was sent. The customer may have access without having paid. | Look the charge up in the dashboard. If it was never made, the success was false: 022's question again. |
   | `UNKNOWN_NOT_SETTLED` | An UNKNOWN attempt, and no batch shows a charge for its reference three days after it was sent. Probably not charged. "Not found" alone never decides (026), so Tally waits for you. | Follow [unknown-payment-past-key-window.md](unknown-payment-past-key-window.md), step 3. |

3. **Gap:** there is no command yet to record a decision (tracker D-27). Until one exists, the billing team changes the attempt by hand, as that runbook says, and closes the mismatch:

   ```sql
   UPDATE payment.settlement_mismatch SET resolved_at = now() WHERE id = '<mismatch id>';
   ```

4. In every case, record in the incident ticket: who decided, and why; what the dashboard showed; the mismatch ID, the reference and the charge ID.

## Never

- **Never delete a settlement line or a batch.** The next import starts after the highest batch imported; a deleted batch is never read again.
- **Never decide an UNKNOWN attempt from the absence of a charge alone.** Settlement can be late.

## Job health

The job is `payment-settlement` in `payment.jobs`. Its waiting work is the number of open mismatches. If it falls behind, see [job-overdue.md](job-overdue.md).
