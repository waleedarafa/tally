package com.tally.spikes;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Slice 4 spikes S-1 and S-2: a test key pair, tokens signed with it, and a stand-in identity provider that serves
 * the public keys (JWKS) and an OpenID discovery document. The provider can be up, down (503) or stopped, or can
 * hang. Nothing here is the PR-2 test token issuer; it is the least the spikes need.
 */
final class SpikeAuthTokens {

    static final String AUDIENCE = "tally-billing";

    private SpikeAuthTokens() {
    }

    static RSAKey newKey(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** A signed token with the given claims; {@code type} null leaves the {@code typ} header out. */
    static String token(RSAKey key, String issuer, String subject, List<String> audience, Instant issuedAt,
            Duration lifetime, JOSEObjectType type) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .subject(subject)
                    .audience(audience)
                    .issueTime(Date.from(issuedAt))
                    .expirationTime(Date.from(issuedAt.plus(lifetime)))
                    .jwtID(UUID.randomUUID().toString())
                    .build();
            JWSHeader.Builder header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID());
            if (type != null) {
                header.type(type);
            }
            SignedJWT jwt = new SignedJWT(header.build(), claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String token(RSAKey key, String issuer, String subject, Instant issuedAt) {
        return token(key, issuer, subject, List.of(AUDIENCE), issuedAt, Duration.ofMinutes(5), JOSEObjectType.JWT);
    }

    /** The stand-in identity provider. */
    static final class Provider implements AutoCloseable {

        enum Mode { UP, DOWN, HANG }

        private final HttpServer server;
        private final AtomicInteger keyFetches = new AtomicInteger();
        private volatile Mode mode = Mode.UP;
        private volatile JWKSet keys;

        Provider(RSAKey... keys) throws IOException {
            this.keys = new JWKSet(List.of(keys)).toPublicJWKSet();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/jwks", exchange -> {
                keyFetches.incrementAndGet();
                answer(exchange, this.keys.toString());
            });
            server.createContext("/.well-known/openid-configuration", exchange -> answer(exchange,
                    "{\"issuer\":\"" + issuer() + "\",\"jwks_uri\":\"" + jwksUri() + "\","
                            + "\"subject_types_supported\":[\"public\"],"
                            + "\"id_token_signing_alg_values_supported\":[\"RS256\"]}"));
            server.start();
        }

        private void answer(HttpExchange exchange, String body) throws IOException {
            try (exchange) {
                Mode now = mode;
                if (now == Mode.HANG) {
                    try {
                        Thread.sleep(Duration.ofSeconds(3));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (now != Mode.UP) {
                    exchange.sendResponseHeaders(503, -1);
                    return;
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        }

        String issuer() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        String jwksUri() {
            return issuer() + "/jwks";
        }

        JWKSet keys() {
            return keys;
        }

        void keys(RSAKey... newKeys) {
            this.keys = new JWKSet(List.of(newKeys)).toPublicJWKSet();
        }

        void mode(Mode mode) {
            this.mode = mode;
        }

        int keyFetches() {
            return keyFetches.get();
        }

        /** Stopped: the port no longer accepts connections. */
        void stop() {
            server.stop(0);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
