# Runbook: unknown payment past the key window

A payment attempt is UNKNOWN when Tally does not know whether the provider charged. Reconciliation resolves it automatically (ADR-026):
- **(a) lookup:** it looks the reference up;
- **(b) repeat:** while the attempt's key is younger than `tally.payment.provider.key-repeat-window` (20 hours), it repeats the charge with the same key;
- **(c) never repeat:** past that window, the provider may have forgotten the key, and a repeat would charge a second time. So Tally stops, and the attempt waits for a person.

## Alert

`tally.payment.unknown_past_key_window` > 0. The log line `Attempt <id> for <reference> is UNKNOWN past the key window` names each attempt.

## Why it matters

No new attempt is allowed while one is UNKNOWN (P2). So the invoice stays unpaid, and Collections never starts: nothing else will move this payment.

## Who

The billing team's on-call engineer (Design Record §11).

## What to do

1. Find the attempt:

   ```sql
   SELECT id, reference, number, idempotency_key, amount_minor, currency, sent_at
   FROM payment.payment_attempt
   WHERE state = 'UNKNOWN'
   ORDER BY sent_at;
   ```

2. In the provider's dashboard, look up the reference, and the idempotency key if the dashboard shows it.
3. Act on what the dashboard shows:
   - **Charged:** record the attempt as SUCCEEDED with the provider's charge ID.
   - **Not charged:** record the decision to treat it as not charged, so a new attempt can follow.

   **Gap:** Slice 1R has no command for either decision. Until one exists, ask the billing team to make the change in the database by hand. This is a known gap in the Slice 1R report.
4. In both cases, record in the incident ticket:
   - who decided, and why;
   - what the dashboard showed;
   - the attempt ID and the reference.

## Never

- **Never repeat the old key.** Past the window the provider may have forgotten it, and a repeat could charge a second time.
- **Never delete the attempt.** It is the record that a charge may have been made.

## Settlement reconciliation

Since proposed 021 was built, Tally also reads the provider's settlement batches every hour. If a batch shows a charge for the reference with the attempt's amount, the attempt becomes SUCCEEDED by itself, and this alert clears. If no batch shows a charge three days after the attempt was sent, `SettlementMismatch` raises an `UNKNOWN_NOT_SETTLED` mismatch: probably not charged, but you decide, with step 3 above. See [settlement-mismatch.md](settlement-mismatch.md).
