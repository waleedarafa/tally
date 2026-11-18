package com.tallyaccess;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Access's test token issuer (Slice 4): a test key pair, served as a key set over HTTP by this test JVM, and tokens
 * signed with it. The streaming platform's service token is the one Access answers (decision 1 of the brief, option
 * A). Access never depends on billing code (F7), so this is its own copy of billing's test issuer, kept to what
 * Access's tests need. The served key set can be switched off, as an identity provider that is down.
 *
 * <p>A token is valid for ten years from 2026-01-14 unless a test names its times: AT-30 uses a service token of one
 * hour (review of #88).
 */
public final class TestTokens {

    public static final String ISSUER = "https://identity.tally.test";
    public static final String ACCESS = "tally-access";
    public static final String BILLING = "tally-billing";
    public static final String ACCESS_SCOPE = "access:read";
    public static final String STREAMING_PLATFORM = "streaming-platform";

    private static final Instant NOT_BEFORE = Instant.parse("2026-01-14T10:00:00Z");
    private static final Duration LIFETIME = Duration.ofDays(3650);
    private static final RSAKey KEY = newKey("tally-access-test-1");
    private static final AtomicBoolean PROVIDER_UP = new AtomicBoolean(true);
    private static final AtomicBoolean PROVIDER_HANGS = new AtomicBoolean(false);
    private static final AtomicInteger KEY_FETCHES = new AtomicInteger();
    private static final HttpServer KEYS = serveKeys();

    private TestTokens() {
    }

    /** The streaming platform's service token, as the value of the {@code Authorization} header. */
    public static String streamingPlatformBearer() {
        return "Bearer " + token().sign();
    }

    /** A token to shape: by default the streaming platform's service token, valid for ten years. */
    public static Token token() {
        return new Token();
    }

    /** A household's token: billing's audience, a user, no scope (decision 1: Access refuses it). */
    public static Token household() {
        return new Token().subject(UUID.randomUUID().toString()).audience(BILLING).scope(null);
    }

    /** The settings that make Access check tokens with the test key set. */
    public static Map<String, String> accessSettings() {
        return Map.of(
                "spring.security.oauth2.resourceserver.jwt.issuer-uri", ISSUER,
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri", jwkSetUri());
    }

    public static String jwkSetUri() {
        return "http://127.0.0.1:" + KEYS.getAddress().getPort() + "/jwks";
    }

    /** The identity provider is up (the key set is served) or down (503). */
    public static void providerUp(boolean up) {
        PROVIDER_UP.set(up);
        PROVIDER_HANGS.set(false);
    }

    /** The identity provider accepts the connection and never answers in time (spike S-2, finding 5). */
    public static void providerHangs() {
        PROVIDER_UP.set(false);
        PROVIDER_HANGS.set(true);
    }

    /** How many times the key set was fetched successfully. */
    public static int keyFetches() {
        return KEY_FETCHES.get();
    }

    static RSAKey newKey(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpServer serveKeys() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            byte[] body = new JWKSet(KEY).toPublicJWKSet().toString().getBytes(StandardCharsets.UTF_8);
            server.createContext("/jwks", exchange -> {
                try (exchange) {
                    if (PROVIDER_HANGS.get()) {
                        try {
                            Thread.sleep(Duration.ofSeconds(10));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    if (!PROVIDER_UP.get()) {
                        exchange.sendResponseHeaders(503, -1);
                        return;
                    }
                    KEY_FETCHES.incrementAndGet();
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
            });
            // Many threads: a request that hangs must not stop the server from taking the next one.
            server.setExecutor(Executors.newCachedThreadPool(Thread.ofPlatform().daemon().factory()));
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException("Could not serve the test key set", e);
        }
    }

    /** A token's claims and key; each setter returns this token. */
    public static final class Token {

        private String issuer = ISSUER;
        private String subject = STREAMING_PLATFORM;
        private List<String> audience = List.of(ACCESS);
        private String scope = ACCESS_SCOPE;
        private Instant issuedAt = NOT_BEFORE;
        private Instant expiresAt = NOT_BEFORE.plus(LIFETIME);
        private RSAKey key = KEY;

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

        public Token valid(Instant issuedAt, Duration lifetime) {
            this.issuedAt = issuedAt;
            this.expiresAt = issuedAt.plus(lifetime);
            return this;
        }

        /** Signed by a key whose ID the provider's key set does not hold: the decoder asks for the key set again. */
        public Token unknownKey() {
            this.key = newKey("unknown-" + UUID.randomUUID());
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

        public String bearer() {
            return "Bearer " + sign();
        }
    }
}
