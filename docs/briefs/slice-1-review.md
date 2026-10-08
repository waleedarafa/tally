# Slice 1 review: the architect's evaluation and answers

This file holds the lightweight evaluation of slice 1 and the answers to the slice 1b report, so they live in the repository and not only in a chat. Decisions are in the records it links to.

## Part 1. Evaluation of slice 1

**Scope.** The slice 1 report, the fitness proof and the spike findings. Not the code itself. An outside review of the code is still planned before any real money.

**Verdict.** Definition of done met. All twelve acceptance tests pass; every fitness function was shown failing on a violation; both spikes are permanent tests.

**Main findings**
1. **Spike A: "idempotent" is not enough.** Two deliveries of one event at the same moment both marked an invoice paid. Fixed with an optimistic lock. Rule: listeners must be safe under concurrent duplicates (ADR-028).
2. **Spike B, case B4: revenue leakage.** A decline reported as success is never corrected. Real-world equivalents: refunds, chargebacks, late roll-forwards. Detection: daily settlement reconciliation (ADR-031). Action on the customer: finance decides (ADR-032, Proposed). New driver D14.
3. **All four defects were timing defects** (dynamic connascence). Structural fitness functions found none; runtime tests found all four. Slice 2 adds a fitness function that delivers every pinned event twice at the same time.

**Deviation calls**

| Deviation | Call | Record |
|---|---|---|
| D1, D2 versions outside Boot | Accept | ADR-039 (supersedes 026) |
| D3 `catalog → account` | Accept, revisit slice 3 | ADR-035 |
| D4 checkout in Invoicing | Reject: own process module | ADR-034 |
| D7 503 sent back to the queue | Accept as a written assumption, fake provider only | ADR-030 |
| D8 unknown with no charge → `FAILED(SOFT)` | Accept; finance to confirm the label | — |
| D12 no cleanup | Retention job | ADR-037 |
| D13 listener threads from Spring Boot | Platform owns the executor | ADR-029 |
| D20 collected versus invoiced | Check inside Invoicing | ADR-033 |
| Q1, R6 429 threshold | Aim at 95% of the provider's limit; 1% CI alarm | ADR-036 |
| Q6 build time | Two pipeline stages | ADR-038 |
| D3 built without approval; `allowEmptyShould` | Make the rules mechanical | ADR-040 |

**Risk assessment, with direction**

| Risk | Before | After slice 1 | Direction |
|---|---|---|---|
| Failed listeners never retried | 9 | 3 | Better |
| Two deliveries at once (new) | — | 3 (6 for an unguarded new listener) | New |
| Unknown outcomes and false declines | 6 | 2 | Better |
| False success never detected (new) | — | 6 | New |
| 503 assumed "not charged" (new) | — | 6 until slice 2 | New |
| Background jobs fail silently | 6 | 2 | Better |
| Busiest day: charging rate | 4 | 2 | Better |
| Busiest day: invoice rate | 4 | 4 | Unclear until staging |
| Fitness rules that check nothing | — | 4 | Fixed in 1b |
| Dependency changed without approval | — | 4 | Fixed in 1b |
| One person on call | 6 | 6 | Unchanged |

**Risk themes now:** (1) the fake provider encodes guesses about a real one; (2) correct one at a time is not correct concurrently.

## Part 2. Answers to the slice 1b report

1. **Checkout is missing from `CLAUDE.md` and ADR-013.** ADR-034 records it as a process module: no domain model, no business data, nothing depends on it. ADR-013 gets a cross-reference only. Governance rule (log README): an accepted record's decision text never changes; only its status and a Notes cross-reference may be edited.

2. **Retention and the freed key.** Permanent duplicate protection lives in business tables, which are never deleted. The queue key is only a first, temporary guard. This makes any retention period safe (ADR-037, `CLAUDE.md` rule 11). Every queue must name its business-table guard.

3. **The new check cannot catch B4.** Expected. ADR-033 compares Tally's own records; ADR-031 compares Tally with the provider. Both are needed.

4. **Changed tests.**
   - AT-11: correct. ADR-023 makes an hourly job overdue at 2 hours.
   - AT-3: an acceptance test should check business results, not queue rows. If it needs 26 months of queue rows, either it reads the wrong source, or the invoice is missing a fact (such as its billing time). Fix: invoices record their billing time; AT-3 checks invoices with normal retention.

