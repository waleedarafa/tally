package com.tally.platform;

import java.util.Optional;
import java.util.UUID;

/**
 * Who sent the request (ADR-032; CLAUDE.md rule 16): the token's subject, and, for a household, the account the user
 * has. Found once for each request, and given to any web handler that takes a {@code Caller} parameter, so every
 * module's web layer can check that a request acts only for the caller's account, with no dependency on
 * {@code account}. {@code platform} holds the type and no rule about accounts: {@code account} finds the account,
 * through {@link CallerAccounts}.
 *
 * @param id      the token's subject: a user of the identity provider
 * @param account the user's account; empty while the user has none
 */
public record Caller(String id, Optional<UUID> account) {

    /** Whether {@code accountId} is the caller's account. A request for any other answers 404 (brief rule 5). */
    public boolean owns(UUID accountId) {
        return accountId != null && account.filter(accountId::equals).isPresent();
    }
}
