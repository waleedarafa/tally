package com.tally.subscription.adapter.persistence;

import com.tally.subscription.domain.Subscription;
import com.tally.subscription.domain.SubscriptionStatus;
import com.tally.subscription.domain.Subscriptions;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Repository;

/**
 * Implements {@link Subscriptions} on the EntityManager. An adapter class rather than a Spring Data
 * interface: a new subscription carries its id and version, which Spring Data's save would merge, and
 * Hibernate refuses to merge a versioned entity that does not exist yet. {@code @Repository} translates
 * persistence exceptions; {@link #addIfNoneLive} reads the guard's name before they are translated.
 */
@Repository
class JpaSubscriptions implements Subscriptions {

    @PersistenceContext
    private EntityManager entities;

    /** The sign-up's duplicate guard (V37). */
    static final String ONE_LIVE_PER_ACCOUNT = "subscription_one_live_per_account";

    @Override
    public boolean addIfNoneLive(Subscription subscription) {
        try {
            entities.persist(subscription);
            entities.flush();
            return true;
        } catch (ConstraintViolationException e) {
            if (ONE_LIVE_PER_ACCOUNT.equals(e.getConstraintName())) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public Optional<Subscription> find(UUID subscriptionId) {
        return Optional.ofNullable(entities.find(Subscription.class, subscriptionId));
    }

    @Override
    public boolean hasLive(UUID accountId) {
        return entities.createQuery("SELECT count(s) FROM Subscription s WHERE s.accountId = :account"
                + " AND s.status = :live", Long.class).setParameter("account", accountId)
                .setParameter("live", SubscriptionStatus.ACTIVE).getSingleResult() > 0;
    }

    @Override
    public List<Subscription> lockLive(UUID accountId) {
        return entities.createQuery("SELECT s FROM Subscription s WHERE s.accountId = :account AND s.status = :live",
                Subscription.class).setParameter("account", accountId).setParameter("live", SubscriptionStatus.ACTIVE)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
    }

    @Override
    public void update(Subscription subscription) {
        entities.flush();
    }
}
