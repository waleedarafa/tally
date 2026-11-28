package com.tally.flags.application;

import com.tally.flags.FlagsApi;
import com.tally.flags.domain.FlagCatalogue;
import com.tally.flags.domain.Flags;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The flags service: evaluates every defined flag for an account and country (ADR-010, ADR-048). */
@Service
class FlagsService implements FlagsApi {

    private final FlagCatalogue catalogue;

    FlagsService(FlagCatalogue catalogue) {
        this.catalogue = catalogue;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Boolean> statesFor(UUID accountId, String country) {
        Flags flags = catalogue.load();
        Map<String, Boolean> states = new LinkedHashMap<>();
        for (String name : flags.names()) {
            states.put(name, flags.isOn(name, accountId, country));
        }
        return Collections.unmodifiableMap(states);
    }
}
