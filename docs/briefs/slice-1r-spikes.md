# Slice 1R spikes: findings

Two spikes from `slice-1r.md`. Both pass. Each finding names the test that pins it; a library or provider change that breaks a finding fails the build.

| Spike | Question | Answer | Test |
|---|---|---|---|
| S-1 | Can Spring Modulith 2.1.1's resubmission be driven one publication at a time with a predicate, catching per row? | **Yes**, through `resubmitIncompletePublications(Predicate)`. PR-7 and AT-17 can go ahead. | `spikes.SpikeS1ResubmitOneAtATimeIT` (7 experiments) |
| S-2 | Does the fake provider model idempotency keys and status lookup by key? | **It did not.** Both are now modelled on Stripe's API v1 and listed in `fake-provider/CONTRACT.md` (A22, A23). Status "by key" means repeating the request with its key; no real lookup by key exists. | `fakeprovider.IdempotencyKeysTest` (7 tests) |

## S-1. Resubmission one publication at a time

### Set-up

Each experiment starts a real Tally instance (`TallyInstances`) on the shared test database, with the platform's polling off. It then leaves three FAILED publications of `SpikeEvent`, published a second apart:
1. an unreadable one, published first, so that it is read first;
2. two readable ones.

The unreadable row is damaged in one of two ways:
- the payload no longer parses (`{"id":"not-a-uuid"}`), as after an incompatible field change;
- the event class is gone (`event_type` names a class that does not exist), as after a rename.

The test clock then moves 10 minutes, and one way of resubmitting is tried.

### Findings

| # | Experiment | Observed (log prefix `SPIKE-S1`) |
|---|---|---|
| 1 | **S1a** `resubmitIncompletePublicationsOlderThan`, today's job | Throws `InvalidFormatException` in the caller. Both readable publications stay `FAILED` (1 call each). The unreadable row is left `RESUBMITTED`. **First-run finding 1 reproduced:** everything behind the unreadable row waits. |
| 2 | **S1b** `resubmitIncompletePublications(predicate)`, excluding the unreadable row by ID | Nothing thrown. Both readable `COMPLETED` (2 calls). The unreadable row stays `FAILED`. **The predicate runs before the event is read:** rows are deserialized lazily, when `getEvent()` is called. |
| 3 | **S1c** `ResubmissionOptions`, batch size 1, a filter excluding the unreadable row | Nothing thrown, and **nothing resubmitted**: all three stay `FAILED`. The batch is read before the filter runs, so the excluded row at the head fills the batch, on this call and every later one. This API also skips publications a crash left in progress (first run, Spike A, A2b). **Not usable.** |
| 4 | **S1d** `resubmitIncompletePublications(predicate)`, where the predicate calls `getEvent()` in try/catch and returns false on failure | Nothing thrown. The predicate skipped exactly the unreadable row. Both readable `COMPLETED`; the unreadable row stays `FAILED`. **One pass, isolated per row.** |
| 5 | **S1e** one call per incomplete row, `p -> p.getIdentifier().equals(row)`, each call in try/catch | The unreadable row's call threw `InvalidFormatException`; the others completed. The unreadable row is left `RESUBMITTED`. Works, but each call reloads every incomplete row: O(n²). |
| 6 | **S1f** as S1d, with the event class missing | Nothing thrown, and the predicate **never saw** the row: the repository drops it on load with `WARN JdbcEventPublicationRepositoryV2 : Event '<id>' of unknown type 'spike.modulith.RemovedEvent' found`. It is never resubmitted and stays incomplete. |
| 7 | **S1g** the predicate API, on a publication a crash left `PROCESSING` | Clock not moved: still `PROCESSING` (1 call). Clock +5 minutes: `COMPLETED` (2 calls). **The predicate API covers crash-left rows**, as `resubmitIncompletePublicationsOlderThan` does. It has **no minimum age of its own**, so the predicate must check the age. |

### Evidence

From the run of `SpikeS1ResubmitOneAtATimeIT` that went green (row IDs replaced by `<row>`):

```
SPIKE-S1 S1a resubmitIncompletePublicationsOlderThan, bad payload | thrown=InvalidFormatException: Cannot deserialize value of type `java.util.UUID` from String "not-a-uuid" ... | first=FAILED (1 calls) | second=FAILED (1 calls) | broken=RESUBMITTED (1 calls)
SPIKE-S1 S1b resubmitIncompletePublications(predicate excluding the broken row by ID), bad payload | thrown=nothing | first=COMPLETED (2 calls) | second=COMPLETED (2 calls) | broken=FAILED (1 calls)
SPIKE-S1 S1c ResubmissionOptions(batch 1, filter excluding the broken row), bad payload | thrown=nothing | first=FAILED (1 calls) | second=FAILED (1 calls) | broken=FAILED (1 calls)
SPIKE-S1 S1d resubmitIncompletePublications(predicate that reads the event in try/catch), bad payload | skipped=[<row>] | thrown=nothing | first=COMPLETED (2 calls) | second=COMPLETED (2 calls) | broken=FAILED (1 calls)
SPIKE-S1 S1e one resubmitIncompletePublications(identifier equals) call per row, bad payload | per-row outcomes=[<row>=InvalidFormatException] | thrown=nothing | first=COMPLETED (2 calls) | second=COMPLETED (2 calls) | broken=RESUBMITTED (1 calls)
SPIKE-S1 S1f resubmitIncompletePublications(predicate that reads the event in try/catch), missing class | skipped=[] | thrown=nothing | first=COMPLETED (2 calls) | second=COMPLETED (2 calls) | broken=FAILED (1 calls)
SPIKE-S1 S1g predicate (age and readable), crash-left, clock not moved | PROCESSING (1 calls)
SPIKE-S1 S1g predicate (age and readable), crash-left, clock +5 min | COMPLETED (2 calls)
```

