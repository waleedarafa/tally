package com.tallyaccess.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.mockmvc.MockMvcRequest;
import com.atlassian.oai.validator.mockmvc.MockMvcResponse;
import com.atlassian.oai.validator.model.Request.Method;
import com.atlassian.oai.validator.report.ValidationReport;
import com.tallyaccess.AccessTestSupport;
import com.tallyaccess.application.AccessCopy;
import com.tallyaccess.application.Readiness;
import io.swagger.v3.parser.OpenAPIV3Parser;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Access's contract test (ADR-019; brief PR-6): every operation in {@code docs/api/access-openapi.yaml}, its response
 * as the application wrote it, checked against the specification, as billing's {@code ContractIT} does.
 */
@SpringBootTest
class AccessContractIT {

    static final Path SPECIFICATION = Path.of("..", "docs", "api", "access-openapi.yaml").toAbsolutePath().normalize();
    static final OpenApiInteractionValidator VALIDATOR = OpenApiInteractionValidator
            .createForSpecificationUrl(SPECIFICATION.toUri().toString()).build();
    static final Set<String> TESTED = Set.of("GET /v1/access/{subscriberId}");

    static String topic;
    static final KafkaProducer<String, String> PRODUCER = AccessTestSupport.producer();

    @DynamicPropertySource
    static void isolate(DynamicPropertyRegistry registry) {
        topic = AccessTestSupport.isolate(registry);
    }

    @AfterAll
    static void close() {
        PRODUCER.close();
    }

    @Autowired
    WebApplicationContext context;

    @Autowired
    AccessCopy copy;

    @Autowired
    Readiness readiness;

    MockMvc mvc;

    @BeforeEach
    void mvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        await().atMost(Duration.ofSeconds(20)).until(readiness::ready);
    }

    MvcResult valid(String uri, int status) throws Exception {
        MvcResult result = mvc.perform(get(uri)).andReturn();
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);
        assertConforms(VALIDATOR.validate(MockMvcRequest.of(result.getRequest()),
                MockMvcResponse.of(result.getResponse())));
        return result;
    }

    static void assertConforms(ValidationReport report) {
        assertThat(report.getMessages()).as("Contract violations (docs/api/access-openapi.yaml)").isEmpty();
    }

    @Test
    void theOperationsTestedAreExactlyTheOperationsSpecified() {
        Set<String> specified = new TreeSet<>();
        new OpenAPIV3Parser().read(SPECIFICATION.toString()).getPaths().forEach((path, item) ->
                item.readOperationsMap().keySet().forEach(method -> specified.add(method + " " + path)));

        assertThat(specified).containsExactlyInAnyOrderElementsOf(TESTED);
    }

    @Test
    void aKnownSubscriber() throws Exception {
        UUID account = UUID.randomUUID();
        AccessTestSupport.sendCommitted(PRODUCER, topic, account, AccessTestSupport.facts(account, 7, "GOOD", "ACTIVE"));
        await().atMost(Duration.ofSeconds(20)).until(() -> copy.answer(account).sequence() != null);

        String body = valid("/v1/access/" + account, 200).getResponse().getContentAsString();
        assertThat(body).contains("\"canWatch\":true").contains("\"reason\":\"ACTIVE_SOURCE\"")
                .contains("\"sequence\":7").contains("\"asOf\":\"2026-11-13T06:00:07Z\"");
    }

    @Test
    void aBlockedSubscriber() throws Exception {
        UUID account = UUID.randomUUID();
        AccessTestSupport.sendCommitted(PRODUCER, topic, account, AccessTestSupport.facts(account, 3, "BLOCKED",
                "ACTIVE"));
        await().atMost(Duration.ofSeconds(20)).until(() -> copy.answer(account).sequence() != null);

        assertThat(valid("/v1/access/" + account, 200).getResponse().getContentAsString())
                .contains("\"canWatch\":false").contains("\"reason\":\"ACCOUNT_BLOCKED\"");
    }

    @Test
    void aSubscriberAccessDoesNotKnow() throws Exception {
        assertThat(valid("/v1/access/" + UUID.randomUUID(), 200).getResponse().getContentAsString())
                .contains("\"canWatch\":false").contains("\"reason\":\"NOT_KNOWN\"").contains("\"asOf\":null")
                .contains("\"sequence\":null");
    }

    @Test
    void anIdThatIsNotAUuid() throws Exception {
        MvcResult result = mvc.perform(get("/v1/access/not-a-uuid")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertConforms(VALIDATOR.validateResponse("/v1/access/not-a-uuid", Method.GET,
                MockMvcResponse.of(result.getResponse())));
    }
}
