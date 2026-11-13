package com.tally.accessfeed.domain;

import com.tally.account.Standing;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The current access facts of one account (ADR-033, design.md §4): its standing and its sources. {@link #sequence()}
 * goes up by one only when the facts change; a repeated or older event changes nothing.
 *
 * <p>For the standing and for each source it keeps the sequence of the last event applied. An event whose sequence
 * is not above it is a repeat or older, and is ignored. An event with no sequence is applied: a missing sequence
 * means "order not known", never "older" (CLAUDE.md rule 7, FF-13).
 */
public final class AccountFacts {

    private final UUID accountId;
    private long sequence;
    private Standing standing;
    private Long standingSequence;
    private Instant producedAt;
    private final Map<UUID, Source> sources = new TreeMap<>();

    public AccountFacts(UUID accountId, long sequence, Standing standing, Long standingSequence, Instant producedAt,
            List<Source> sources) {
        this.accountId = accountId;
        this.sequence = sequence;
        this.standing = standing;
        this.standingSequence = standingSequence;
        this.producedAt = producedAt;
        sources.forEach(source -> this.sources.put(source.id(), source));
    }

    /** The facts of an account the feed has not seen: good standing, no source, sequence 0. */
    public static AccountFacts none(UUID accountId) {
        return new AccountFacts(accountId, 0, Standing.GOOD, null, null, List.of());
    }

    /** Applies a standing; the sequence goes up if the standing changes. GOOD, OVERDUE or BLOCKED only. */
    public void applyStanding(Standing standing, Long eventSequence, Instant now) {
        if (standing == null || standing == Standing.UNKNOWN) {
            throw new IllegalArgumentException("A standing is GOOD, OVERDUE or BLOCKED: " + standing);
        }
        if (olderOrRepeated(eventSequence, standingSequence)) {
            return;
        }
        standingSequence = eventSequence != null ? eventSequence : standingSequence;
        if (this.standing != standing) {
            this.standing = standing;
            changed(now);
        }
    }

    /** Applies a source's state; the sequence goes up if the source is new or its state changes. */
    public void applySource(SourceKind kind, UUID id, SourceState state, Long eventSequence, Instant now) {
        Source current = sources.get(id);
        Long last = current == null ? null : current.sourceSequence();
        if (olderOrRepeated(eventSequence, last)) {
            return;
        }
        sources.put(id, new Source(kind, id, state, eventSequence != null ? eventSequence : last));
        if (current == null || current.state() != state) {
            changed(now);
        }
    }

    private static boolean olderOrRepeated(Long eventSequence, Long lastApplied) {
        return eventSequence != null && lastApplied != null && eventSequence <= lastApplied;
    }

    private void changed(Instant now) {
        sequence++;
        producedAt = now;
    }

    public UUID accountId() {
        return accountId;
    }

    public long sequence() {
        return sequence;
    }

    public Standing standing() {
        return standing;
    }

    public Long standingSequence() {
        return standingSequence;
    }

    public Instant producedAt() {
        return producedAt;
    }

    /** The sources, by ID. */
    public List<Source> sources() {
        List<Source> all = new ArrayList<>(sources.values());
        all.sort(Comparator.comparing(Source::id));
        return all;
    }
}