An earlier run published all three rows at the same instant; there the read order was arbitrary, and S1a happened to read the unreadable row last. The spike now publishes a second apart, so the order is fixed.

### What PR-7 builds on this

- **The job:** one call to `resubmitIncompletePublications(olderThan(minAge).and(readable))`, where `readable` calls `getEvent()` in try/catch (S1d). This is one pass, it decides per publication, and one unreadable row never blocks the rest. It keeps the minimum age and the single-instance job from the first run (Spike A).
- **S1e is the fallback** if a Modulith upgrade starts deserializing before the predicate. That would break S1b and S1d, and the build fails.
- **Unreadable rows must be visible:**
  - the predicate counts what it skips, for the job's health (rule 8);
  - a row whose class is gone never reaches the predicate (S1f), so only `EventHealth`'s count of incomplete rows (SQL, every age) shows it. AT-17 asserts both.

### Does ADR-016 hold?

Yes. "Resubmission isolates each publication" can be met with the public API of Spring Modulith 2.1.1. Two things are added to what the record assumes:
- the minimum age is the predicate's job (S1g);
- an unreadable row is never resubmitted, only reported. Repairing it needs a person.

## S-2. Idempotency keys and status by key

### What the fake did before

No keys. `CONTRACT.md` A8 said that every request is charged again, whatever its reference. The only lookup was by reference (A11). The new tests failed against it:

```
[ERROR] Tests run: 7, Failures: 5, Errors: 1, Skipped: 0 -- in com.fakeprovider.IdempotencyKeysTest
```

(The seventh test, "a refusal is not saved", passed trivially, because nothing was saved at all.)

### What a real provider does

Stripe's API v1 ([Idempotent requests](https://docs.stripe.com/api/idempotent_requests), [API v2 overview](https://docs.stripe.com/api-v2-overview), [Advanced error handling](https://docs.stripe.com/error-low-level)):
- the first response for a key is saved and replayed for repeats, including errors and `500`s, for 24 hours. After that, a reused key makes a new request;
- a repeat with other parameters is an `idempotency_error`;
- a request that conflicts with one still executing gets `409`, and is not saved. Neither is a request that fails validation;
- rate limiting runs before the idempotency layer (already in Q-A);
- API v2 differs: it retries failed requests instead of replaying them, and keeps keys for 30 days.

The documents reviewed describe **no endpoint that looks a request up by its idempotency key**. Repeating the request with the key is the way to learn its outcome.

### What the fake does now

`IdempotencyKeys` and `ProviderController.charge` implement these behaviours; `IdempotencyKeysTest` passes all seven tests:

| Behaviour | Test |
|---|---|
| The same key twice charges once and replays the first response | `theSameKeyTwiceChargesOnceAndReplaysTheFirstResponse` |
| A decline is replayed for the same key; a new key charges again | `aDeclineIsReplayedForTheSameKeyAndANewKeyChargesAgain` |
| The same key with other parameters: `400 idempotency_error`, nothing charged | `theSameKeyWithOtherParametersIsRefusedAndChargesNothing` |
| After a timeout, the same key returns the recorded charge without charging again (status by key) | `afterATimeoutTheSameKeyReturnsTheRecordedChargeWithoutChargingAgain` |
| `503` and `429` are not saved; the same key charges later | `aRefusedRequestIsNotSavedSoTheSameKeyChargesLater` |
| A repeat while the first executes: `409`, not saved | `aRepeatWhileTheFirstStillExecutesIsAConflictAndIsNotSaved` |
| After retention (simulated by `POST /admin/idempotency-keys/prune`), a reused key charges again | `afterRetentionAReusedKeyChargesAgain` |

Without a key, the fake behaves exactly as before (A8), so Tally, which sends no key yet, is unaffected. The existing `ProviderControllerTest` and all of Tally's tests pass unchanged.

One deliberate difference from Stripe: the fake's `503` is not saved, because it models a gateway in front of the provider (A6). Stripe saves its own `500`s.

### Findings for the design

1. **Each attempt needs its own key**, as decided for PR-8; now evidenced. Reusing the first attempt's key replays its decline, even after the card is fixed (`aDeclineIsReplayedForTheSameKeyAndANewKeyChargesAgain`).
2. **"Resolve unknowns by key" (ADR-004, ADR-018) means "repeat the request with its key".** No real lookup by key exists. That has three edges the design does not mention (CONTRACT A23):
   - **the first request never executed:** the repeat charges now. That is still at most once, but the resolver never sees "no such charge" (design §5's UNKNOWN → TECHNICAL_FAILURE branch); it gets a fresh outcome instead;
   - **after 24 hours the key is forgotten:** a repeat is a new charge, even if the first one charged. That is a double charge, against D2. The resolver must stop repeating before the retention ends, and fall back to the lookup by reference (A11);
   - **the first request is still executing:** the repeat gets `409`. That is no answer yet; ask again later.

   The repeat must also carry exactly the first request's parameters, so an attempt must store its token, amount, currency and reference.
3. **The lookup by reference (A11) stays necessary.** It is the only way to resolve an attempt after 24 hours, and the only way to see "no such charge". Q-B (its consistency delay) is still open.

### Question before PR-6

Today's resolver uses the lookup by reference (A11), not keys. Should PR-6 keep that, and leave sending keys to PR-8 as planned? Or should the resolver also repeat by key within the retention period? I propose:
- keep resolution by reference in PR-6;
- add keys in PR-8, where each attempt gets its own;
- record finding 2 as a proposed amendment to ADR-004 and ADR-018: "resolve by reference; a repeat by key only within the provider's retention".
