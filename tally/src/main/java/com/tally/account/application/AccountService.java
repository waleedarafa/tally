package com.tally.account.application;

import com.tally.account.AccountApi;
import com.tally.account.AccountStanding;
import com.tally.account.AccountView;
import com.tally.account.Standing;
import com.tally.account.StandingChanged;
import com.tally.account.UnknownAccountException;
import com.tally.account.domain.Account;
import com.tally.account.domain.AccountRepository;
import com.tally.account.domain.AccountUser;
import com.tally.account.domain.AccountUserRepository;
import com.tally.platform.CallerAccounts;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AccountService implements AccountApi, AccountStanding, AccountUsers, CallerAccounts {

    private final AccountRepository accounts;
    private final AccountUserRepository users;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    AccountService(AccountRepository accounts, AccountUserRepository users, ApplicationEventPublisher events,
            Clock clock) {
        this.accounts = accounts;
        this.users = users;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Reads the user's link first; with none, opens the account and inserts the link, which the unique constraint
     * guards against a second "open" at the same time (CLAUDE.md rule 11): the read is not the guard.
     */
    @Override
    @Transactional
    public Opened openFor(String user, Currency currency, ZoneId timeZone) {
        Optional<AccountView> existing = accountOf(user);
        if (existing.isPresent()) {
            return new Opened(existing.get(), false);
        }
        Account account = accounts.save(new Account(UUID.randomUUID(), currency, timeZone, clock.instant()));
        try {
            users.saveAndFlush(new AccountUser(account.id(), user, clock.instant()));
        } catch (DataIntegrityViolationException failure) {
            if (violates(failure, AccountUser.ONE_PER_USER)) {
                throw new UserHasAnAccountException(user, failure);
            }
            throw failure;
        }
        return new Opened(view(account), true);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountView> accountOf(String user) {
        return users.findByUserId(user).flatMap(link -> accounts.findById(link.accountId()))
                .map(AccountService::view);
    }

    /** The caller's account, for every module's web layer (brief rule 6). */
    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> accountIdOf(String user) {
        return users.findByUserId(user).map(AccountUser::accountId);
    }

    /** Whether {@code failure} is the named constraint's refusal, and not any other failure (rule 11). */
    private static boolean violates(Throwable failure, String constraint) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && constraint.equals(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountView> get(UUID accountId) {
        return accounts.findById(accountId).map(AccountService::view);
    }

    @Override
    @Transactional
    public void recordPaymentToken(UUID accountId, String paymentToken) {
        accounts.lockById(accountId)
                .orElseThrow(() -> new UnknownAccountException(accountId))
                .recordPaymentToken(paymentToken);
    }

    @Override
    @Transactional
    public boolean setBillingDayFrom(UUID accountId, Instant startedAt) {
        return accounts.lockById(accountId)
                .orElseThrow(() -> new UnknownAccountException(accountId))
                .setBillingDayFrom(startedAt);
    }

    /**
     * Collections only (F3); nothing calls it yet. Publishes {@link StandingChanged} in this transaction, only when the
     * standing changes, with the account's version after the change: the flush makes Hibernate raise it first.
     */
    @Override
    @Transactional
    public void set(UUID accountId, Standing standing, String cause) {
        Account account = accounts.lockById(accountId).orElseThrow(() -> new UnknownAccountException(accountId));
        if (account.setStanding(standing, cause)) {
            accounts.flush();
            events.publishEvent(new StandingChanged(UUID.randomUUID(), clock.instant(), accountId, account.version(),
                    standing));
        }
    }

    private static AccountView view(Account account) {
        return new AccountView(account.id(), account.currency(), account.timeZone(), account.paymentToken(),
                account.standing(), account.billingDay());
    }
}
