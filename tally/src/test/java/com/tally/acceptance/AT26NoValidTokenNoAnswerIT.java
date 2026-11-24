package com.tally.acceptance;

import static org.assertj.core.api.Assertions.assertThat;

import com.tally.AbstractIntegrationTest;
import com.tally.support.TestTokens;
import io.swagger.v3.parser.OpenAPIV3Parser;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

/**
 * AT-26. No valid token, no answer (Slice 4 brief; ADR-032). For every operation in {@code docs/api/openapi.yaml}: no
 * token, a bad signature, an expired token (the clock moved), a wrong issuer and a wrong audience each get 401, and
 * nothing changes. The operations come from the contract, so a new endpoint is covered at once.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AT26NoValidTokenNoAnswerIT extends AbstractIntegrationTest {

    static final Path SPECIFICATION = Path.of("..", "docs", "api", "openapi.yaml").toAbsolutePath().normalize();
    static final List<String> SCHEMAS = List.of("account", "subscription", "invoicing", "payment", "collections",
            "accessfeed");

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    record Operation(HttpMethod method, String path) {
        /** The path with each parameter filled by a new UUID. */
        String uri() {
            return path.replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
        }
    }

    static List<Operation> operations() {
        List<Operation> operations = new ArrayList<>();
        new OpenAPIV3Parser().read(SPECIFICATION.toString()).getPaths().forEach((path, item) ->
                item.readOperationsMap().keySet().forEach(method ->
                        operations.add(new Operation(HttpMethod.valueOf(method.name()), path))));
        return operations;
    }

    /** The row count of every table of the business schemas. */
    Map<String, Long> rows() {
        Map<String, Long> rows = new TreeMap<>();
        jdbc.queryForList("SELECT table_schema || '.' || table_name FROM information_schema.tables"
                + " WHERE table_type = 'BASE TABLE' AND table_schema = ANY (?)", String.class,
                (Object) SCHEMAS.toArray(String[]::new))
                .forEach(table -> rows.put(table, jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class)));
        return rows;
    }

    int send(Operation operation, String authorization) {
        RestClient.RequestBodySpec request = RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (r, response) -> { }).build()
                .method(operation.method()).uri(operation.uri())
                .header("Idempotency-Key", UUID.randomUUID().toString());
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        if (operation.method() == HttpMethod.POST) {
            request.contentType(MediaType.APPLICATION_JSON)
                    .body("{\"currency\":\"USD\",\"timeZone\":\"UTC\",\"country\":\"US\",\"planId\":\"STANDARD\","
                            + "\"paymentToken\":\"tok_at26\",\"accountId\":\"" + UUID.randomUUID() + "\"}");
        }
        ResponseEntity<Void> response = request.retrieve().toBodilessEntity();
        return response.getStatusCode().value();
    }

    @Test
    void everyOperationAnswers401ToEachKindOfBadTokenAndNothingChanges() {
        List<Operation> operations = operations();
        assertThat(operations).as("the operations in openapi.yaml").hasSizeGreaterThanOrEqualTo(6);
        Map<String, Supplier<String>> badTokens = new LinkedHashMap<>();
        badTokens.put("no token", () -> null);
        badTokens.put("a bad signature", () -> "Bearer " + TestTokens.token().forged().sign());
        badTokens.put("a wrong issuer", () -> "Bearer " + TestTokens.token().issuer("https://elsewhere.test").sign());
        badTokens.put("a wrong audience", () -> "Bearer " + TestTokens.token().audience(TestTokens.ACCESS).sign());
        badTokens.put("not a token", () -> "Bearer not-a-token");
        Map<String, Long> before = rows();

        for (Operation operation : operations) {
            badTokens.forEach((kind, token) -> assertThat(send(operation, token.get()))
                    .as("%s %s with %s", operation.method(), operation.path(), kind).isEqualTo(401));
        }

        assertThat(rows()).as("rows of the business schemas").isEqualTo(before);
    }

    /** The clock moves past the token's end: 401, by the injected clock (brief rule 9; spike S-1). */
    @Test
    void anExpiredTokenAnswers401AfterTheClockMovesAndNothingChanges() {
        String token = "Bearer " + TestTokens.token().valid(clock.instant(), Duration.ofMinutes(5)).sign();
        Operation me = new Operation(HttpMethod.GET, "/v1/me/account");
        assertThat(send(me, token)).as("while the token lives").isEqualTo(404);
        Map<String, Long> before = rows();

        clock.advance(Duration.ofMinutes(5).plusSeconds(61));

        for (Operation operation : operations()) {
            assertThat(send(operation, token)).as("%s %s with an expired token", operation.method(),
                    operation.path()).isEqualTo(401);
        }
        assertThat(rows()).as("rows of the business schemas").isEqualTo(before);
    }

    /** A valid token reaches the operation: the check refuses only what it should. */
    @Test
    void aValidTokenIsNotRefused() {
        String token = TestTokens.household().bearer();

        for (Operation operation : operations()) {
            assertThat(send(operation, token)).as("%s %s with a valid token", operation.method(), operation.path())
                    .isNotEqualTo(401);
        }
    }
}
