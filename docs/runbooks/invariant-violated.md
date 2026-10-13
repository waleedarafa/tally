# Runbook: invariant violated

Each module checks its own business invariants daily (FR-024). A violation means data that must never exist does: a duplicate charge, a duplicate invoice, a negative invoice, an UNKNOWN payment older than a day, or invoiced and collected amounts that disagree.

## Who

The billing team's on-call engineer (Design Record §11). A duplicate charge also goes to the finance team.

## When

**`InvariantViolated`** (page, D1, D2): `tally_invariant_violations` > 0. The `check` label names the check.

## See what is wrong, by check

### `payment.duplicate-successful-charges`
```sql
SELECT reference, count(*), array_agg(id), array_agg(charge_id)
FROM payment.payment_attempt WHERE state = 'SUCCEEDED'
GROUP BY reference HAVING count(*) > 1;
```

### `payment.unknown-over-24h`
Follow `unknown-payment.md`, then `unknown-payment-past-key-window.md`.

### `invoicing.duplicate-invoices`
```sql
SELECT a.account_id, a.id, b.id, a.period_start, a.period_end, b.period_start, b.period_end
FROM invoicing.invoice a JOIN invoicing.invoice b
  ON a.account_id = b.account_id AND a.id < b.id
 AND a.period_start < b.period_end AND b.period_start < a.period_end;
```

### `invoicing.negative-invoices`
```sql
SELECT id, account_id, amount_minor, currency FROM invoicing.invoice WHERE amount_minor < 0;
```

### `invoicing.invoiced-versus-collected`
The log line `Invoiced versus collected: invoice <id> is <status> for <amount>, but Payment collected <amount> for <reference>` names each one.

## What to do

1. Stop the cause first: find the change or incident that produced the data (deploy times, other alerts).
2. A duplicate charge: refund the extra charge at the provider, record the refund in the incident ticket, and tell finance.
3. Correct the data only with a reviewed change, recorded in the incident ticket. The check counts again on its next daily run.

**Gap:** Tally has no command to refund a charge or to void an invoice. Until one exists, a refund is made in the provider's dashboard and the invoice data is corrected by a reviewed database change.

## Never

- Never delete an invoice or a payment attempt: they are the records an auditor follows (D7).
