package com.tally.account.application;

import com.tally.account.AccountApi;
import com.tally.account.AccountView;
import com.tally.account.UnknownAccountException;
import com.tally.account.domain.Account;
import com.tally.account.domain.AccountRepository;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AccountService implements AccountApi {

    private final AccountRepository accounts;
    private final Clock clock;

    AccountService(AccountRepository accounts, Clock clock) {
        this.accounts = accounts;
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

    private static AccountView view(Account account) {
        return new AccountView(account.id(), account.currency(), account.timeZone(), account.paymentToken());
    }
}
