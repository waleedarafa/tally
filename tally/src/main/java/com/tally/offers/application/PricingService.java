package com.tally.offers.application;

import com.tally.account.AccountApi;
import com.tally.account.AccountView;
import com.tally.account.UnknownAccountException;
import com.tally.offers.Offers;
import com.tally.offers.PriceQuote;
import com.tally.offers.Pricing;
import com.tally.offers.QuoteReason;
import com.tally.offers.QuoteRequest;
import com.tally.offers.UnknownPlanException;
import com.tally.offers.domain.Offer;
import com.tally.offers.domain.OfferCatalogue;
import com.tally.offers.domain.OfferVersion;
import com.tally.offers.domain.PricingRuleRegistry;
import com.tally.shared.Money;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Quotes the offer version a subscription is on through the rule of its kind (ADR-015; decision A). The
 * account gives the currency; the version's terms give the price in it; the rule gives the lines. New
 * sign-ups get the offer's current version ({@link #currentVersion}).
 */
@Service
class PricingService implements Pricing, Offers {

    private final AccountApi accounts;
    private final OfferCatalogue catalogue;
    private final PricingRuleRegistry rules;

    PricingService(AccountApi accounts, OfferCatalogue catalogue, PricingRuleRegistry rules) {
        this.accounts = accounts;
        this.catalogue = catalogue;
        this.rules = rules;
    }

    @Override
    public PriceQuote quote(QuoteRequest request) {
        AccountView account = account(request.accountId());
        String currency = account.currency().getCurrencyCode();
        OfferVersion version = catalogue.offer(request.offerId()).flatMap(o -> o.version(request.offerVersion()))
                .orElseThrow(() -> new UnknownPlanException(request.offerId(), currency));
        Money price = version.terms().priceIn(account.currency())
                .orElseThrow(() -> new UnknownPlanException(request.offerId(), currency));
        return PriceQuote.of(request, version.number(),
                rules.ruleFor(version.kind()).lines(version.terms(), price, request.period()),
                new QuoteReason(request.offerId(), version.number(), version.kind(), Map.of()));
    }

    @Override
    public boolean isOffered(String offerId, UUID accountId) {
        return currentVersion(offerId, accountId).isPresent();
    }

    @Override
    public OptionalInt currentVersion(String offerId, UUID accountId) {
        AccountView account = account(accountId);
        return currentVersion(offerId).filter(v -> v.terms().priceIn(account.currency()).isPresent())
                .map(v -> OptionalInt.of(v.number())).orElse(OptionalInt.empty());
    }

    private AccountView account(UUID accountId) {
        return accounts.get(accountId).orElseThrow(() -> new UnknownAccountException(accountId));
    }

    private Optional<OfferVersion> currentVersion(String offerId) {
        return catalogue.offer(offerId).flatMap(Offer::current);
    }
}
