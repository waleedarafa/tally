# Runbook: unknown payment

A payment attempt is UNKNOWN when Tally does not know whether the provider charged: the call timed out, or a sent attempt went stale (ADR-018). Reconciliation resolves it by itself, by looking the reference up and then, inside the key window, repeating the charge with the same key (ADR-026). D2 expects every unknown outcome resolved within 1 hour.

## Who

The billing team's on-call engineer (Design Record §11).

## When

**`PaymentUnknownTooLong`** (page, D2): the oldest UNKNOWN attempt was sent over an hour ago (`tally_payment_unknown_oldest_age_seconds`).

## See what is wrong

```sql
SELECT id, reference, number, sent_at, reconcile_at, reconcile_checks, idempotency_key
FROM payment.payment_attempt
WHERE state = 'UNKNOWN'
ORDER BY sent_at;
```

In Prometheus: `tally_job_last_success_age_seconds{exported_job="payment-reconciliation"}` and `tally_payment_breaker_state`.

Log lines: `Reconciliation of <reference> could not reach the provider`, `Repeat of attempt <id> for <reference> by key got no answer; checked again later`.

## What to do, by cause

### (a) Reconciliation is not running
`payment-reconciliation` is overdue: follow `job-overdue.md`. Attempts resolve once it runs.

### (b) The provider does not answer lookups
The breaker is open, or the log shows lookups failing. Follow `provider-outage.md`. Reconciliation resumes when the provider answers.

### (c) The provider answers, but cannot say
The lookup finds nothing and the repeat gets no answer. Reconciliation keeps trying until the key window ends (20 hours); after that `PaymentUnknownPastKeyWindow` pages, and `unknown-payment-past-key-window.md` applies. Before then, check the provider's dashboard for the reference and record what it shows in the incident ticket.

## Never

- **Never retry an UNKNOWN attempt as a new attempt.** The first may have charged (rule 9).
- **Never change an attempt's state by hand while reconciliation is running for it.**
