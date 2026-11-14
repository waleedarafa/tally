package com.tally.accessfeed.application;

import com.tally.accessfeed.domain.AccountFacts;
import com.tally.accessfeed.domain.AccountFactsStore;
import com.tally.accessfeed.domain.SourceKind;
import com.tally.accessfeed.domain.SourceState;
import com.tally.account.Standing;
import com.tally.account.StandingChanged;
import com.tally.subscription.SubscriptionChangeType;
import com.tally.subscription.SubscriptionChanged;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

/**
 * Turns the events of {@code subscription} and {@code account} into the account's current facts (ADR-033). Each
 * listener runs in its own transaction, under the account's row lock, so two copies of one event at the same time
 * change the row once (CLAUDE.md rule 7). Both listeners are pinned (pinned-listeners.txt).
 *
 * <p>A value this release does not know (a change type, a standing) changes nothing and is not recorded, so a known
 * event with a lower sequence still applies after it. It is counted, by field, in
 * {@code tally.accessfeed.values.not_known} (AccessValueNotKnown, brief PR-8); a repeated event is counted again.
 */
@Service
class AccessFeedListeners {

    private final AccountFactsStore facts;
    private final AccessFactsRelay relay;
    private final Clock clock;
    private final Counter typeNotKnown;
    private final Counter standingNotKnown;

    AccessFeedListeners(AccountFactsStore facts, AccessFactsRelay relay, Clock clock, MeterRegistry meters) {
        this.facts = facts;
        this.relay = relay;
        this.clock = clock;
        // Registered at 0 from the start, so a scrape shows them and increase() sees the first value.
        this.typeNotKnown = notKnown(meters, "subscription.type");
        this.standingNotKnown = notKnown(meters, "standing");
    }

    private static Counter notKnown(MeterRegistry meters, String field) {
        return Counter.builder("tally.accessfeed.values.not_known").tag("field", field).register(meters);
    }

    /** A started or renewed subscription is an active source. A renewal changes no fact, so it sends nothing. */
    @ApplicationModuleListener
    void on(SubscriptionChanged changed) {
        if (changed.type() == null || changed.type() == SubscriptionChangeType.UNKNOWN) {
            typeNotKnown.increment();
            return;
        }
        if (changed.type() != SubscriptionChangeType.STARTED && changed.type() != SubscriptionChangeType.PERIOD_RENEWED) {
            return;
        }
        AccountFacts account = facts.lockOrCreate(changed.accountId());
        long before = account.sequence();
        account.applySource(SourceKind.SUBSCRIPTION, changed.subscriptionId(), SourceState.ACTIVE, changed.sequence(),
                clock.instant());
        save(account, before);
    }

    @ApplicationModuleListener
    void on(StandingChanged changed) {
        if (changed.standing() == null || changed.standing() == Standing.UNKNOWN) {
            standingNotKnown.increment();
            return;
        }
        AccountFacts account = facts.lockOrCreate(changed.accountId());
        long before = account.sequence();
        account.applyStanding(changed.standing(), changed.sequence(), clock.instant());
        save(account, before);
    }

    /** Saves the facts; a change of them is sent to the log, through the relay's queue, in the same transaction. */
    private void save(AccountFacts account, long sequenceBefore) {
        facts.save(account);
        if (account.sequence() != sequenceBefore) {
            relay.changed(account.accountId(), account.sequence());
        }
    }
}
