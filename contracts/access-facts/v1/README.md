# AccessFacts, version 1

The message billing's `accessfeed` sends to the event log, and Access reads (ADR-012, ADR-033, ADR-034; `design.md` §4).

| File | Owner | What it is |
|---|---|---|
| `schema.json` | billing | The message, as JSON Schema. No extra properties; every property an ID, an enum, a number or a time (F15). |
| `consumer-access.json` | Access | The fields Access reads. Billing's commit stage fails if a message `accessfeed` can build lacks one (F7). |
| `examples/` | both | Messages for the consumer test (Access, Slice 2 PR-6) and the checks of billing. |

`examples/future-unknown-values.json` is **not** a valid version 1 message, on purpose. It holds what a later version may add: an unknown field (`graceEndsAt`, and `partnerSequence` in a source), and enum values version 1 does not know (`SUSPENDED`, `PARTNER_BUNDLE`, `PAUSED`). Access must read it without failing, and a value it does not know never takes access away (ADR-033). Every other example is valid version 1.

Change these files in the same commit as the code that changes the message (CLAUDE.md rule 7). Add only (ADR-016): a new field or enum value is added; nothing is renamed or removed. A break is version 2, in `v2/`.
