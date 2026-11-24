package com.tally.offers.application;

import com.tally.account.AccountApi;
import com.tally.account.AccountView;
import com.tally.account.UnknownAccountException;
import com.tally.flags.FlagsApi;
import com.tally.flags.domain.Flags;
import com.tally.offers.domain.Offer;
import com.tally.offers.domain.OfferCatalogue;
import com.tally.offers.domain.OfferKind;
import com.tally.offers.domain.OfferKinds;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/** In-memory ports for the golden tests: offers, kinds, accounts and flags, no database. */
class InMemoryOffers implements OfferCatalogue, OfferKinds, AccountApi, FlagsApi {

    final Map<String, Offer> offers = new HashMap<>();
    final List<OfferKind> kinds = new ArrayList<>();
    final Map<UUID, AccountView> accounts = new HashMap<>();
    final Flags flags = new Flags();

    @Override
    public Map<String, Boolean> statesFor(UUID accountId, String country) {
        Map<String, Boolean> states = new LinkedHashMap<>();
        for (String name : flags.names()) {
            states.put(name, flags.isOn(name, accountId, country));
        }
        return Collections.unmodifiableMap(states);
    }

    @Override
    public Optional<Offer> offer(String offerId) {
        return Optional.ofNullable(offers.get(offerId));
    }

    @Override
    public List<OfferKind> all() {
        return List.copyOf(kinds);
    }

    /** A test account (AccountApi opens none: an account is opened only for a user, by its web layer). */
    public AccountView open(Currency currency, ZoneId timeZone) {
        return open(currency, timeZone, "US");
    }

    /** A test account (AccountApi opens none: an account is opened only for a user, by its web layer). */
    public AccountView open(Currency currency, ZoneId timeZone, String country) {
        AccountView account = new AccountView(UUID.randomUUID(), currency, timeZone, country, Optional.empty(),
                com.tally.account.Standing.GOOD, java.util.OptionalInt.empty());
        accounts.put(account.id(), account);
        return account;
    }

    @Override
    public Optional<AccountView> get(UUID accountId) {
        return Optional.ofNullable(accounts.get(accountId));
    }

    @Override
    public void recordPaymentToken(UUID accountId, String paymentToken) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean setBillingDayFrom(UUID accountId, Instant startedAt) {
        AccountView current = accounts.get(accountId);
        if (current == null) {
            throw new UnknownAccountException(accountId);
        }
        if (current.billingDay().isPresent()) {
            return false;
        }
        int day = LocalDate.ofInstant(startedAt, current.timeZone()).getDayOfMonth();
        accounts.put(accountId, new AccountView(current.id(), current.currency(), current.timeZone(),
                current.country(), current.paymentToken(), current.standing(), OptionalInt.of(day)));
        return true;
    }
}
