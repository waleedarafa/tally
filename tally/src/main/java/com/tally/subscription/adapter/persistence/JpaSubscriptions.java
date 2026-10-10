package com.tally.subscription.adapter.persistence;

import com.tally.subscription.domain.Subscription;
import com.tally.subscription.domain.Subscriptions;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Implements {@link Subscriptions} on the EntityManager. An adapter class rather than a Spring Data
 * interface: a new subscription carries its id and version, which Spring Data's save would merge, and
 * Hibernate refuses to merge a versioned entity that does not exist yet. {@code @Repository} translates
 * persistence exceptions, so a duplicate account surfaces as DataIntegrityViolationException.
 */
@Repository
class JpaSubscriptions implements Subscriptions {

    @PersistenceContext
    private EntityManager entities;

    @Override
    public void add(Subscription subscription) {
        entities.persist(subscription);
        entities.flush();
    }

    @Override
    public Optional<Subscription> find(UUID subscriptionId) {
        return Optional.ofNullable(entities.find(Subscription.class, subscriptionId));
    }

    @Override
    public boolean existsFor(UUID accountId) {
        return entities.createQuery("SELECT count(s) FROM Subscription s WHERE s.accountId = :account", Long.class)
                .setParameter("account", accountId).getSingleResult() > 0;
    }

    @Override
    public void update(Subscription subscription) {
        entities.flush();
    }
}
