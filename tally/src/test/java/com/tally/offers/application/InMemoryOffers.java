package com.tally.offers.application;

import com.tally.account.AccountApi;
import com.tally.account.AccountView;
import com.tally.offers.domain.Offer;
import com.tally.offers.domain.OfferCatalogue;
import com.tally.offers.domain.OfferKind;
import com.tally.offers.domain.OfferKinds;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** In-memory ports for the golden tests: offers, kinds and accounts, no database. */
class InMemoryOffers implements OfferCatalogue, OfferKinds, AccountApi {

    final Map<String, Offer> offers = new HashMap<>();
    final List<OfferKind> kinds = new ArrayList<>();
    final Map<UUID, AccountView> accounts = new HashMap<>();

    @Override
    public Optional<Offer> offer(String offerId) {
        return Optional.ofNullable(offers.get(offerId));
    }

    @Override
    public List<OfferKind> all() {
        return List.copyOf(kinds);
    }

    @Override
    public AccountView open(Currency currency, ZoneId timeZone) {
        AccountView account = new AccountView(UUID.randomUUID(), currency, timeZone, Optional.empty(),
                com.tally.account.Standing.GOOD);
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
}
