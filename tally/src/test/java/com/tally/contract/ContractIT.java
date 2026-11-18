package com.tally.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.mockmvc.MockMvcRequest;
import com.atlassian.oai.validator.mockmvc.MockMvcResponse;
import com.atlassian.oai.validator.model.Request.Method;
import com.atlassian.oai.validator.report.ValidationReport;
import com.tally.AbstractIntegrationTest;
import com.tally.account.AccountApi;
import com.tally.account.AccountStanding;
import com.tally.account.Standing;
import com.tally.collections.Collections;
import com.tally.invoicing.InvoicePaymentDeclined;
import com.tally.payment.PaymentApi;
import com.tally.shared.Money;
import com.tally.support.TestAccounts;
import com.tally.support.TestTokens;
import io.swagger.v3.oas.models.PathItem.HttpMethod;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import jakarta.servlet.Filter;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Contract tests (ADR-019; PR #23 review, decision 5): every operation in {@code docs/api/openapi.yaml}, request
 * and response, against the running application. Requests go through MockMvc as raw JSON; responses are the
 * raw bytes the application's own message converters (Jackson 3) wrote. The validator (test scope only) checks
 * those bytes against the specification; it never maps objects itself.
 *
 * <p>A request the test sends invalid on purpose (no key, missing fields) is checked on its response only: the
 * specification says what the client must send, and the refusal is what it must get back.
 */
class ContractIT extends AbstractIntegrationTest {

    static final Path SPECIFICATION = Path.of("..", "docs", "api", "openapi.yaml").toAbsolutePath().normalize();

    /** Every operation this class tests; it must be exactly the operations in the specification. */
    static final Set<String> TESTED = Set.of(
            "POST /v1/accounts", "GET /v1/accounts/{id}", "GET /v1/me/account",
            "GET /v1/payments/{reference}/status", "POST /v1/subscriptions",
            "POST /v1/accounts/{accountId}/collections/payment-requests");

    static final OpenApiInteractionValidator VALIDATOR = OpenApiInteractionValidator
            .createForSpecificationUrl(SPECIFICATION.toUri().toString()).build();

    @Autowired
    WebApplicationContext context;

    @Autowired
    AccountApi accounts;

    @Autowired
    TestAccounts testAccounts;

    @Autowired
    AccountStanding standing;

    @Autowired
    PaymentApi payments;

    @Autowired
    JdbcTemplate jdbc;

    MockMvc mvc;

    /** The caller of every request of one test. */
    final TestTokens.Household household = TestTokens.household();

    @BeforeEach
    void mvc() {
        // One household's token on every request; the server's security filters run in MockMvc too (Slice 4).
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .defaultRequest(get("/").header("Authorization", household.bearer()))
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    MvcResult exchange(RequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn();
    }

    /** The request and the response both conform. Returns the result for further checks. */
    MvcResult valid(RequestBuilder request, int status) throws Exception {
        MvcResult result = exchange(request);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);
        assertConforms(VALIDATOR.validate(MockMvcRequest.of(result.getRequest()),
                MockMvcResponse.of(result.getResponse())));
        return result;
    }

    /** A request sent invalid on purpose: its response conforms. */
    void validResponse(MockHttpServletRequestBuilder request, Method method, int status) throws Exception {
        MvcResult result = exchange(request);
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);
        assertConforms(VALIDATOR.validateResponse(result.getRequest().getRequestURI(), method,
                MockMvcResponse.of(result.getResponse())));
    }

    static void assertConforms(ValidationReport report) {
        assertThat(report.getMessages()).as("Contract violations (docs/api/openapi.yaml)").isEmpty();
    }

    static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    static String key() {
        return UUID.randomUUID().toString();
    }

    /** Each account's household: a request about an account carries its owner's token (Slice 4 PR-4). */
    final Map<UUID, TestTokens.Household> owners = new HashMap<>();

    /** A new household's account. */
    UUID account() {
        TestTokens.Household owner = TestTokens.household();
        UUID account = testAccounts.openFor(owner, Currency.getInstance("USD"), ZoneId.of("UTC")).id();
        owners.put(account, owner);
        return account;
    }

    /** {@code request}, sent by the household of {@code account}. */
    MockHttpServletRequestBuilder by(UUID account, MockHttpServletRequestBuilder request) {
        return request.header("Authorization", owners.get(account).bearer());
    }

    String signUp(UUID account, String planId) {
        return "{\"accountId\":\"" + account + "\",\"planId\":\"" + planId + "\",\"paymentToken\":\"tok_" + account + "\"}";
    }

    /** Simulates a request still in flight with {@code key}: its response not stored, claimed just now. */
    void inFlight(String table, String key) {
        jdbc.update("UPDATE " + table + " SET response_status = NULL, response_content_type = NULL,"
                + " response_body = NULL, claimed_at = ? WHERE key = ?", clock.instant().atOffset(ZoneOffset.UTC), key);
    }

    @Test
    void theOperationsTestedAreExactlyTheOperationsSpecified() {
        Set<String> specified = new TreeSet<>();
        new OpenAPIV3Parser().read(SPECIFICATION.toString()).getPaths().forEach((path, item) ->
                item.readOperationsMap().keySet().forEach(method -> specified.add(method + " " + path)));

        assertThat(specified).containsExactlyInAnyOrderElementsOf(TESTED);
    }

    /** No endpoint ships undocumented: every /v1 handler in the application is in the specification. */
    @Test
    void everyEndpointOfTheApplicationIsSpecified() {
        Set<String> served = new TreeSet<>();
        context.getBean("requestMappingHandlerMapping", RequestMappingHandlerMapping.class).getHandlerMethods()
                .keySet().forEach(info -> info.getPatternValues().stream().filter(p -> p.startsWith("/v1/"))
                        .forEach(path -> info.getMethodsCondition().getMethods()
                                .forEach(method -> served.add(method + " " + path))));

        assertThat(served).containsExactlyInAnyOrderElementsOf(TESTED);
    }

    /**
     * Every operation that changes state requires an Idempotency-Key that is a UUID (ADR-019; #24 review). Loosening
     * the rule later is additive; tightening it later would break clients.
     */
    @Test
    void everyStateChangingOperationRequiresAUuidKey() {
        Set<String> keyed = new TreeSet<>();
        Set<String> changing = new TreeSet<>();
        ParseOptions resolved = new ParseOptions();
        resolved.setResolve(true);
        resolved.setResolveFully(true);
        new OpenAPIV3Parser().read(SPECIFICATION.toString(), null, resolved).getPaths().forEach((path, item) ->
                item.readOperationsMap().forEach((method, operation) -> {
                    if (method != HttpMethod.GET) {
                        changing.add(method + " " + path);
                    }
                    for (Parameter parameter : operation.getParameters() == null ? List.<Parameter>of()
                            : operation.getParameters()) {
                        if ("header".equals(parameter.getIn()) && "Idempotency-Key".equals(parameter.getName())
                                && Boolean.TRUE.equals(parameter.getRequired())
                                && "uuid".equals(parameter.getSchema().getFormat())) {
                            keyed.add(method + " " + path);
                        }
                    }
                }));

        assertThat(changing).isNotEmpty();
        assertThat(keyed).isEqualTo(changing);
    }

    /** A key that is not a UUID is refused with a 400 problem, on every operation that takes one, and not stored. */
    @Test
    void aKeyThatIsNotAUuidIsRefusedAndNotStored() throws Exception {
        String key = "not-a-uuid-" + UUID.randomUUID();
        MvcResult account = exchange(json(post("/v1/accounts").header("Idempotency-Key", key),
                "{\"currency\":\"USD\",\"timeZone\":\"UTC\"}"));
        UUID owned = account();
        MvcResult signUp = exchange(by(owned, json(post("/v1/subscriptions").header("Idempotency-Key", key),
                signUp(owned, "BASIC"))));

        for (MvcResult result : List.of(account, signUp)) {
            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(result.getResponse().getContentAsString()).contains("UUID");
            assertConforms(VALIDATOR.validateResponse(result.getRequest().getRequestURI(), Method.POST,
                    MockMvcResponse.of(result.getResponse())));
            assertThat(VALIDATOR.validateRequest(MockMvcRequest.of(result.getRequest())).getMessages())
                    .as("the specification itself refuses the key")
                    .anySatisfy(message -> assertThat(message.getMessage()).contains("uuid"));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM account.idempotency_key WHERE key = ?", Integer.class,
                key)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM subscription.idempotency_key WHERE key = ?",
                Integer.class, key)).isZero();
    }

    /** Requests are checked too: one without its required Idempotency-Key does not conform. */
    @Test
    void theValidatorChecksRequestsAsWellAsResponses() throws Exception {
        MvcResult result = exchange(json(post("/v1/accounts"), "{\"currency\":\"USD\",\"timeZone\":\"UTC\"}"));

        assertThat(VALIDATOR.validateRequest(MockMvcRequest.of(result.getRequest())).getMessages())
                .anySatisfy(message -> assertThat(message.getMessage()).contains("Idempotency-Key"));
    }

    @Test
    void openAccount() throws Exception {
        String body = "{\"currency\":\"USD\",\"timeZone\":\"Europe/London\"}";
        String key = key();
        valid(json(post("/v1/accounts").header("Idempotency-Key", key), body), 201);
        valid(json(post("/v1/accounts").header("Idempotency-Key", key), "{\"currency\":\"AED\",\"timeZone\":\"UTC\"}"),
                422);
        // The user has an account: a new key answers 200 with it (WAR-84).
        valid(json(post("/v1/accounts").header("Idempotency-Key", key()), body), 200);
        valid(json(post("/v1/accounts").header("Idempotency-Key", key()), "{\"currency\":\"XX\",\"timeZone\":\"UTC\"}"),
                400);
        inFlight("account.idempotency_key", key);
        MvcResult inProgress = valid(json(post("/v1/accounts").header("Idempotency-Key", key), body), 409);
        assertThat(inProgress.getResponse().getHeader("Retry-After")).isNotBlank();
        validResponse(json(post("/v1/accounts"), body), Method.POST, 400);
    }

    @Test
    void getMyAccount() throws Exception {
        valid(get("/v1/me/account"), 404);
        valid(json(post("/v1/accounts").header("Idempotency-Key", key()), "{\"currency\":\"USD\",\"timeZone\":\"UTC\"}"),
                201);
        valid(get("/v1/me/account"), 200);
    }

    /** Every operation's 401, to a request with no valid token, conforms (Slice 4, ADR-032). */
    @Test
    void everyOperationsUnauthenticatedAnswerConforms() throws Exception {
        for (String operation : TESTED) {
            String method = operation.substring(0, operation.indexOf(' '));
            String path = operation.substring(operation.indexOf(' ') + 1).replaceAll("\\{[^}]+}",
                    UUID.randomUUID().toString());
            MockHttpServletRequestBuilder request = "POST".equals(method) ? json(post(path), "{}") : get(path);
            MvcResult result = exchange(request.header("Authorization", "Bearer not-a-token"));
            assertThat(result.getResponse().getStatus()).as(operation).isEqualTo(401);
            assertConforms(VALIDATOR.validateResponse(path, Method.valueOf(method),
                    MockMvcResponse.of(result.getResponse())));
        }
    }

    @Test
    void getAccount() throws Exception {
        UUID owned = account();
        valid(by(owned, get("/v1/accounts/{id}", owned)), 200);
        valid(get("/v1/accounts/{id}", owned), 404);
        valid(get("/v1/accounts/{id}", UUID.randomUUID()), 404);
    }

    @Test
    void getPaymentStatus() throws Exception {
        String reference = "CONTRACT-" + UUID.randomUUID();
        UUID account = account();
        payments.requestCollection(reference, account, Money.of(999, "USD"), "tok");
        valid(by(account, get("/v1/payments/{reference}/status", reference)), 200);
        valid(get("/v1/payments/{reference}/status", reference), 404);
        valid(get("/v1/payments/{reference}/status", "NO-SUCH-" + UUID.randomUUID()), 404);
    }

    @Test
    void signUp() throws Exception {
        UUID account = account();
        String key = key();
        valid(by(account, json(post("/v1/subscriptions").header("Idempotency-Key", key), signUp(account, "BASIC"))),
                201);
        valid(by(account, json(post("/v1/subscriptions").header("Idempotency-Key", key), signUp(account, "PREMIUM"))),
                422);
        valid(by(account, json(post("/v1/subscriptions").header("Idempotency-Key", key()), signUp(account, "BASIC"))),
                409);
        UUID other = account();
        valid(by(other, json(post("/v1/subscriptions").header("Idempotency-Key", key()), signUp(other, "NO-SUCH-PLAN"))),
                422);
        valid(json(post("/v1/subscriptions").header("Idempotency-Key", key()), signUp(UUID.randomUUID(), "BASIC")),
                404);
        // Another household's account answers as one that does not exist (rule 16).
        valid(json(post("/v1/subscriptions").header("Idempotency-Key", key()), signUp(other, "BASIC")), 404);
        UUID blocked = account();
        standing.set(blocked, Standing.BLOCKED, "ContractIT");
        valid(by(blocked, json(post("/v1/subscriptions").header("Idempotency-Key", key()), signUp(blocked, "BASIC"))),
                409);
        inFlight("subscription.idempotency_key", key);
        MvcResult inProgress = valid(by(account, json(post("/v1/subscriptions").header("Idempotency-Key", key),
                signUp(account, "BASIC"))), 409);
        assertThat(inProgress.getResponse().getHeader("Retry-After")).isNotBlank();
        UUID unkeyed = account();
        validResponse(by(unkeyed, json(post("/v1/subscriptions"), signUp(unkeyed, "BASIC"))), Method.POST, 400);
        validResponse(json(post("/v1/subscriptions").header("Idempotency-Key", key()), "{\"planId\":\"BASIC\"}"),
                Method.POST, 400);
    }

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    TransactionTemplate transactions;

    @Autowired
    Collections collections;

    /** "Pay now" (Slice 3 PR-5): 202 while a debt is open, the same answer for the same key, 404 with none. */
    @Test
    void payNow() throws Exception {
        UUID account = account();
        UUID invoice = UUID.randomUUID();
        transactions.executeWithoutResult(tx -> events.publishEvent(new InvoicePaymentDeclined(UUID.randomUUID(),
                clock.instant(), account, invoice, "ref-" + invoice, Money.of(1599, "USD"), 2L)));
        await().atMost(Duration.ofSeconds(15)).until(() -> collections.caseFor(account).isPresent());
        String path = "/v1/accounts/{accountId}/collections/payment-requests";
        String key = key();

        MvcResult asked = valid(by(account, post(path, account).header("Idempotency-Key", key)), 202);
        MvcResult replayed = valid(by(account, post(path, account).header("Idempotency-Key", key)), 202);
        assertThat(replayed.getResponse().getContentAsString()).isEqualTo(asked.getResponse().getContentAsString());
        UUID debtFree = account();
        valid(by(debtFree, post(path, debtFree).header("Idempotency-Key", key())), 404);
        valid(post(path, UUID.randomUUID()).header("Idempotency-Key", key()), 404);
        // Another household's debt answers as no debt (rule 16).
        valid(post(path, account).header("Idempotency-Key", key()), 404);
        inFlight("collections.idempotency_key", key);
        MvcResult inProgress = valid(by(account, post(path, account).header("Idempotency-Key", key)), 409);
        assertThat(inProgress.getResponse().getHeader("Retry-After")).isNotBlank();
        validResponse(by(account, post(path, account)), Method.POST, 400);
    }
}
