package com.tally.catalog.application;

import com.tally.account.AccountApi;
import com.tally.account.AccountView;
import com.tally.account.UnknownAccountException;
import com.tally.catalog.BillingPeriod;
import com.tally.catalog.CatalogApi;
import com.tally.catalog.PriceQuote;
import com.tally.catalog.UnknownPlanException;
import com.tally.catalog.domain.PriceBook;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class CatalogService implements CatalogApi {

    private final AccountApi accounts;
    private final PriceBook prices;

    CatalogService(AccountApi accounts, PriceBook prices) {
        this.accounts = accounts;
        this.prices = prices;
    }

    @Override
    public PriceQuote quote(UUID accountId, String planId, BillingPeriod period) {
        AccountView account = accounts.get(accountId).orElseThrow(() -> new UnknownAccountException(accountId));
        return prices.price(planId, period, account.currency())
                .map(price -> new PriceQuote(planId, period, price))
                .orElseThrow(() -> new UnknownPlanException(planId, account.currency().getCurrencyCode()));
    }
}
