package com.tally.accessfeed.adapter.persistence;

import com.tally.accessfeed.domain.AccountFacts;
import com.tally.accessfeed.domain.AccountFactsStore;
import com.tally.accessfeed.domain.Source;
import com.tally.accessfeed.domain.SourceKind;
import com.tally.accessfeed.domain.SourceState;
import com.tally.account.Standing;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@link AccountFactsStore} on the feed's own schema, {@code accessfeed} (CLAUDE.md rule 2). */
@Repository
class JdbcAccountFacts implements AccountFactsStore {

    private final JdbcTemplate jdbc;

    JdbcAccountFacts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public AccountFacts lockOrCreate(UUID accountId) {
        jdbc.update("INSERT INTO accessfeed.account_facts (account_id) VALUES (?) ON CONFLICT (account_id) DO NOTHING",
                accountId);
        // The lock first: the sources are read only once no other transaction can change them.
        AccountFacts locked = jdbc.queryForObject("SELECT sequence, standing, standing_sequence, produced_at"
                + " FROM accessfeed.account_facts WHERE account_id = ? FOR UPDATE",
                (rs, row) -> {
                    OffsetDateTime producedAt = rs.getObject("produced_at", OffsetDateTime.class);
                    return new AccountFacts(accountId, rs.getLong("sequence"),
                            Standing.valueOf(rs.getString("standing")), rs.getObject("standing_sequence", Long.class),
                            producedAt == null ? null : producedAt.toInstant(), List.of());
                }, accountId);
        List<Source> sources = jdbc.query("SELECT kind, source_id, state, source_sequence FROM accessfeed.fact_source"
                + " WHERE account_id = ?", (rs, row) -> new Source(SourceKind.valueOf(rs.getString("kind")),
                        rs.getObject("source_id", UUID.class), SourceState.valueOf(rs.getString("state")),
                        rs.getObject("source_sequence", Long.class)), accountId);
        return new AccountFacts(accountId, locked.sequence(), locked.standing(), locked.standingSequence(),
                locked.producedAt(), sources);
    }

    @Override
    public void save(AccountFacts facts) {
        jdbc.update("UPDATE accessfeed.account_facts SET sequence = ?, standing = ?, standing_sequence = ?,"
                + " produced_at = ? WHERE account_id = ?", facts.sequence(), facts.standing().name(),
                facts.standingSequence(),
                facts.producedAt() == null ? null : OffsetDateTime.ofInstant(facts.producedAt(), ZoneOffset.UTC),
                facts.accountId());
        for (Source source : facts.sources()) {
            jdbc.update("INSERT INTO accessfeed.fact_source (account_id, kind, source_id, state, source_sequence)"
                    + " VALUES (?, ?, ?, ?, ?) ON CONFLICT (account_id, kind, source_id)"
                    + " DO UPDATE SET state = excluded.state, source_sequence = excluded.source_sequence",
                    facts.accountId(), source.kind().name(), source.id(), source.state().name(),
                    source.sourceSequence());
        }
    }
}
