# ADR-040. The approved dependency graph is checked by a test

**Status:** Accepted.

## Context
`CLAUDE.md` tells the coding agent to stop and ask before changing a module's allowed dependencies. In slice 1, `catalog → account` was proposed in a plan, got no answer, and was built. A written instruction is weaker than a check.

## Decision
The approved module dependencies live in one file (`architecture/module-dependencies.txt` in the test resources). Fitness function FF-11 compares every module's declared dependencies with it. Widening a module's dependencies fails the build until the approved file changes too. Changes to that file are reviewed by the architect.

All ArchUnit rules run with `allowEmptyShould` off, so a rule that matches no classes fails instead of passing silently.

Business justification: time to market. Boundaries stay where the context map put them.

## Consequences
A dependency change needs two edits, and the second one is visible in review.

## Compliance
Automated. FF-11, shown failing on a deliberate violation (`slice-1-fitness-proof.md`).

## Drivers
D5, D8, D9.

## Revisit when
Several teams exist: add code ownership rules (CODEOWNERS) for the file.

## Notes
Recorded after implementation in slice 1b (commit abad0b2).

Archived; see `docs/architecture/v2/README.md`.
