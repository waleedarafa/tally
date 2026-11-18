# Runbook: tokens refused

Every request to billing under `/v1/`, and every request to Access, carries a token from the identity provider, and Tally checks its signature, issuer, audience and expiry itself (ADR-032). A token that fails a check gets 401. Billing serves households; Access serves only the streaming platform, with its service token (Slice 4 brief, decision 1).

## Who

The on-call engineer for billing (`ApiTokensRefused`) or for Access (`AccessTokensRefused`).

## When

- **`ApiTokensRefused`** (page, ADR-032): for 5 minutes, more than half of billing's answers are 401, and more than one a second (`tally_api_requests_total{outcome="unauthenticated"}`).
- **`AccessTokensRefused`** (page, D13): the same for Access (`tally_access_requests_total`). Access has one caller, so this nearly always means the streaming platform's service token is refused, and households get no answer to "can I watch?".

The counters are taken in front of the security filters, so they hold every answer: `answered`, `unauthenticated` (401), `forbidden` (403) and `failed` (5xx).

## See what is wrong

```sh
# Billing: the metrics are on the management port.
curl -sS http://<instance>:8082/actuator/prometheus | grep -E 'tally_(api_requests|identity_key_fetches)_total'
# Access: on its own port.
curl -sS http://<instance>:8090/actuator/prometheus | grep -E 'tally_access_(requests|identity_key_fetches)_total'
```

The 401 answer says why, in its `WWW-Authenticate` header (`invalid_token`, with a description such as `Jwt expired` or `The aud claim is not valid`). Try one known good token:

```sh
curl -sS -o /dev/null -D - -H "Authorization: Bearer $TOKEN" http://<instance>:8080/v1/me/account | grep -iE '^(HTTP|www-authenticate)'
```

Decode the token (its middle part is base64url JSON) and compare `iss`, `aud`, `exp` and the header's `kid` with the settings:

```sh
# Each instance's settings: TALLY_IDENTITY_ISSUER and TALLY_IDENTITY_JWK_SET_URI.
curl -sS "$TALLY_IDENTITY_JWK_SET_URI" | jq '.keys[].kid'
```

## What to do, by cause

### (a) Every token is refused, the good one too: the settings
After a deploy, or a change at the identity provider: the issuer (`TALLY_IDENTITY_ISSUER`) must equal the token's `iss` exactly, trailing slash included; billing's audience is `tally-billing`, Access's `tally-access`. Correct the setting and roll the instances. Roll back the deploy if it changed them.

### (b) Tokens are refused with "expired" or "not yet valid"
The instance's clock, or the provider's, is wrong. Tally checks the expiry on its own clock (rule 4) with Spring Security's default skew of 60 s. Fix time sync on the host.

### (c) Tokens with a new key ID are refused
The provider rotated its keys and an instance cannot fetch the new set: see [identity-keys-unavailable](identity-keys-unavailable.md). Access takes a new key at most one hold-off (30 s) after it first sees it.

### (d) Only Access refuses: the streaming platform's token
A token without the scope `access:read` gets 403, not 401, so a 401 here is the token itself: expired (the platform did not refresh it, or could not, because the provider is down), or for the wrong audience. Tell the streaming platform's on-call, with the description from `WWW-Authenticate`.

### (e) Good tokens pass, and a flood of bad ones arrives: someone is trying tokens
`answered` holds steady while `unauthenticated` climbs. Tally changes nothing for a refused token, so no data is at risk from the refusals themselves. Find the sources in the load balancer's log and block them there; tell security.

## Never

- Never widen the accepted audiences or issuers to make the alert stop: a token for another service would then be accepted.
- Never turn off the expiry check, or raise the clock skew, to let expired tokens through.
