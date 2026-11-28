package com.tally.flags;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tally.AbstractIntegrationTest;
import jakarta.servlet.Filter;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The flags admin screen (ADR-055): two static files, served without a token, that hold no data and call only the admin API.
 */
class FlagsAdminScreenIT extends AbstractIntegrationTest {

    @Autowired
    WebApplicationContext context;

    MockMvc mvc;

    @BeforeEach
    void mvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    @Test
    void thePageIsServedWithoutAToken() throws Exception {
        MvcResult page = mvc.perform(get("/admin/flags/index.html"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(page.getResponse().getContentType())
                .as("content type").contains("text/html");

        String body = page.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).as("page loads the script")
                .contains("<script src=\"flags.js\" defer></script>");
        assertThat(body).as("page holds the token field").contains("id=\"token\"");
        assertThat(body.split("<script", -1).length - 1)
                .as("page has exactly one script tag").isEqualTo(1);
    }

    @Test
    void theScriptIsServedWithoutAToken() throws Exception {
        MvcResult script = mvc.perform(get("/admin/flags/flags.js"))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(script.getResponse().getContentType())
                .as("content type").contains("javascript");

        String body = script.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).as("script calls the admin API").contains("/v1/admin/flags");
        assertThat(body).as("script sends an idempotency key").contains("Idempotency-Key");
        assertThat(body).as("script sends a bearer header").contains("Bearer ");
        assertThat(body).as("script generates fresh idempotency keys").contains("crypto.randomUUID()");
    }

    @Test
    void theScriptKeepsNoTokenAndWritesNoMarkup() throws Exception {
        String body = mvc.perform(get("/admin/flags/flags.js"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).as("no localStorage").doesNotContain("localStorage");
        assertThat(body).as("no sessionStorage").doesNotContain("sessionStorage");
        assertThat(body).as("no document.cookie").doesNotContain("document.cookie");
        assertThat(body).as("no innerHTML").doesNotContain("innerHTML");
    }

    @Test
    void theScreenIsAtItsAddress() throws Exception {
        MvcResult direct = mvc.perform(get("/admin/flags/index.html"))
                .andExpect(status().isOk())
                .andReturn();
        String directBody = direct.getResponse().getContentAsString(StandardCharsets.UTF_8);

        MvcResult atAddress = mvc.perform(get("/admin/flags/"))
                .andExpect(status().isOk())
                .andReturn();
        String forwardedUrl = atAddress.getResponse().getForwardedUrl();
        if (forwardedUrl != null) {
            assertThat(forwardedUrl).as("forwarded to the static page").isEqualTo("/admin/flags/index.html");
        } else {
            assertThat(atAddress.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .as("the screen at its address is the page").isEqualTo(directBody);
        }

        mvc.perform(get("/admin/flags"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/flags/"));
    }

    @Test
    void theApiStillNeedsAToken() throws Exception {
        mvc.perform(get("/v1/admin/flags")).andExpect(status().isUnauthorized());
    }
}
