package com.tally.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.tally.support.AccessService;
import com.tally.support.AccessService.Instance;
import com.tally.support.BillingDatabase;
import com.tally.support.FakeProvider;
import com.tally.support.TallyProcess;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

/**
 * AT-18. Access survives billing (ADR-001, D13). With 100 accounts known to Access: stop every billing instance and
 * the billing database. Pass when Access answers every account correctly, for known and unknown IDs, and, once
 * billing is started again, a new sign-up reaches Access.
 *
 * <p>Billing is two separate processes from the jar the build packaged, on a PostgreSQL container of their own, so
 * that this test can stop the database itself (the shared one serves the rest of the run). Access is a container
 * with its own database (ADR-007). The 100 accounts sign up through billing's HTTP API.
 */
class AT18AccessSurvivesBillingIT {

    static final int ACCOUNTS = 100;
    static final int UNKNOWN = 20;

    final List<TallyProcess> billing = new ArrayList<>();
    BillingDatabase database;
    AccessService access;

    @AfterEach
    void stop() {
        billing.forEach(TallyProcess::close);
        if (access != null) {
            access.close();
        }
        if (database != null) {
            database.close();
        }
    }

    TallyProcess startBilling(String name, int port) {
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("server.port", String.valueOf(port));
        settings.put("tally.payment.provider.base-url", FakeProvider.baseUrl());
        settings.put("spring.datasource.username", database.username());
        settings.put("spring.datasource.password", database.password());
        settings.putAll(database.feedSettings());
        TallyProcess process = TallyProcess.start(name, database.url(), settings);
        billing.add(process);
        return process;
    }

    static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static UUID signUp(RestClient tally) {
        UUID account = UUID.fromString((String) tally.post().uri("/v1/accounts")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .body(Map.of("currency", "AED", "timeZone", "Asia/Dubai"))
                .retrieve().body(new ParameterizedTypeReference<Map<String, Object>>() { }).get("id"));
        int status = tally.post().uri("/v1/subscriptions")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .body(Map.of("accountId", account, "planId", "STANDARD", "paymentToken", FakeProvider.token()))
                .retrieve().toBodilessEntity().getStatusCode().value();
        assertThat(status).isEqualTo(201);
        return account;
    }

    @Test
    void accessAnswersEveryAccountWhileBillingAndItsDatabaseAreStoppedAndASignUpReachesItAfterwards() {
        database = BillingDatabase.start("at18");
        access = AccessService.on(database.feedSettings().get("tally.accessfeed.log.topic"));
        Instance instance = access.start("at18-access");
        int portA = freePort();
        startBilling("at18-billing-a", portA);
        startBilling("at18-billing-b", freePort());

        RestClient tally = RestClient.create("http://localhost:" + portA);
        List<UUID> known = new ArrayList<>();
        for (int i = 0; i < ACCOUNTS; i++) {
            known.add(signUp(tally));
        }
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).until(() -> known.stream()
                .allMatch(account -> instance.status(account) == 200
                        && Boolean.TRUE.equals(instance.answer(account).get("canWatch"))));
        Map<UUID, Map<String, Object>> before = new LinkedHashMap<>();
        known.forEach(account -> before.put(account, instance.answer(account)));

        // Every billing instance, then the billing database.
        billing.forEach(TallyProcess::kill);
        billing.clear();
        database.stop();
        assertThatThrownBy(database::connect).as("the billing database is down").isInstanceOf(Exception.class);

        for (UUID account : known) {
            assertThat(instance.answer(account)).as("known account %s", account).isEqualTo(before.get(account));
        }
        for (int i = 0; i < UNKNOWN; i++) {
            Map<String, Object> answer = instance.answer(UUID.randomUUID());
            assertThat(answer).containsEntry("canWatch", false).containsEntry("reason", "NOT_KNOWN");
        }

        // Billing again: its database, then one instance; a new sign-up reaches Access.
        database.start();
        int portC = freePort();
        startBilling("at18-billing-c", portC);
        UUID newcomer = signUp(RestClient.create("http://localhost:" + portC));
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100))
                .until(() -> Boolean.TRUE.equals(instance.answer(newcomer).get("canWatch")));
        System.out.printf("AT-18: %d known and %d unknown accounts answered with billing down; a new sign-up reached"
                + " Access after the restart%n", ACCOUNTS, UNKNOWN);
    }
}
