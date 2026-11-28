package com.tallyaccess.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tallyaccess.AccessTestSupport;
import com.tallyaccess.TestTokens;
import com.tallyaccess.application.Readiness;
import jakarta.servlet.Filter;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Partner notices at the endpoint (ADR-056, decision 7). A partner's token may POST notices and nothing else; the
 * streaming platform's token cannot post notices.
 */
@SpringBootTest
class PartnerNoticeEndpointIT {

    static final String PARTNER = "etisalat";

    @DynamicPropertySource
    static void isolate(DynamicPropertyRegistry registry) {
        AccessTestSupport.isolate(registry);
    }

    @Autowired
    WebApplicationContext context;

    @Autowired
    Readiness readiness;

    MockMvc mvc;

    @BeforeEach
    void mvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
        await().atMost(Duration.ofSeconds(20)).until(readiness::ready);
    }

    String bearer() {
        return TestTokens.partner(PARTNER).bearer();
    }

    MvcResult post(String partnerId, String bearer, String json) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/v1/partners/" + partnerId + "/notices")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)).andReturn();
    }

    static String notice(String partnerId, String bundleId, UUID subscriber, String event, long sequence,
            String occurredAt) {
        return "{\"partnerId\":\"" + partnerId + "\",\"bundleId\":\"" + bundleId + "\",\"subscriberId\":\""
                + subscriber + "\",\"event\":\"" + event + "\",\"partnerSequence\":" + sequence
                + ",\"occurredAt\":\"" + occurredAt + "\"}";
    }

    String answer(UUID subscriber) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get("/v1/access/" + subscriber)
                .header("Authorization", TestTokens.streamingPlatformBearer()))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void anActivatedNoticeIsAcceptedAndTheSubscriberCanWatch() throws Exception {
        UUID subscriber = UUID.randomUUID();

        assertThat(post(PARTNER, bearer(), notice(PARTNER, "B-1", subscriber, "ACTIVATED", 1L,
                "2026-11-27T08:00:00Z")).getResponse().getStatus()).isEqualTo(202);

        assertThat(answer(subscriber)).contains("\"canWatch\":true").contains("\"reason\":\"ACTIVE_SOURCE\"")
                .contains("\"asOf\":\"2026-11-27T08:00:00Z\"");
    }

    @Test
    void anEndedNoticeTakesTheAccessAndAnOlderOneDoesNotBringItBack() throws Exception {
        UUID subscriber = UUID.randomUUID();

        assertThat(post(PARTNER, bearer(), notice(PARTNER, "B-1", subscriber, "ACTIVATED", 1L,
                "2026-11-27T08:00:00Z")).getResponse().getStatus()).isEqualTo(202);
        assertThat(post(PARTNER, bearer(), notice(PARTNER, "B-1", subscriber, "ENDED", 2L,
                "2026-11-27T09:00:00Z")).getResponse().getStatus()).isEqualTo(202);
        assertThat(post(PARTNER, bearer(), notice(PARTNER, "B-1", subscriber, "ACTIVATED", 1L,
                "2026-11-27T08:00:00Z")).getResponse().getStatus()).isEqualTo(202);

        assertThat(answer(subscriber)).contains("\"canWatch\":false").contains("\"reason\":\"NO_ACTIVE_SOURCE\"");
    }

    @Test
    void aNoticeTheTranslatorRefusesIsA400WithTheReason() throws Exception {
        UUID subscriber = UUID.randomUUID();

        MvcResult result = post(PARTNER, bearer(), notice(PARTNER, "B-1", subscriber, "PAUSED", 1L,
                "2026-11-27T08:00:00Z"));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).contains("event is unknown: PAUSED");
    }

    @Test
    void aNoticeForAnotherPartnerThanThePathIsA400() throws Exception {
        UUID subscriber = UUID.randomUUID();

        MvcResult result = post(PARTNER, bearer(), notice("orange", "B-1", subscriber, "ACTIVATED", 1L,
                "2026-11-27T08:00:00Z"));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).contains("partnerId does not match the path");
    }

    @Test
    void aPartnerTokenOfAnotherPartnerIsRefused() throws Exception {
        UUID subscriber = UUID.randomUUID();

        MvcResult result = post(PARTNER, TestTokens.partner("orange").bearer(), notice(PARTNER, "B-1", subscriber,
                "ACTIVATED", 1L, "2026-11-27T08:00:00Z"));

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void theStreamingPlatformCannotPostAndAPartnerCannotAsk() throws Exception {
        UUID subscriber = UUID.randomUUID();

        assertThat(post(PARTNER, TestTokens.streamingPlatformBearer(), notice(PARTNER, "B-1", subscriber,
                "ACTIVATED", 1L, "2026-11-27T08:00:00Z")).getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(MockMvcRequestBuilders.get("/v1/access/" + subscriber).header("Authorization", bearer()))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void noTokenIsA401() throws Exception {
        UUID subscriber = UUID.randomUUID();

        MvcResult result = post(PARTNER, "Bearer not-a-token", notice(PARTNER, "B-1", subscriber, "ACTIVATED", 1L,
                "2026-11-27T08:00:00Z"));

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
    }
}
