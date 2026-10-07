package com.fakeprovider;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;

/** In-memory tokens and charges. The fake provider keeps no state across restarts. */
@Component
class Ledger {

    private final Set<String> tokens = ConcurrentHashMap.newKeySet();
    private final List<Charge> charges = new CopyOnWriteArrayList<>();

    String issueToken() {
        String token = "tok_" + UUID.randomUUID();
        tokens.add(token);
        return token;
    }

    Charge charge(String token, long amount, String currency, String reference) {
        // An unknown token is a hard decline, as with a real provider.
        Charge.Outcome outcome = tokens.contains(token) ? Charge.Outcome.SUCCEEDED : Charge.Outcome.DECLINED_HARD;
        Charge charge = new Charge("ch_" + UUID.randomUUID(), reference, token, amount, currency, outcome, Instant.now());
        charges.add(charge);
        return charge;
    }

    List<Charge> chargesFor(String reference) {
        return charges.stream().filter(c -> c.reference().equals(reference)).toList();
    }
}
