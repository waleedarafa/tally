package com.tally.accessfeed.application;

import com.tally.accessfeed.domain.AccountFacts;
import com.tally.accessfeed.domain.AccountFactsStore;
import com.tally.accessfeed.domain.SourceKind;
import com.tally.accessfeed.domain.SourceState;
import com.tally.account.Standing;
import com.tally.account.StandingChanged;
import com.tally.subscription.SubscriptionChangeType;
import com.tally.subscription.SubscriptionChanged;
import java.time.Clock;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

/**
 * Turns the events of {@code subscription} and {@code account} into the account's current facts (ADR-033). Each
 * listener runs in its own transaction, under the account's row lock, so two copies of one event at the same time
 * change the row once (CLAUDE.md rule 7). Both listeners are pinned (pinned-listeners.txt).
 *
 * <p>A value this release does not know (a change type, a standing) changes nothing and is not recorded, so a known
 * event with a lower sequence still applies after it.
 */
@Service
class AccessFeedListeners {

    private final AccountFactsStore facts;
    private final Clock clock;

    AccessFeedListeners(AccountFactsStore facts, Clock clock) {
        this.facts = facts;
        this.clock = clock;
    }

    /** A started or renewed subscription is an active source. A renewal changes no fact, so it sends nothing. */
    @ApplicationModuleListener
    void on(SubscriptionChanged changed) {
        if (changed.type() != SubscriptionChangeType.STARTED && changed.type() != SubscriptionChangeType.PERIOD_RENEWED) {
            return;
        }
        AccountFacts account = facts.lockOrCreate(changed.accountId());
        account.applySource(SourceKind.SUBSCRIPTION, changed.subscriptionId(), SourceState.ACTIVE, changed.sequence(),
                clock.instant());
        facts.save(account);
    }

    @ApplicationModuleListener
    void on(StandingChanged changed) {
        if (changed.standing() == null || changed.standing() == Standing.UNKNOWN) {
            return;
        }
        AccountFacts account = facts.lockOrCreate(changed.accountId());
        account.applyStanding(changed.standing(), changed.sequence(), clock.instant());
        facts.save(account);
    }
}
