# Runbook: provider outage

Tally calls the payment provider through a circuit breaker (ADR-013). After repeated technical failures the breaker opens: workers stop claiming collection items, and calls fail fast. After `open-for` (30 seconds) it lets a few calls through (half-open); success closes it, failure opens it again.

## Who

The billing team, in working hours (a ticket). If money queues stall, `BillingStalled` pages too.

## When

**`ProviderBreakerOpen`** (ticket, ADR-013): the breaker has not been closed for 10 minutes. It alternates between open (1) and half-open (2) while the provider stays down.

## See what is wrong

- The provider's status page.
- Log lines: `Attempt <id> for <reference>: technical failure (<why>); retried as a new attempt`.
- `tally_queue_waiting{queue="payment-collection"}`: the collection backlog that waits for the provider.

## What to do, by cause

### (a) The provider has an outage
Nothing to do in Tally. Collection resumes when the breaker closes; technical failures were retried as new attempts and never started Collections (ADR-013). Record the outage window in the incident ticket.

### (b) Only Tally fails (the provider's status is fine)
Credentials, network or TLS. Check one call from an instance to the provider's endpoint. Fix the configuration; the breaker closes on the next successful half-open call.

## Never

- **Never force the breaker closed.** While the provider fails, every call would fail too, and fail slowly.
- Never mark technical failures as declines: only a decline starts Collections (rule 9).
