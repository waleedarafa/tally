package com.tally.support;

import com.tally.AbstractIntegrationTest;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * A fresh database in the shared PostgreSQL container, for tests that run Tally as separate
 * processes on the real clock (AT-9, AT-10). Fresh, because other tests leave queue rows due at
 * dates from 2026 to 2028, which a real-clock instance would process.
 */
public final class ChaosDatabase {

    private final String url;
    private final JdbcTemplate jdbc;

    private ChaosDatabase(String url) {
        this.url = url;
        this.jdbc = new JdbcTemplate(dataSource(url));
    }

    public static ChaosDatabase create(String prefix) {
        AbstractIntegrationTest.POSTGRES.start();
        String name = prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
        new JdbcTemplate(dataSource(AbstractIntegrationTest.POSTGRES.getJdbcUrl())).execute("CREATE DATABASE " + name);
        String base = AbstractIntegrationTest.POSTGRES.getJdbcUrl();
        return new ChaosDatabase(base.replaceFirst("/" + AbstractIntegrationTest.POSTGRES.getDatabaseName() + "(\\?|$)",
                "/" + name + "$1"));
    }

    public String url() {
        return url;
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    /**
     * Seeds {@code accounts} BASIC subscriptions, each with a renewal into period 2 due on
     * {@code billingDate} (UTC midnight): accounts, subscriptions with their first period, and
     * Subscription's renewal queue, as the application itself would have left them a month earlier.
     * Needs Tally's migrations applied.
     */
    public void seedDueRenewals(int accounts, LocalDate billingDate, String paymentToken) {
        jdbc.queryForObject("""
                WITH seed AS (
                    SELECT gen_random_uuid() AS account_id, gen_random_uuid() AS subscription_id
                    FROM generate_series(1, ?)
                ), a AS (
                    INSERT INTO account.account (id, currency, time_zone, payment_token, created_at)
                    SELECT account_id, 'USD', 'UTC', ?, now() FROM seed RETURNING 1
                ), s AS (
                    INSERT INTO subscription.subscription
                        (id, account_id, plan_id, started_at, version, anchor_day, offer_version)
                    SELECT subscription_id, account_id, 'BASIC', ?::date - interval '1 month', 1, ?, 1 FROM seed
                    RETURNING id
                ), p AS (
                    INSERT INTO subscription.subscription_period (subscription_id, position, number, start_date, end_date)
                    SELECT id, 0, 1, (?::date - interval '1 month')::date, ?::date FROM s RETURNING 1
                ), q AS (
                    INSERT INTO subscription.renewal (queue_key, due_at, payload, status, attempts, created_at)
                    SELECT account_id || ':' || ?, ?::date, subscription_id || ',2', 'READY', 0, now() FROM seed
                    RETURNING 1
                )
                SELECT (SELECT count(*) FROM a) + (SELECT count(*) FROM p) + (SELECT count(*) FROM q)
                """, Long.class,
                accounts, paymentToken, billingDate, billingDate.getDayOfMonth(), billingDate, billingDate,
                billingDate.toString(), billingDate);
    }

    private static DriverManagerDataSource dataSource(String url) {
        return new DriverManagerDataSource(url, AbstractIntegrationTest.POSTGRES.getUsername(),
                AbstractIntegrationTest.POSTGRES.getPassword());
    }
}
