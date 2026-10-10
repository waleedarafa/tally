# ADR-033. Invoiced versus collected is checked inside Invoicing

**Status:** Accepted. Implements part of ADR-024's compliance section.

## Context
ADR-024 asks for a daily check of collected against invoiced amounts. Invoicing and Payment own those facts. No module may read another's tables.

## Decision
Invoicing runs a daily check, `invoicing.invoiced-versus-collected`. It calls `PaymentApi.collected(references)`. A `PAID` invoice must match the amount Payment collected for its reference. An unpaid invoice must have nothing collected. Violations raise `tally.invariant.violations`.

Business justification: cost. Disagreements between Tally's own records are found within a day.

## Consequences
- Uses the existing Invoicing → Payment dependency. No new dependency.
- It compares Tally's own records only. It cannot detect B4 (ADR-031 does).
- Slice 4's finance export builds on it.

## Compliance
Automated. The check is a periodic job with health signals. A test injects a mismatch.

## Drivers
D10, D14.

## Notes
Recorded after implementation in slice 1b (commit 92fac62). Per-invoice comparison chosen over daily totals: it names the invoice that disagrees.

Archived; see `docs/architecture/v2/README.md`.
