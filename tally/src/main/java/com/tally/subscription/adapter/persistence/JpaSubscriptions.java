package com.tally.subscription.adapter.persistence;

import com.tally.subscription.domain.Subscription;
import com.tally.subscription.domain.SubscriptionPeriod;
import com.tally.subscription.domain.SubscriptionStatus;
import com.tally.subscription.domain.Subscriptions;
import com.tally.subscription.domain.Subscriptions.Add;
import jakarta.persistence.EntityManager;
import jakarta.persistence.FlushModeType;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * Implements {@link Subscriptions} on the EntityManager. An adapter class rather than a Spring Data
 * interface: a new subscription carries its id and version, which Spring Data's save would merge, and
 * Hibernate refuses to merge a versioned entity that does not exist yet. {@code @Repository} translates
 * persistence exceptions. {@link #add} uses {@code ON CONFLICT (id) DO NOTHING} so a same-key retry
 * does not abort the transaction (ADR-045).
 */
@Repository
class JpaSubscriptions implements Subscriptions {

    @PersistenceContext
    private EntityManager entities;

    /** The live states of the subscription (design.md §5): TRIAL, ACTIVE and CANCELLING; not ENDED. */
    static final List<SubscriptionStatus> LIVE = List.of(SubscriptionStatus.TRIAL,
            SubscriptionStatus.ACTIVE, SubscriptionStatus.CANCELLING);

    @Override
    public Add add(Subscription subscription) {
        // ON CONFLICT (id) DO NOTHING does not abort the transaction, so a same-key retry can
        // return the existing row (ADR-045). An advisory lock serialises two inserts of the same
        // id (the invoice insert uses the same pattern). FlushMode.COMMIT: do not flush a pending
        // account update first, so a versioned payment-token write is not flushed before the insert.
        entities.createNativeQuery(
                "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:id, 0))) held")
                .setFlushMode(FlushModeType.COMMIT)
                .setParameter("id", subscription.id().toString())
                .getSingleResult();
        int inserted = entities.createNativeQuery("""
                INSERT INTO subscription.subscription (id, account_id, plan_id, offer_version, started_at,
                    anchor_day, version, status, end_reason, ended_at)
                VALUES (:id, :accountId, :planId, :offerVersion, :startedAt,
                    :anchorDay, :version, :status, :endReason, :endedAt)
                ON CONFLICT (id) DO NOTHING
                """)
                .setFlushMode(FlushModeType.COMMIT)
                .setParameter("id", subscription.id())
                .setParameter("accountId", subscription.accountId())
                .setParameter("planId", subscription.planId())
                .setParameter("offerVersion", subscription.offerVersion())
                .setParameter("startedAt", subscription.startedAt())
                .setParameter("anchorDay", subscription.anchorDay())
                .setParameter("version", subscription.version())
                .setParameter("status", subscription.status().name())
                .setParameter("endReason", subscription.endReason() == null ? null : subscription.endReason().name())
                .setParameter("endedAt", subscription.endedAt())
                .executeUpdate();
        if (inserted == 0) {
            return Add.ALREADY_PRESENT;
        }
        List<SubscriptionPeriod> periods = subscription.periods();
        for (int i = 0; i < periods.size(); i++) {
            SubscriptionPeriod period = periods.get(i);
            entities.createNativeQuery("""
                    INSERT INTO subscription.subscription_period (subscription_id, position, number,
                        start_date, end_date)
                    VALUES (:id, :position, :number, :startDate, :endDate)
                    """)
                    .setFlushMode(FlushModeType.COMMIT)
                    .setParameter("id", subscription.id())
                    .setParameter("position", i)
                    .setParameter("number", period.number())
                    .setParameter("startDate", period.start())
                    .setParameter("endDate", period.end())
                    .executeUpdate();
        }
        return Add.ADDED;
    }

    @Override
    public Optional<Subscription> find(UUID subscriptionId) {
        return Optional.ofNullable(entities.find(Subscription.class, subscriptionId));
    }

    @Override
    public boolean hasLive(UUID accountId) {
        return entities.createQuery("SELECT count(s) FROM Subscription s WHERE s.accountId = :account"
                + " AND s.status IN (:live)", Long.class).setParameter("account", accountId)
                .setParameter("live", LIVE).getSingleResult() > 0;
    }

    @Override
    public Optional<Subscription> lock(UUID subscriptionId) {
        return Optional.ofNullable(entities.find(Subscription.class, subscriptionId, LockModeType.PESSIMISTIC_WRITE));
    }

    @Override
    public List<Subscription> lockLive(UUID accountId) {
        return entities.createQuery("SELECT s FROM Subscription s WHERE s.accountId = :account AND s.status IN (:live)",
                Subscription.class).setParameter("account", accountId).setParameter("live", LIVE)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE).getResultList();
    }

    @Override
    public void update(Subscription subscription) {
        entities.flush();
    }
}
