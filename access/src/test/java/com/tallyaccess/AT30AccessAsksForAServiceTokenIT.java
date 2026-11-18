package com.tallyaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.client.RestClient;

/**
 * AT-30. Access asks for a service token (Slice 4 brief, decision 1, option A; ADR-039, option A for Access). A service
 * token with the right audience and scope gets the answer; no token gets 401; a household's token gets 403. Access
 * answers while the identity provider is down, as far as the keys it kept allow: a running instance, and one that
 * starts during the outage, answer the platform's service token while it lives, and refuse it once it expires.
 *
 * <p>The service token lives one hour, a real lifetime (review of #88): it is the number D13 depends on, since the
 * platform cannot get a new one while the provider is down. The test clock moves past its end.
 */
class AT30AccessAsksForAServiceTokenIT {

    /** The streaming platform's service token lives this long (review of #88; ADR-039's limit). */
    static final Duration SERVICE_TOKEN_LIFETIME = Duration.ofHours(1);

    final List<ConfigurableApplicationContext> started = new ArrayList<>();
    final MovableClock clock = new MovableClock();

    @AfterEach
    void stop() {
        started.forEach(ConfigurableApplicationContext::close);
        TestTokens.providerUp(true);
    }

    ConfigurableApplicationContext start(Map<String, String> settings) {
        Map<String, String> all = new LinkedHashMap<>(settings);
        all.put("server.port", "0");
        MovableClock.Configuration.next = clock;
        ConfigurableApplicationContext context = new SpringApplicationBuilder(AccessApplication.class,
                MovableClock.Configuration.class)
                .run(all.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new));
        started.add(context);
        return context;
    }

    static int status(ConfigurableApplicationContext instance, String authorization) {
        String port = instance.getEnvironment().getProperty("local.server.port");
        var request = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(code -> true, (r, response) -> { }).build()
                .get().uri("/v1/access/{id}", UUID.randomUUID());
        if (authorization != null) {
            request = request.header("Authorization", authorization);
        }
        return request.retrieve().toBodilessEntity().getStatusCode().value();
    }

    String serviceToken() {
        return TestTokens.token().valid(clock.instant(), SERVICE_TOKEN_LIFETIME).bearer();
    }

    ConfigurableApplicationContext ready(Map<String, String> settings) {
        ConfigurableApplicationContext instance = start(settings);
        await().atMost(Duration.ofSeconds(30)).until(() -> status(instance, serviceToken()) == 200);
        return instance;
    }

    @Test
    void onlyTheStreamingPlatformsServiceTokenGetsTheAnswer() {
        ConfigurableApplicationContext access = ready(AccessTestSupport.isolated());

        assertThat(status(access, serviceToken())).as("the streaming platform's service token").isEqualTo(200);
        assertThat(status(access, null)).as("no token").isEqualTo(401);
        assertThat(status(access, TestTokens.household().bearer())).as("a household's token").isEqualTo(403);
        assertThat(status(access, TestTokens.token().scope(null).bearer())).as("Access's audience, no scope")
                .isEqualTo(403);
        assertThat(status(access, TestTokens.token().audience("someone-else").bearer())).as("another audience")
                .isEqualTo(401);
        assertThat(status(access, TestTokens.token().forged().bearer())).as("a bad signature").isEqualTo(401);
    }

    @Test
    void duringAnOutageAccessAnswersWhileTheServiceTokenLivesAndRefusesItAfter() {
        Map<String, String> settings = AccessTestSupport.isolated();
        ConfigurableApplicationContext running = ready(settings);
        String token = serviceToken();
        assertThat(status(running, token)).isEqualTo(200);

        TestTokens.providerUp(false);
        int fetches = TestTokens.keyFetches();

        assertThat(status(running, token)).as("a running instance, the provider down").isEqualTo(200);
        ConfigurableApplicationContext startedDuringTheOutage = ready(settings);
        assertThat(status(startedDuringTheOutage, token))
                .as("an instance started during the outage, from the key set kept in Access's database")
                .isEqualTo(200);
        assertThat(TestTokens.keyFetches()).as("no key set was fetched during the outage").isEqualTo(fetches);

        clock.advance(SERVICE_TOKEN_LIFETIME.plusSeconds(61));

        assertThat(status(running, token)).as("the token has expired: the running instance").isEqualTo(401);
        assertThat(status(startedDuringTheOutage, token)).as("the token has expired: the new instance")
                .isEqualTo(401);
        assertThat(status(running, serviceToken())).as("a token issued now would answer: the keys are not the cause")
                .isEqualTo(200);
    }

    /**
     * The limit of option A: an instance with no key set kept, started during an outage, cannot check a token. It
     * answers 503, not 401: the token is not at fault, so the platform keeps it and asks again.
     */
    @Test
    void anInstanceThatNeverFetchedTheKeysAnswers503DuringAnOutage() {
        TestTokens.providerUp(false);
        ConfigurableApplicationContext access = start(AccessTestSupport.isolated());

        assertThat(status(access, serviceToken())).isEqualTo(503);

        TestTokens.providerUp(true);
        await().atMost(Duration.ofSeconds(30)).until(() -> status(access, serviceToken()) == 200);
    }
}
