# Runbook: identity keys unavailable

Tally checks each token's signature with the identity provider's public keys, which it fetches from the provider's key set URL (`TALLY_IDENTITY_JWK_SET_URI`). The fetch has short timeouts, 2 s, so a provider that hangs does not hold requests (ADR-039).

- **Billing** (ADR-039, option B) keeps the keys in memory. It fetches them at its first request and again for a key ID it does not hold. A running instance goes on checking tokens through an outage; an instance that starts during the outage has no keys and answers **500** to every request until the provider is back.
- **Access** (ADR-039, option A) keeps the last key set in its own database (`access.identity_key_set`). A running instance, and one that starts during the outage, go on checking tokens with it. After any fetch it waits a hold-off, 30 s, before the next. An instance whose database holds no key set answers **503** with `Retry-After` until the provider is back.

The limit (ADR-039): Access answers only as long as the streaming platform's service token lives, 1 hour in AT-30, because the platform gets its next token from the same provider.

## Who

The on-call engineer for billing (`IdentityKeysUnavailable`) or for Access (`AccessIdentityKeysUnavailable`).

## When

- **`IdentityKeysUnavailable`** (page, ADR-039): in 10 minutes, billing's fetches of the key set failed at least 3 times and none succeeded (`tally_identity_key_fetches_total`), for 2 minutes.
- **`AccessIdentityKeysUnavailable`** (page, D13): the same for Access (`tally_access_identity_key_fetches_total`).

Tally fetches only when it needs to, so an outage no request meets is not seen by these alerts: a running instance with the keys it needs does not fetch. Watch the provider's own status as well.

## See what is wrong

```sh
curl -sS http://<billing instance>:8082/actuator/prometheus | grep -E 'tally_(identity_key_fetches|api_requests)_total'
curl -sS http://<access instance>:8090/actuator/prometheus | grep -E 'tally_access_(identity_key_fetches|requests)_total'
# From the instance's host: does the provider answer, and in time?
curl -sS -m 5 -o /dev/null -w '%{http_code} %{time_total}s\n' "$TALLY_IDENTITY_JWK_SET_URI"
```

In Access's log: `The identity provider's key set could not be fetched; the key set kept since it was last fetched is used`.

```sql
-- Access's database: the key set kept, and when it was last fetched.
SELECT jwk_set_uri, fetched_at FROM access.identity_key_set;
```

## What to do, by cause

### (a) The provider is down
Tell the identity provider's owners. Meanwhile:
- Do **not** restart or add billing instances: a new one has no keys and answers 500 to every request. Keep the running ones.
- Access keeps answering with the key set it kept. Note the time: the streaming platform's service token lives 1 hour, and the platform cannot get a new one while the provider is down. Tell the streaming platform's on-call, so they keep their current token and do not discard it.
- An Access instance with no key set (`access.identity_key_set` empty) answers 503; it recovers by itself within 30 s of the provider's return.

### (b) The provider is up, but Tally cannot reach it
The `curl` above fails or takes over 2 s from the instance's host, but works elsewhere: a firewall, DNS or proxy between them. Fix the path; nothing in Tally needs restarting.

### (c) The URL is wrong
After a deploy or a change at the provider: `TALLY_IDENTITY_JWK_SET_URI` answers 404 or something that is not a key set. Correct it and roll the instances one at a time, checking each answers before the next.

### (d) Fetches fail only for some tokens
Tokens with a key ID the provider does not publish (forged, or from another provider) make Tally fetch again, and the fetch succeeds; that is not this alert. If fetches fail and succeed in turns, the provider is unstable: treat it as (a).

## Never

- Never delete the row in `access.identity_key_set` during an outage: it is what lets Access answer.
- Never put a public key in the settings to work around an outage (ADR-039, option C was not chosen): it could not be rotated without a restart.
