package com.tallyaccess.persistence;

import com.tallyaccess.application.AccessFactsMessage;
import com.tallyaccess.application.FactsStore;
import com.tallyaccess.application.StoredFacts;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** {@link FactsStore} on Access's own schema, {@code access}. */
@Repository
class JdbcFactsStore implements FactsStore {

    private static final TypeReference<List<AccessFactsMessage.Source>> SOURCES = new TypeReference<>() { };

    private final JdbcTemplate jdbc;
    private final JsonMapper json = JsonMapper.builder().build();

    JdbcFactsStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<StoredFacts> find(UUID accountId) {
        return jdbc.query("SELECT sequence, produced_at, standing, sources::text AS sources FROM access.facts"
                + " WHERE account_id = ?", (rs, row) -> new StoredFacts(accountId, rs.getLong("sequence"),
                        rs.getObject("produced_at", OffsetDateTime.class).toInstant(), rs.getString("standing"),
                        json.readValue(rs.getString("sources"), SOURCES)), accountId).stream().findFirst();
    }

    @Override
    public boolean applyIfNewer(AccessFactsMessage message, Instant appliedAt) {
        return jdbc.update("INSERT INTO access.facts (account_id, sequence, produced_at, standing, sources, applied_at)"
                + " VALUES (?, ?, ?, ?, ?::jsonb, ?) ON CONFLICT (account_id) DO UPDATE SET"
                + " sequence = excluded.sequence, produced_at = excluded.produced_at, standing = excluded.standing,"
                + " sources = excluded.sources, applied_at = excluded.applied_at"
                + " WHERE access.facts.sequence < excluded.sequence",
                message.accountId(), message.sequence(), at(message.producedAt()),
                message.standing() == null ? "" : message.standing(),
                json.writeValueAsString(message.sourcesOrNone()), at(appliedAt)) == 1;
    }

    @Override
    public Map<Integer, Long> positions(String topic) {
        Map<Integer, Long> positions = new HashMap<>();
        jdbc.query("SELECT partition, next_offset FROM access.log_position WHERE topic = ?",
                rs -> {
                    positions.put(rs.getInt("partition"), rs.getLong("next_offset"));
                }, topic);
        return positions;
    }

    @Override
    public void advance(String topic, int partition, long nextOffset) {
        jdbc.update("INSERT INTO access.log_position (topic, partition, next_offset) VALUES (?, ?, ?)"
                + " ON CONFLICT (topic, partition) DO UPDATE SET"
                + " next_offset = greatest(access.log_position.next_offset, excluded.next_offset)",
                topic, partition, nextOffset);
    }

    @Override
    public boolean completeOnce(String topic) {
        return jdbc.queryForObject("SELECT exists (SELECT 1 FROM access.copy_state)"
                + " AND exists (SELECT 1 FROM access.log_position WHERE topic = ?)", Boolean.class, topic);
    }

    @Override
    public void markComplete(Instant at) {
        jdbc.update("INSERT INTO access.copy_state (complete_at) VALUES (?) ON CONFLICT (one) DO NOTHING", at(at));
    }

    @Override
    public Optional<Instant> newestProducedAt() {
        return Optional.ofNullable(jdbc.queryForObject("SELECT max(produced_at) FROM access.facts",
                OffsetDateTime.class)).map(OffsetDateTime::toInstant);
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
