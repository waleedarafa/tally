package com.tally.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tally.account.AccountApi;
import com.tally.invoicing.InvoiceStatus;
import com.tally.invoicing.InvoiceView;
import com.tally.invoicing.InvoicingApi;
import com.tally.subscription.SubscriptionApi;
import com.tally.support.AbstractProviderIntegrationTest;
import com.tally.support.FakeProvider;
import com.tally.support.Quiesce;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * AT-3. Calendar edges (D4). Pass when: day 31 bills on 30 April and on 28 or 29 February; billing
 * happens at local midnight in a non-UTC zone; and stays correct across daylight-saving changes.
 *
 * <p>Runs the real renewal path (billing queue, invoicing, payment, provider), moving the clock from
 * one due billing item to the next, from January 2026 to April 2028 (2028 is a leap year). Retention
 * runs on its production settings, so finished billing rows older than 30 days are deleted on the
 * way: billing times are checked on the invoices.
 */
@TestPropertySource(properties = {
        "tally.platform.polling-enabled=true",
        "tally.platform.poll-interval=100ms",
        "tally.test.context=at03"
})
class AT03CalendarEdgesIT extends AbstractProviderIntegrationTest {

    static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    static final ZoneId SANTIAGO = ZoneId.of("America/Santiago");
    static final Instant END = Instant.parse("2028-04-01T00:00:00Z");

    @Autowired
    AccountApi accounts;

    @Autowired
    SubscriptionApi subscriptions;

    @Autowired
    InvoicingApi invoicing;

    @Autowired
    JdbcTemplate jdbc;

    UUID subscribe(ZoneId zone) {
        UUID account = accounts.open(Currency.getInstance("USD"), zone).id();
        accounts.recordPaymentToken(account, FakeProvider.token());
        subscriptions.subscribe(account, "BASIC");
        await().atMost(Duration.ofSeconds(10)).until(() -> invoicing.invoicesFor(account).size() == 1);
        return account;
    }

    /**
     * When each renewal was due to be billed ({@code billed_for}), by billing date, after checking it
     * was issued at or after that time. The first invoice is billed at subscribe, not on the
     * calendar, so it is left out. Read from invoices, not from the billing queue, whose finished
     * rows the retention job deletes after 30 days (ADR-037).
     */
    Map<LocalDate, Instant> renewalsBilledAt(UUID account) {
        List<InvoiceView> renewals = invoicing.invoicesFor(account).stream().skip(1).toList();
        assertThat(renewals).allSatisfy(i -> assertThat(i.issuedAt()).as("issued at, for %s", i.periodStart())
                .isAfterOrEqualTo(i.billedFor()));
        return renewals.stream().collect(Collectors.toMap(InvoiceView::periodStart, InvoiceView::billedFor));
    }

    Instant nextDue(List<UUID> accounts) {
        return jdbc.queryForObject("SELECT min(due_at) FROM invoicing.billing_schedule"
                        + " WHERE status = 'READY' AND split_part(queue_key, ':', 1) = ANY (?::text[])",
                OffsetDateTime.class,
                (Object) accounts.stream().map(UUID::toString).toArray(String[]::new)).toInstant();
    }

