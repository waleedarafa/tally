# Runbook: provider false decline

Before Collections retries a declined reference, Payment looks the reference up (ADR-026). If the provider shows a charge, no new attempt is made, and the declined attempt is corrected to SUCCEEDED. The customer was charged; the provider had reported a decline.

## Who

The billing team, in working hours (a ticket).

## When

**`ProviderFalseDecline`** (ticket, D2): `tally_payment_outcome_corrected_total{from="DECLINED", to="SUCCEEDED"}` increased in the last day.

## See what is wrong

The log line `Corrected <reference> from DECLINED to SUCCEEDED` names each one.

For each reference in those lines:

```sql
SELECT id, number, state, charge_id, created_at, updated_at
FROM payment.payment_attempt
WHERE reference = '<reference>'
ORDER BY number;
```

The corrected attempt is SUCCEEDED with the provider's `charge_id`. No later attempt was made for the reference.

## What to do

1. For each reference, check the provider's dashboard: one charge, for the attempt Tally now shows as SUCCEEDED.
2. Check the customer's account standing: a decline may have opened a collection case. It closes when Payment reports the success; check it did.
3. Report the false declines to the provider, with the references and times. Repeated ones are a provider defect.

## Never

- Never refund a corrected charge without checking the provider shows exactly one charge for the reference.
