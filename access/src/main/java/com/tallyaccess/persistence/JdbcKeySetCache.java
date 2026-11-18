package com.tallyaccess.persistence;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.support.SimpleValueWrapper;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The identity provider's last key set, kept in Access's own database (ADR-039, option A for Access; spike S-2). Spring
 * Security's token decoder uses it as its {@link Cache}: it reads it once, when the decoder is built, so an instance
 * that starts while the provider is down still checks tokens; it asks for a fetch only for a key ID it does not hold
 * (S-2, finding 4).
 *
 * <p>Two departures from a plain cache, both so that an outage never loses the keys:
 * <ul>
 *   <li>{@link #invalidate()} (the decoder met an unknown key ID) marks the copy stale; it does not delete it.</li>
 *   <li>A stale copy is fetched again; if the fetch fails, the copy kept is returned, and the token with the unknown
 *       key is refused by the decoder. Every other token goes on being checked with the kept keys.</li>
 * </ul>
 * A key the provider withdraws during an outage stays accepted until a fetch succeeds (ADR-039's cost). The copy is
 * held in memory once read: the database is read when the decoder is built and written on each successful fetch.
 *
 * <p>The decoder asks under one lock, for every token (review of #92, finding 1). So, after any fetch, successful or
 * not, a stale mark is ignored for {@code holdOff}: in that time the kept copy is returned at once, and a token with a
 * key ID it does not hold is refused at once, instead of holding every other token behind a fetch from a provider
 * that hangs. A key the provider adds is therefore accepted at most {@code holdOff} after it is first seen.
 */
public class JdbcKeySetCache implements Cache {

    private static final Logger log = LoggerFactory.getLogger(JdbcKeySetCache.class);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Duration holdOff;
    private volatile String keySet;
    private volatile boolean stale;
    private volatile Instant lastFetch;

    public JdbcKeySetCache(JdbcTemplate jdbc, Clock clock, Duration holdOff) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.holdOff = holdOff;
    }

    @Override
    public String getName() {
        return "identity-key-set";
    }

    @Override
    public Object getNativeCache() {
        return jdbc;
    }

    @Override
    public ValueWrapper get(Object key) {
        String kept = kept(key);
        return kept == null ? null : new SimpleValueWrapper(kept);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Class<T> type) {
        return (T) kept(key);
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized <T> T get(Object key, Callable<T> loader) {
        String kept = kept(key);
        if (kept != null && !stale) {
            return (T) kept;
        }
        Instant now = clock.instant();
        if (lastFetch != null && now.isBefore(lastFetch.plus(holdOff))) {
            if (kept != null) {
                return (T) kept;
            }
            throw new ValueRetrievalException(key, loader, new IllegalStateException(
                    "The identity provider's key set was asked for less than " + holdOff + " ago"));
        }
        lastFetch = now;
        try {
            T fetched = loader.call();
            put(key, fetched);
            return fetched;
        } catch (Exception failure) {
            if (kept == null) {
                throw new ValueRetrievalException(key, loader, failure);
            }
            // The provider is down or answers badly: the kept keys go on checking every other token.
            stale = false;
            log.warn("The identity provider's key set could not be fetched; the key set kept since it was last fetched"
                    + " is used: {}", failure.toString());
            return (T) kept;
        }
    }

    @Override
    public synchronized void put(Object key, Object value) {
        if (value == null) {
            return;
        }
        String fetched = value.toString();
        jdbc.update("""
                INSERT INTO access.identity_key_set (jwk_set_uri, key_set, fetched_at) VALUES (?, ?, ?)
                ON CONFLICT (jwk_set_uri) DO UPDATE SET key_set = EXCLUDED.key_set, fetched_at = EXCLUDED.fetched_at
                """, key.toString(), fetched, Timestamp.from(clock.instant()));
        keySet = fetched;
        stale = false;
    }

    /** The decoder met a key ID it does not hold: fetch again on the next read, and keep this copy until then. */
    @Override
    public void evict(Object key) {
        stale = true;
    }

    @Override
    public void clear() {
        stale = true;
    }

    private String kept(Object key) {
        String inMemory = keySet;
        if (inMemory != null) {
            return inMemory;
        }
        List<String> stored = jdbc.queryForList("SELECT key_set FROM access.identity_key_set WHERE jwk_set_uri = ?",
                String.class, key.toString());
        keySet = stored.isEmpty() ? null : stored.getFirst();
        return keySet;
    }
}
