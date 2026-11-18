package com.tally.platform;

import java.util.Optional;
import java.util.UUID;

/** Finds the account of a user (the token's subject). {@code account} implements it: the link lives there (WAR-84). */
public interface CallerAccounts {

    Optional<UUID> accountIdOf(String user);
}
