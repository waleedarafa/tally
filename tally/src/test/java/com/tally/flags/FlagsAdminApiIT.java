package com.tally.flags;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.tally.AbstractIntegrationTest;
import com.tally.flags.domain.FlagCatalogue;
import com.tally.flags.domain.Flags;
import com.tally.support.TestTokens;
import jakarta.servlet.Filter;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Contract tests for the flags admin HTTP API (ADR-053): the report, the define/default/account/country
 * assignments, release and remove. Every request answers through the security filter chain, and every change
 * goes through the idempotency claim with a fresh key per change. One fresh household token per test.
 */
class FlagsAdminApiIT extends AbstractIntegrationTest {

    static final String STAFF = "staff-1";

    @Autowired
    WebApplicationContext context;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    FlagCatalogue catalogue;

    @Autowired
    Clock clock;

    MockMvc mvc;

    final String staff = TestTokens.token().subject(STAFF).scope("billing:admin").sign();

    @BeforeEach
    void mvc() {
        jdbc.update("DELETE FROM flags.flag");
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    static String key() {
        return UUID.randomUUID().toString();
    }

    String staffBearer() {
        return "Bearer " + staff;
    }

    @Test
    void noTokenIs401AndAHouseholdIs403() throws Exception {
        mvc.perform(get("/v1/admin/flags")).andExpect(status().isUnauthorized());

        String forged = TestTokens.token().forged().sign();
        mvc.perform(get("/v1/admin/flags").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/v1/admin/flags")
                .header("Authorization", TestTokens.household().bearer()))
                .andExpect(status().isForbidden());

        MvcResult out = mvc.perform(get("/v1/admin/flags").header("Authorization", staffBearer()))
                .andExpect(status().isOk())
                .andReturn();
        String body = out.getResponse().getContentAsString();
        List<?> flags = JsonPath.read(body, "$.flags");
        assertThat(flags).as("flags").isEqualTo(List.of());
        List<?> overdue = JsonPath.read(body, "$.overdue");
        assertThat(overdue).as("overdue").isEqualTo(List.of());
    }

    @Test
    void aFlagLivesThroughItsWholeLife() throws Exception {
        mvc.perform(post("/v1/admin/flags").header("Authorization", staffBearer())
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"family-bundle\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("family-bundle"))
                .andExpect(jsonPath("$.defaultOn").value(false))
                .andExpect(jsonPath("$.changeCount").value(1));

        mvc.perform(put("/v1/admin/flags/family-bundle/default")
                        .header("Authorization", staffBearer())
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"on\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultOn").value(true));

        mvc.perform(put("/v1/admin/flags/family-bundle/countries/ae")
                        .header("Authorization", staffBearer())
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"on\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.countries.AE").value(true));

        mvc.perform(put("/v1/admin/flags/family-bundle/accounts/00000000-0000-0000-0000-00000000000a")
                        .header("Authorization", staffBearer())
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"on\":true}"))
                .andExpect(status().isOk());