5. **Questions for a real provider** (Stripe's public documentation as the reference):
   - *Does 503 mean no charge?* No. Stripe says to treat a 500 as indeterminate and groups 500, 502, 503 and 504 as server errors. It may roll a failed charge forward after an incident and notify later. Our 503 rule holds only for the fake provider (ADR-030); slice 2's idempotency keys remove it. A 429 is safe: Stripe's rate limiters run before the request executes.
   - *How long until a charge appears in a lookup?* Not documented in what was reviewed. Open question in `fake-provider/CONTRACT.md`.
   - *Tokens for renewals without the customer?* Card networks distinguish customer-initiated from merchant-initiated charges. Where strong customer authentication applies, the card must be saved for later use, with the customer's agreement, at first checkout, and a bank may still demand authentication at renewal. That creates a third failure reason: "the customer must come back". New driver D15, for the slice 2 design.
   - *Lesson:* an anticorruption layer hides a provider's vocabulary. It cannot hide obligations set by card networks or regulators; those reach into our own model.

6. **Fast-forward `main`?** Yes, once run 12's acceptance stage is green. From then on, `main` is the trunk: short-lived branches merged by pull request (ADR-038). A long-lived branch hides the real lead time.

## Sources
- Stripe, Advanced error handling: https://docs.stripe.com/error-low-level
- Stripe, Errors: https://docs.stripe.com/api/errors
- Stripe, The Setup Intents API: https://docs.stripe.com/payments/setup-intents.md

## Part 3. Corrections and the record audit (after slice 1b)

**Corrections to this review, from the agent's audit**
- Part 1, finding 3: three of the four defects were timing defects, not four. The fourth (pollers leaving workers idle) was a throughput logic defect, found by a load test. The lesson still holds: none of the four was found by a structural fitness function.
- Part 2, point 2: "any retention period is safe" was true for the billing queue only. The payment guard was a partial index covering open attempts. ADR-041 replaces it.

**The audit found a pattern.** Several records I wrote after slice 1b described checks in their Compliance sections that did not exist yet: alerts, a dedicated rule, a deployment gate. A record that claims a check which is not there is worse than no record: it makes a gap look closed. From now on:
- A Compliance section describes the intended check. The Notes say whether it exists yet.
- Every slice report audits the records it touched against the code (`CLAUDE.md`, report item 6).

**Answers to the agent's three questions**
1. **AT-3:** add `billed_for`, the scheduled billing time, with an additive migration. `issued_at` and `billed_for` differ when a renewal runs late, and D6's lateness measure needs both after queue rows are deleted.
2. **Record mismatches:**

| Gap | Action | When |
|---|---|---|
| ADR-028: check-then-insert in the collection listener | ADR-041: `CollectionRequest` with a full unique constraint | Now |
| ADR-037: payment guard is a partial index | ADR-041 | Now |
| ADR-028: subscription listener fails on duplicates | Absorb duplicates quietly (atomic insert) | Now |
| ADR-038: CI does not run on pull requests | Add `pull_request` trigger | Now |
| `CLAUDE.md` rule 7: unknown enum values | Audit cross-module enums; stored events must deserialize an unknown value to a safe default | Now |
| ADR-028 and ADR-037: alerts; money queues | Flag money queues now; alert rules as code with staging | Flag now; alerts slice 2, step 0 |
| ADR-038: no deployment gate | Built with staging | Slice 2, step 0 |
| ADR-029, ADR-034: compliance described a different check | Record corrected in Notes; no code | Done |

**Why rule 7 matters.** A Java enum cannot hold a value the code does not know, so the danger is in stored events. In slice 2, D15 adds a new payment failure reason (for example `AUTHENTICATION_REQUIRED`). During a rolling deployment, the old version can read an event stored by the new version. It fails to deserialise the unknown value, and the listener fails. Fix: every enum that crosses a module boundary or is stored in an event has an `UNKNOWN` default, and Jackson maps unknown values to it.

**Why the subscription listener must absorb duplicates.** If it fails on a duplicate, every duplicate becomes a failed publication, a resubmission and, once alerts exist, a false alarm. D7 allows fewer than one false alarm a week.

3. **Merging:** the architect merges. The cost is lead time: every pull request waits for a person. Pull requests that change `main` are reviewed by a person while there is no deployment gate. Revisit after slice 2, using lead-time data.
