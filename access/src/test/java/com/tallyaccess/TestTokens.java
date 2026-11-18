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
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Access's test token issuer (Slice 4 PR-2): a test key pair, and the streaming platform's service token signed with
 * it, the one caller of Access (decision 1 of the brief, option A). Access never depends on billing code (F7), so
 * this is its own copy of billing's test issuer, kept to what Access's tests send. A token is valid for ten years
 * from 2026-01-14.
 */
public final class TestTokens {

    public static final String ISSUER = "https://identity.tally.test";
    public static final String ACCESS = "tally-access";
    public static final String ACCESS_SCOPE = "access:read";
    public static final String STREAMING_PLATFORM = "streaming-platform";

    private static final Instant NOT_BEFORE = Instant.parse("2026-01-14T10:00:00Z");
    private static final Duration LIFETIME = Duration.ofDays(3650);
    private static final RSAKey KEY = newKey();

    private TestTokens() {
    }

    /** The streaming platform's service token, as the value of the {@code Authorization} header. */
    public static String streamingPlatformBearer() {
        return "Bearer " + sign(STREAMING_PLATFORM, ACCESS, ACCESS_SCOPE);
    }

    /** The public half of the test key, as a JWK set. */
    public static String jwkSet() {
        return new JWKSet(KEY).toPublicJWKSet().toString();
    }

    static String sign(String subject, String audience, String scope) {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(subject)
                .audience(audience)
                .issueTime(Date.from(NOT_BEFORE))
                .notBeforeTime(Date.from(NOT_BEFORE))
                .expirationTime(Date.from(NOT_BEFORE.plus(LIFETIME)))
                .jwtID(UUID.randomUUID().toString());
        if (scope != null) {
            claims.claim("scope", scope);
        }
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID())
                    .type(JOSEObjectType.JWT).build(), claims.build());
            jwt.sign(new RSASSASigner(KEY));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAKey newKey() {
        try {
            return new RSAKeyGenerator(2048).keyID("tally-access-test-1").generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