        mvc.perform(get("/v1/admin/flags/family-bundle").header("Authorization", staffBearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes.length()").value(4))
                .andExpect(jsonPath("$.changes[0].description").value("defined, default off"));

        mvc.perform(delete("/v1/admin/flags/family-bundle/accounts/00000000-0000-0000-0000-00000000000a")
                        .header("Authorization", staffBearer())
                        .header("Idempotency-Key", key()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts.size()").value(0));

        MvcResult released = mvc.perform(post("/v1/admin/flags/family-bundle/release")
                        .header("Authorization", staffBearer())
                        .header("Idempotency-Key", key()))
                .andExpect(status().isOk())
                .andReturn();
        String releasedBody = released.getResponse().getContentAsString();
        String releasedAt = (String) JsonPath.read(releasedBody, "$.releasedAt");
        String removeBy = (String) JsonPath.read(releasedBody, "$.removeBy");
        assertThat(releasedAt).as("releasedAt").isNotNull();
        assertThat(removeBy).as("removeBy").isNotNull();
        assertThat(Instant.parse(removeBy))
                .isEqualTo(Instant.parse(releasedAt).plus(30, ChronoUnit.DAYS));

        mvc.perform(post("/v1/admin/flags/family-bundle/release")
                        .header("Authorization", staffBearer())
                        .header("Idempotency-Key", key()))
                .andExpect(status().isConflict());

        mvc.perform(put("/v1/admin/flags/family-bundle/default")
                        .header("Authorization", staffBearer())
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"on\":false}"))
                .andExpect(status().isConflict());

        mvc.perform(delete("/v1/admin/flags/family-bundle")
                        .header("Authorization", staffBearer())
                        .header("Idempotency-Key", key()))
                .andExpect(status().isNoContent());

        mvc.perform(get("/v1/admin/flags/family-bundle").header("Authorization", staffBearer()))
                .andExpect(status().isNotFound());

        MvcResult out = mvc.perform(get("/v1/admin/flags").header("Authorization", staffBearer()))
                .andExpect(status().isOk())
                .andReturn();
        List<?> flags = JsonPath.read(out.getResponse().getContentAsString(), "$.flags");
        assertThat(flags).as("flags").isEqualTo(List.of());
    }

    @Test
    void badRequests() throws Exception {
        mvc.perform(post("/v1/admin/flags").header("Authorization", staffBearer())
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Bad Name\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/v1/admin/flags").header("Authorization", staffBearer())
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/v1/admin/flags").header("Authorization", staffBearer())
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\"}"))
                .andExpect(status().isCreated());

        mvc.perform(put("/v1/admin/flags/x/countries/ARE").header("Authorization", staffBearer())
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"on\":true}"))
                .andExpect(status().isBadRequest());

        mvc.perform(put("/v1/admin/flags/x/default").header("Authorization", staffBearer())
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        mvc.perform(put("/v1/admin/flags/x/accounts/not-a-uuid").header("Authorization", staffBearer())
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"on\":true}"))
                .andExpect(status().isBadRequest());

        mvc.perform(put("/v1/admin/flags/unknown/default").header("Authorization", staffBearer())
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"on\":true}"))
                .andExpect(status().isNotFound());

        mvc.perform(post("/v1/admin/flags").header("Authorization", staffBearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"valid-name\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void keysReplayAndConflict() throws Exception {
        String keyK = key();

        MvcResult first = mvc.perform(post("/v1/admin/flags").header("Authorization", staffBearer())
                        .header("Idempotency-Key", keyK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"once\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        MvcResult replay = mvc.perform(post("/v1/admin/flags").header("Authorization", staffBearer())
                        .header("Idempotency-Key", keyK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"once\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(replay.getResponse().getContentAsString())
                .as("replay returns the stored body byte for byte")
                .isEqualTo(first.getResponse().getContentAsString());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM flags.flag WHERE name = 'once'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flags.flag_change WHERE flag_name = 'once'", Integer.class))
                .isEqualTo(1);

        mvc.perform(post("/v1/admin/flags").header("Authorization", staffBearer())
                        .header("Idempotency-Key", keyK)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"other\"}"))
                .andExpect(status().isUnprocessableEntity());

        mvc.perform(post("/v1/admin/flags").header("Authorization", staffBearer())
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"once\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void theReportListsOverdueFlags() throws Exception {
        Instant now = clock.instant();

        Flags flags = new Flags();
        flags.define("old-one", now.minus(60, ChronoUnit.DAYS));
        flags.setDefault("old-one", true, now.minus(59, ChronoUnit.DAYS));
        flags.release("old-one", now.minus(31, ChronoUnit.DAYS));
        flags.define("fresh", now.minus(20, ChronoUnit.DAYS));
        flags.setDefault("fresh", true, now.minus(19, ChronoUnit.DAYS));
        flags.release("fresh", now.minus(10, ChronoUnit.DAYS));
        flags.define("plain", now.minus(10, ChronoUnit.DAYS));
        catalogue.save(flags);

        String body = mvc.perform(get("/v1/admin/flags").header("Authorization", staffBearer()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<String> names = JsonPath.read(body, "$.flags[*].name");
        assertThat(names).as("flags in name order").isEqualTo(List.of("fresh", "old-one", "plain"));
        List<String> overdue = JsonPath.read(body, "$.overdue");
        assertThat(overdue).as("overdue flags").isEqualTo(List.of("old-one"));

        List<Map<String, Object>> reports = JsonPath.read(body, "$.flags");
        Map<String, Map<String, Object>> byName = new HashMap<>();
        for (Map<String, Object> flag : reports) {
            byName.put((String) flag.get("name"), flag);
        }
        assertThat((Boolean) byName.get("old-one").get("overdue")).isEqualTo(true);
        assertThat((Boolean) byName.get("fresh").get("overdue")).isEqualTo(false);
        assertThat(byName.get("plain").get("releasedAt")).isNull();
        assertThat(Instant.parse((String) JsonPath.read(body, "$.asOf")))
                .as("asOf").isEqualTo(clock.instant());
    }
}
