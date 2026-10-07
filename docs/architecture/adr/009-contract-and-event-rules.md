# ADR-009. Contract and event rules

**Status:** Accepted

## Context
Modules talk through Java interfaces and events. The compiler protects interfaces. Events are stored by Spring Modulith until delivered, so an event written by one version may be read by the next, and the compiler cannot help.

## Decision
- Each module owns its schema. No other module reads or writes it.
- Event changes are additive only. A breaking change becomes a new event type. The old type stays until no stored events of it remain.
- Event classes never move package or change name: Spring Modulith stores the class name with each pending event.
- Events carry only facts their publisher owns and that never change afterwards. Identifiers of other modules' entities are allowed; their data is not.
- Every event has an envelope: event ID, occurrence time (from the clock), account ID.
- Consumers ignore unknown fields.
- The external REST API is versioned in the URL (`/v1/...`).

Business justification: time to market. Contracts evolve during rolling deployments without coordinated releases.

## Consequences
Some old event types live on for a while.

## Compliance
Automated. A test lists every event class with its fully qualified name; renaming or moving one fails the build.

## Drivers
D2, D5, D10.

## Revisit when
A message broker is introduced (ADR-010). Then add schema checks for externalised events.

## Notes
Decided in Step 6, Part B. Clarified in the Step 7 review: identifiers of other modules' entities are allowed in envelopes.
