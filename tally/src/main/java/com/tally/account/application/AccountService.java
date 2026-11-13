package com.tally.account.application;

import com.tally.account.AccountApi;
import com.tally.account.AccountStanding;
import com.tally.account.AccountView;
import com.tally.account.Standing;
import com.tally.account.StandingChanged;
import com.tally.account.UnknownAccountException;
import com.tally.account.domain.Account;
import com.tally.account.domain.AccountRepository;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AccountService implements AccountApi, AccountStanding {

    private final AccountRepository accounts;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    AccountService(AccountRepository accounts, ApplicationEventPublisher events, Clock clock) {
        this.accounts = accounts;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public AccountView open(Currency currency, ZoneId timeZone) {
        return view(accounts.save(new Account(UUID.randomUUID(), currency, timeZone, clock.instant())));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountView> get(UUID accountId) {
        return accounts.findById(accountId).map(AccountService::view);
    }

    @Override
    @Transactional
    public void recordPaymentToken(UUID accountId, String paymentToken) {
        accounts.findById(accountId)
                .orElseThrow(() -> new UnknownAccountException(accountId))
                .recordPaymentToken(paymentToken);
    }

    /**
     * Collections only (F3); nothing calls it yet. Publishes {@link StandingChanged} in this transaction, only when the
     * standing changes, with the account's version after the change: the flush makes Hibernate raise it first.
     */
    @Override
    @Transactional
    public void set(UUID accountId, Standing standing, String cause) {
        Account account = accounts.findById(accountId).orElseThrow(() -> new UnknownAccountException(accountId));
        if (account.setStanding(standing, cause)) {
            accounts.flush();
            events.publishEvent(new StandingChanged(UUID.randomUUID(), clock.instant(), accountId, account.version(),
                    standing));
        }
    }

    private static AccountView view(Account account) {
        return new AccountView(account.id(), account.currency(), account.timeZone(), account.paymentToken(),
                account.standing());
    }
}