    @Test
    void renewalsFollowTheLocalCalendarThroughShortMonthsLeapYearsAndDaylightSaving() {
        // Santiago subscribes on 6 January (local), so it bills on the 6th: 6 September 2026 has no 00:00.
        clock.set(Instant.parse("2026-01-06T15:00:00Z"));
        UUID santiago = subscribe(SANTIAGO);
        // New York subscribes on 31 January (local, 10:00 EST).
        clock.set(Instant.parse("2026-01-31T15:00:00Z"));
        UUID newYork = subscribe(NEW_YORK);
        List<UUID> both = List.of(santiago, newYork);

        // Move the clock to each due billing item in turn, letting the work finish before every jump.
        int jumps = 0;
        while (true) {
            Quiesce.untilIdle(jdbc, clock, both, Duration.ofSeconds(20));
            Instant due = nextDue(both);
            if (!due.isBefore(END)) {
                break;
            }
            clock.set(due);
            jumps++;
        }
        await().atMost(Duration.ofSeconds(20)).until(() -> both.stream().flatMap(a -> invoicing.invoicesFor(a).stream())
                .allMatch(i -> i.status() == InvoiceStatus.PAID));

        // Day 31: every New York invoice starts on the last day of its month (an independent oracle),
        // including 28 February 2026, 30 April 2026 and 29 February 2028.
        List<LocalDate> expected = new ArrayList<>();
        for (YearMonth m = YearMonth.of(2026, 1); !m.isAfter(YearMonth.of(2028, 3)); m = m.plusMonths(1)) {
            expected.add(m.atEndOfMonth());
        }
        List<InvoiceView> nyInvoices = invoicing.invoicesFor(newYork);
        assertThat(nyInvoices).extracting(InvoiceView::periodStart).containsExactlyElementsOf(expected);
        assertThat(nyInvoices).extracting(InvoiceView::periodStart)
                .contains(LocalDate.parse("2026-02-28"), LocalDate.parse("2026-04-30"), LocalDate.parse("2028-02-29"));
        for (int i = 1; i < nyInvoices.size(); i++) {
            assertThat(nyInvoices.get(i - 1).periodEnd()).isEqualTo(nyInvoices.get(i).periodStart());
        }

        // Local midnight in a non-UTC zone, on both sides of each daylight-saving change.
        Map<LocalDate, Instant> nySchedule = renewalsBilledAt(newYork);
        nySchedule.forEach((date, due) -> assertThat(due).isEqualTo(date.atStartOfDay(NEW_YORK).toInstant()));
        assertThat(nySchedule.get(LocalDate.parse("2026-02-28"))).isEqualTo(Instant.parse("2026-02-28T05:00:00Z"));
        assertThat(nySchedule.get(LocalDate.parse("2026-03-31"))).isEqualTo(Instant.parse("2026-03-31T04:00:00Z"));
        assertThat(nySchedule.get(LocalDate.parse("2026-10-31"))).isEqualTo(Instant.parse("2026-10-31T04:00:00Z"));
        assertThat(nySchedule.get(LocalDate.parse("2026-11-30"))).isEqualTo(Instant.parse("2026-11-30T05:00:00Z"));

        // Santiago: on the day midnight does not exist, billing happens at 01:00 local, the day's first instant.
        assertThat(invoicing.invoicesFor(santiago)).extracting(InvoiceView::periodStart)
                .contains(LocalDate.parse("2026-09-06")).allMatch(d -> d.getDayOfMonth() == 6);
        Instant gapDay = renewalsBilledAt(santiago).get(LocalDate.parse("2026-09-06"));
        assertThat(gapDay).isEqualTo(Instant.parse("2026-09-06T04:00:00Z"));
        assertThat(gapDay.atZone(SANTIAGO).toLocalTime()).hasToString("01:00");

        // Exactly one invoice and one charge per billing date.
        for (UUID account : both) {
            List<InvoiceView> invoices = invoicing.invoicesFor(account);
            assertThat(invoices).extracting(InvoiceView::periodStart).doesNotHaveDuplicates();
            invoices.forEach(i -> assertThat(FakeProvider.chargeCount(i.paymentReference())).isEqualTo(1));
        }
        long finishedRowsLeft = jdbc.queryForObject("SELECT count(*) FROM invoicing.billing_schedule WHERE status = 'DONE'"
                + " AND split_part(queue_key, ':', 1) = ANY (?::text[])", Long.class,
                (Object) both.stream().map(UUID::toString).toArray(String[]::new));
        System.out.printf("AT-3: %d clock jumps, %d New York and %d Santiago invoices, %d finished billing rows"
                + " left after retention%n", jumps, nyInvoices.size(), invoicing.invoicesFor(santiago).size(),
                finishedRowsLeft);
    }
}
