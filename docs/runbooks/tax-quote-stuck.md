# Runbook: tax quote stuck

An invoice whose tax cannot be quoted waits in `DRAFT` on the `tax-quote` money queue and is retried every few seconds (`tally.tax.retry-delay`, ADR-050). Nothing is invoiced, charged or sent to Payment for it until tax answers. Because every retry moves the item's due time forward, `BillingBehind` and `BillingStalled` do not see it. `TaxQuoteStuck` does (ADR-052).

## Who

The billing team's on-call engineer (Design Record §11).

## When

**`TaxQuoteStuck`** (page, D6): the oldest `tax-quote` item that has been tried at least once is over 1 hour old and not finished. D6 allows four hours to issue an invoice.

## See what is wrong

```sql
-- Draft invoices waiting for tax, oldest first.
SELECT q.queue_key AS invoice_id, q.status, q.attempts, q.created_at, q.due_at, q.last_error
FROM invoicing.tax_quote_queue q
WHERE q.status IN ('READY', 'IN_PROCESSING', 'FAILED')
ORDER BY q.created_at;

-- The invoices themselves: unissued, with no issue date.
SELECT id, account_id, status, amount_minor, currency, billed_for
FROM invoicing.invoice
WHERE status = 'DRAFT' AND issued_at IS NULL
ORDER BY billed_for;
```

In Prometheus:
- `tally_queue_oldest_retrying_age_seconds{queue="tax-quote"}`: the alert's age;
- `tally_queue_waiting{queue="tax-quote"}`: items due now;
- `tally_queue_failed{queue="tax-quote"}`: items that failed for another reason (`MoneyQueueItemsFailed` pages for these).

Log lines: `Tax for invoice <id> is unavailable` (first attempt) and the worker's retry lines.

## What to do, by cause

### (a) The tax service has an outage
Nothing to do in Tally. Invoices are issued, with tax, when it answers: each draft goes to tax quoted, then to payment pending (or paid), and is invoiced once (ADR-050). Record the outage window in the incident ticket and tell finance how many invoices waited (the first query).

### (b) Only Tally cannot reach it
Check `tally.tax.base-url`, the timeouts (`connect-timeout`, `read-timeout`) and whether `tally.tax.unavailable` was left set. If it was, unset it and restart; no call is made while it is on. Fix the address, credentials or network, and the queue drains on its own.

### (c) One country has no rate
An unknown jurisdiction counts as unavailable. Check the account's country against the entries under `tally.tax.rates` (or the service's list), and add the missing rate. Do not change the account's country to get past this.

## Never

- **Never issue a draft by editing its row** (status or `issued_at`): an issued invoice is never edited (I1), and one issued without tax under-bills the household and creates a VAT liability (ADR-050).
- Never set a zero rate to clear the queue.
- Never delete queue rows: the draft would never be issued and nothing would show it.
