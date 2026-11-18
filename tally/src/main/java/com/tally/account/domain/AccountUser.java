package com.tally.account.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The link from a user of the identity provider (the token's subject) to the user's one account (ADR-032; WAR-84).
 * Never changed once made: moving an account to another user is out of scope.
 */
@Entity
@Table(schema = "account", name = "account_user")
public class AccountUser {

    /** The duplicate guard (V43): one account for each user. */
    public static final String ONE_PER_USER = "account_user_one_per_user";

    @Id
    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private String userId;

    @Column(name = "linked_at", nullable = false, updatable = false)
    private Instant linkedAt;

    protected AccountUser() {
    }

    public AccountUser(UUID accountId, String userId, Instant linkedAt) {
        this.accountId = accountId;
        this.userId = userId;
        this.linkedAt = linkedAt;
    }

    public UUID accountId() {
        return accountId;
    }

    public String userId() {
        return userId;
    }
}
