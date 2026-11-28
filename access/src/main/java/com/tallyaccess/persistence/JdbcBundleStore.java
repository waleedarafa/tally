package com.tallyaccess.persistence;

import com.tallyaccess.application.BundleFact;
import com.tallyaccess.application.BundleStore;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@link BundleStore} on Access's own schema, {@code access}. */
@Repository
class JdbcBundleStore implements BundleStore {

    private final JdbcTemplate jdbc;

    JdbcBundleStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean applyIfNewer(BundleFact fact, Instant appliedAt) {
        return jdbc.update("INSERT INTO access.partner_bundle (partner_id, bundle_id, subscriber_id, state,"
                + " partner_sequence, occurred_at, applied_at) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (partner_id, bundle_id)"
                + " DO UPDATE SET subscriber_id = excluded.subscriber_id, state = excluded.state,"
                + " partner_sequence = excluded.partner_sequence, occurred_at = excluded.occurred_at,"
                + " applied_at = excluded.applied_at"
                + " WHERE access.partner_bundle.partner_sequence < excluded.partner_sequence",
                fact.partnerId(), fact.bundleId(), fact.subscriberId(), fact.state().name(), fact.partnerSequence(),
                at(fact.occurredAt()), at(appliedAt)) == 1;
    }

    @Override
    public List<BundleFact> findBySubscriber(UUID subscriberId) {
        return jdbc.query("SELECT partner_id, bundle_id, subscriber_id, state, partner_sequence, occurred_at"
                + " FROM access.partner_bundle WHERE subscriber_id = ? ORDER BY partner_id, bundle_id",
                (rs, row) -> new BundleFact(rs.getString("partner_id"), rs.getString("bundle_id"),
                        rs.getObject("subscriber_id", UUID.class),
                        BundleFact.State.valueOf(rs.getString("state")), rs.getLong("partner_sequence"),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant()),
                subscriberId);
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
