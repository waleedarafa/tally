package com.tally.support;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.springframework.web.client.RestClient;

/**
 * The test token issuer (Slice 4 PR-2): a test key pair, and tokens signed with it for a chosen user, audience,
 * scope and expiry. It stands in for the identity provider, which no test reaches (ADR-032; brief rule 11: no
 * product is chosen).
 *
 * <p>A household's token has the user as its subject and billing's audience. A service token has the client as its
 * subject, Access's audience and a scope (decision 1 of the brief, option A).
 *
 * <p>A token is valid from one day before {@code TestClock}'s start for ten years, so it outlives every test that
 * moves the clock (AT-22 moves it 33 days) and the system clock that out-of-process instances use. A test of expiry
 * names its own times.
 */
public final class TestTokens {

    public static final String ISSUER = "https://identity.tally.test";
    public static final String BILLING = "tally-billing";
    public static final String ACCESS = "tally-access";
    /** The scope of the streaming platform's service token, the one caller of Access. */
    public static final String ACCESS_SCOPE = "access:read";
    public static final String STREAMING_PLATFORM = "streaming-platform";

    static final Instant NOT_BEFORE = Instant.parse("2026-01-14T10:00:00Z");
    static final Duration LIFETIME = Duration.ofDays(3650);

    private static final RSAKey KEY = newKey("tally-test-1");

    /** The test key set, served over HTTP on this machine: the server and its separate instances fetch it. */
    private static final HttpServer KEYS = serveKeys();

    private TestTokens() {
    }

    /** A household: a new user, and a token for it. */
    public record Household(String user, String token) {

        /** A client that sends this household's token with every request. */
        public RestClient client(String baseUrl) {
            return client(RestClient.builder().baseUrl(baseUrl));
        }

        public RestClient client(RestClient.Builder builder) {
            return builder.defaultHeaders(headers -> headers.setBearerAuth(token)).build();
        }

        /** The value of the {@code Authorization} header. */
        public String bearer() {
            return "Bearer " + token;
        }
    }

    /** A household with a new, random user. */
    public static Household household() {
        String user = UUID.randomUUID().toString();
        return new Household(user, token().subject(user).sign());
    }

    /** The streaming platform's service token for Access. */
    public static String streamingPlatform() {
        return token().subject(STREAMING_PLATFORM).audience(ACCESS).scope(ACCESS_SCOPE).sign();
    }

    /** A token to shape: by default a household's, for a random user. */
    public static Token token() {
        return new Token();
    }

    /** The public half of the test key, as a JWK set: what the identity provider would publish. */
    public static String jwkSet() {
        return new JWKSet(KEY).toPublicJWKSet().toString();
    }

    /** Where the test key set is served. */
    public static String jwkSetUri() {
        return "http://127.0.0.1:" + KEYS.getAddress().getPort() + "/jwks";
    }

    /**
     * The settings that make billing check tokens with the test key set (ADR-032; brief rule 11): the issuer, the
     * key set's location and billing's audience. The in-process server and every separate instance get them.
     */
    public static Map<String, String> billingSettings() {
        return Map.of(
                "spring.security.oauth2.resourceserver.jwt.issuer-uri", ISSUER,
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri", jwkSetUri(),
                "spring.security.oauth2.resourceserver.jwt.audiences", BILLING);
    }

    private static HttpServer serveKeys() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] body = jwkSet().getBytes(StandardCharsets.UTF_8);
            server.createContext("/jwks", exchange -> {
                try (exchange) {
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
            });
            server.setExecutor(Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory()));
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("Could not serve the test key set", e);
        }
    }

    static RSAKey newKey(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A token's claims and key; each setter returns this token. */
    public static final class Token {

        private String issuer = ISSUER;
        private String subject = UUID.randomUUID().toString();
        private List<String> audience = List.of(BILLING);
        private String scope;
        private Instant issuedAt = NOT_BEFORE;
        private Instant expiresAt = NOT_BEFORE.plus(LIFETIME);
        private RSAKey key = KEY;

        public Token issuer(String issuer) {
            this.issuer = issuer;
            return this;
        }

        public Token subject(String subject) {
            this.subject = subject;
            return this;
        }

        public Token audience(String... audience) {
            this.audience = List.of(audience);
            return this;
        }

        public Token scope(String scope) {
            this.scope = scope;
            return this;
        }

        /** Valid from {@code issuedAt} until {@code issuedAt + lifetime}. */
        public Token valid(Instant issuedAt, Duration lifetime) {
            this.issuedAt = issuedAt;
            this.expiresAt = issuedAt.plus(lifetime);
            return this;
        }

        /** Signed by another key that claims the test key's ID: a bad signature. */
        public Token forged() {
            this.key = newKey(KEY.getKeyID());
            return this;
        }

        public String sign() {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .subject(subject)
                    .audience(audience)
                    .issueTime(Date.from(issuedAt))
                    .notBeforeTime(Date.from(issuedAt))
                    .expirationTime(Date.from(expiresAt))
                    .jwtID(UUID.randomUUID().toString());
            if (scope != null) {
                claims.claim("scope", scope);
            }
            try {
                SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID())
                        .type(JOSEObjectType.JWT).build(), claims.build());
                jwt.sign(new RSASSASigner(key));
                return jwt.serialize();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
