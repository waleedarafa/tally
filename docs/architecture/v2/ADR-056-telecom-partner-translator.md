# ADR-056. Telecom partner bundles reach Access through a translator in Access

**Status:** Accepted (by delegation, Waleed 2026-11-18 "Feel free to take decisions"), 27 November 2026 (A-11 follow-up).

## Context

The Design Record names telecom partners as a user (G8: a new partner within one quarter, without rewriting billing) and puts them behind an anti-corruption layer into Access: "Telecom partners | Access | ACL | Bundle activated, ended" (Step 6; `design.md` §3 context map). Access's rules include: (1) a subscriber can watch if at least one source is active; (2) a source billed by Tally does not count while its account is blocked; (5) a partner bundle gives access without any Tally subscription. H2: the partner bills its bundle customers; they have access with no Tally subscription. Topic A-11 lists the payment and tax translators as built and "the telecom partner translator is not built".

What exists: Access is its own deployable (`access/`, `com.tallyaccess`, own database, ADR-001) that imports no billing code (`NoBillingCodeTest`). It keeps one row of AccessFacts per account from the event log (ADR-033 to ADR-035) and answers `GET /v1/access/{subscriberId}` to the streaming platform only (scope `access:read`, ADR-032). In Slice 2 a subscriber is the account. Source kinds known today: `SUBSCRIPTION`; the contract's future example already shows a `PARTNER_BUNDLE` kind with a `partnerSequence`.

No partner and no partner wire format exist in the documents. This record does not invent one.

## Decision

1. **Where.** The translator and everything about bundles live in Access, package `com.tallyaccess.partner`. Billing does not change: partners bill their own customers (H2), and bundle facts do not travel through the billing event log. `NoBillingCodeTest` stays true.
2. **Published language first.** Tally publishes one partner notice format, version 1, and a partner either sends it or gets its own translator. Notice v1 (JSON): `partnerId` (text, `[a-z][a-z0-9-]*`), `bundleId` (the partner's own reference, text, 1 to 100 characters), `subscriberId` (UUID), `event` (`ACTIVATED` or `ENDED`), `partnerSequence` (integer >= 1, rising per bundle), `occurredAt` (instant). The translator (`PartnerNoticeTranslator`) turns a notice into Access's own `BundleFact` (partner, bundle, subscriber, state `ACTIVE` or `ENDED`, partner sequence, occurred at) or refuses it with the reason (missing field, bad value, unknown event). A partner's own format later gets its own translator class producing the same `BundleFact`; Access's rules never see a partner's words.
3. **Who the subscriber is.** The notice carries the subscriber ID the streaming platform asks Access about (today an account ID, ADR-033). Access does not mint, look up or link IDs. How a partner learns that ID for a customer with no Tally account (a linking step at activation, in the streaming platform) is open; see Open questions.
4. **Ordering and repeats.** One stored row per (partner, bundle): the notice is applied only if its partner sequence is higher than the stored one, in one statement, as for AccessFacts (CLAUDE.md rule 15). A repeat or an older notice changes nothing. `ENDED` is kept as a row, so a late, older `ACTIVATED` cannot bring a bundle back.
5. **The rule.** `AccessRules` takes the account facts and the subscriber's bundles. An `ACTIVE` bundle gives access whatever the account's standing (rules 2 and 5): reason `ACTIVE_SOURCE`, an existing value, so the answer's contract (v1) does not change. Otherwise the answer is as today, except that a subscriber with no account facts but with only ended bundles is `NO_ACTIVE_SOURCE` (Access knows them). A subscriber with no facts and no bundles is `NOT_KNOWN`. When there are no account facts, `asOf` is the latest `occurredAt` of the bundles counted and `sequence` is null (both are already nullable in v1).
6. **Storage.** A new Access migration `V4__partner_bundle.sql`: `access.partner_bundle(partner_id, bundle_id, subscriber_id, state, partner_sequence, occurred_at, applied_at)`, primary key (partner_id, bundle_id), index on subscriber_id. Existing migrations are not edited.
7. **How notices arrive.** `POST /v1/partners/{partnerId}/notices` on Access, with a service token from the identity provider: audience `tally-access`, scope `access:partner`, and the token's subject equal to `{partnerId}` (one partner cannot send for another). 202 when stored or ignored as older (a repeat is harmless); 400 when the translator refuses (with the reason); 401/403/503 as the existing Access security. The streaming platform's `access:read` token cannot send notices, and a partner token cannot ask `GET /v1/access/...`.
8. **Build order.** (a) translator, `BundleFact` and the rule, with unit tests; (b) storage and the answer from facts plus bundles, with an integration test; (c) the endpoint, its security and `access-openapi.yaml`, with contract and security tests.

## Built (27 November 2026)

Steps (a), (b) and (c) are built and tested: `PartnerNoticeTranslator`, `BundleFact`, the rule in `AccessRules`, `V4__partner_bundle.sql` with `JdbcBundleStore`, and `POST /v1/partners/{partnerId}/notices` (`PartnerNoticeController`; security by path in `AccessSecurity`; `docs/api/access-openapi.yaml`). Choices made while building, inside the decision above:

- A notice whose `partnerId` differs from the path is a 400 with the reason `partnerId does not match the path`; the token check alone would not catch a partner naming another partner in the body.
- A refused notice is a 400 with the body `{"reason": "..."}` (schema `NoticeRefused`). Spring's own 400 for a body that is not valid JSON keeps its default body.
- The endpoint does not wait for Access to catch up with the log: it writes only to the bundle table, which the log does not feed.
- Tests: `PartnerNoticeTranslatorTest`, `AccessRulesBundleTest`, `PartnerBundleIT`, `PartnerNoticeEndpointIT` (token, path and ordering cases) and `AccessContractIT` (the operation against the specification).

## Consequences

- A partner can switch access on and off for its bundle customers without any billing change, which is what G8 asks for.
- A blocked Tally account does not take away a partner bundle's access.
- The streaming platform's contract (`GET /v1/access/{subscriberId}`, v1) is unchanged.
- Bundle-only subscribers depend on the open linking question below before a real partner can go live.
- Unlike AccessFacts, bundle rows are not a copy of a log: Access holds the only record of what partners sent. An emptied Access database (ADR-035's rebuild) gets the facts back from the log but not the bundles. Until partner reconciliation exists (open question 3), the Access database must be backed up like any system of record, and the rebuild runbook must say that bundles are lost.

## Rejected

- **Bundles through billing** (a billing module receives notices and adds a `PARTNER_BUNDLE` source to AccessFacts): billing would hold facts it never bills (H2), and a partner outage or burst would share billing's database and log.
- **A per-partner format from the start:** no partner's format is known; inventing one would be guessing. The published language costs a partner little and keeps one translator until a real partner needs another.
- **A new answer reason such as `PARTNER_BUNDLE`:** a contract change for the streaming platform with no use there yet.

## Open questions

1. How a bundle customer with no Tally account gets a subscriber ID the streaming platform and the partner share (linking at activation). Needs the streaming platform team and the partnerships lead.
2. Whether a partner may also *suspend* a bundle (a third event), and what the streaming platform shows then.
3. Partner reconciliation: a daily list of active bundles from each partner, to catch a lost `ENDED`.

## Drivers

Design Record Steps 3, 4 and 6 (G8, H2, Access rules 1, 2, 5); ADR-001; ADR-016; ADR-032; ADR-033; ADR-034; D11; A-11.

## Revisit when

The first real partner signs, or a partner cannot send notice v1.
