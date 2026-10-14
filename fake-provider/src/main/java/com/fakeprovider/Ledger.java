package com.fakeprovider;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/** In-memory tokens, charges and request counts. The fake provider keeps no state across restarts. */
@Component
class Ledger {

    private final Set<String> tokens = ConcurrentHashMap.newKeySet();
    private final List<Charge> charges = new CopyOnWriteArrayList<>();
    private final Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();
    private final List<SettlementBatch> batches = new CopyOnWriteArrayList<>();
    private int settledUpTo;

    String issueToken() {
        String token = "tok_" + UUID.randomUUID();
        tokens.add(token);
        return token;
    }

    /** Counts every charge request, including those answered 429 or 503. */
    void requested(String reference) {
        requests.computeIfAbsent(reference, r -> new AtomicInteger()).incrementAndGet();
    }

    Charge charge(String token, long amount, String currency, String reference, Modes modes) {
        Charge.Outcome outcome;
        int roll = ThreadLocalRandom.current().nextInt(100);
        if (!tokens.contains(token)) {
            // An unknown token is a hard decline, as with a real provider.
            outcome = Charge.Outcome.DECLINED_HARD;
        } else if (roll < modes.declineHardPercent()) {
            outcome = Charge.Outcome.DECLINED_HARD;
        } else if (roll < modes.declineHardPercent() + modes.declineSoftPercent()) {
            outcome = Charge.Outcome.DECLINED_SOFT;
        } else {
            outcome = Charge.Outcome.SUCCEEDED;
        }
        Charge charge = new Charge("ch_" + UUID.randomUUID(), reference, token, amount, currency, outcome, Instant.now());
        charges.add(charge);
        return charge;
    }

    List<Charge> chargesFor(String reference) {
        return charges.stream().filter(c -> c.reference().equals(reference)).toList();
    }

    int requestsFor(String reference) {
        AtomicInteger count = requests.get(reference);
        return count == null ? 0 : count.get();
    }

    /**
     * Closes a settlement batch, as a real provider does at its daily cut-off: every successful charge not yet in a
     * batch goes into the next one, numbered from 1. A batch may be empty. Declines move no money, so they are never
     * settled.
     */
    synchronized SettlementBatch closeBatch() {
        List<Charge> all = List.copyOf(charges);
        List<SettlementBatch.Line> lines = new ArrayList<>();
        for (Charge charge : all.subList(settledUpTo, all.size())) {
            if (charge.outcome() == Charge.Outcome.SUCCEEDED) {
                lines.add(new SettlementBatch.Line(charge.chargeId(), charge.reference(), charge.amount(),
                        charge.currency()));
            }
        }
        settledUpTo = all.size();
        SettlementBatch batch = new SettlementBatch(batches.size() + 1, List.copyOf(lines));
        batches.add(batch);
        return batch;
    }

    /** Closed batches numbered above {@code number}, oldest first. */
    List<SettlementBatch> batchesAfter(long number) {
        return batches.stream().filter(b -> b.number() > number).toList();
    }
}
