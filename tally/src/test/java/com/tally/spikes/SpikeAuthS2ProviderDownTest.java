package com.tally.spikes;

import static com.tally.spikes.SpikeAuthTokens.newKey;
import static com.tally.spikes.SpikeAuthTokens.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.SecurityContext;
import com.tally.spikes.SpikeAuthTokens.Provider.Mode;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderInitializationException;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;

/**
 * Slice 4 spike S-2: the identity provider is down. (a) An instance is running: how long does it keep the keys, and
 * does it still accept tokens? (b) An instance starts while the provider is down: does it start, and does it accept
 * tokens? Each case is the decoder that Spring Boot 4.1.1 builds for one setting, or a documented option of Spring
 * Security 7.1.1; the expiry check is not under test here, so tokens are issued on the system clock.
 *
 * <p>"Down" is a 503 or a stopped port; "hangs" accepts the connection and never answers.
 */
class SpikeAuthS2ProviderDownTest {

    final RSAKey key = newKey("k1");
    SpikeAuthTokens.Provider provider;

    @BeforeEach
    void start() throws Exception {
        provider = new SpikeAuthTokens.Provider(key);
    }

    @AfterEach
    void stop() {
        provider.close();
    }

    String aToken() {
        return token(key, provider.issuer(), "user-1", Instant.now());
    }

    // (a) A running instance.

    /**
     * {@code jwk-set-uri}, Boot's default decoder: the keys are fetched on the first token and kept by Nimbus's cache
     * for its default time to live, 5 minutes. Within it, a stopped provider changes nothing.
     */
    @Test
    void aRunningInstanceKeepsTheKeysForFiveMinutesAndAcceptsTokensWhileTheProviderIsStopped() {
        JwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(provider.jwksUri()).build();
        assertThat(decoder.decode(aToken()).getSubject()).isEqualTo("user-1");
        assertThat(provider.keyFetches()).isEqualTo(1);

        provider.stop();

        assertThat(decoder.decode(aToken()).getSubject()).isEqualTo("user-1");
        assertThat(JWKSourceBuilder.DEFAULT_CACHE_TIME_TO_LIVE).isEqualTo(Duration.ofMinutes(5).toMillis());
    }

    /**
     * After the time to live, a provider that is down means every token is refused: the cache is not outage
     * tolerant. Shown on Nimbus's same cache with a 1-second time to live, because the 5-minute one reads the system
     * time.
     */
    @Test
    void afterTheTimeToLiveEveryTokenIsRefusedWhileTheProviderIsDown() throws Exception {
        JWKSource<SecurityContext> keys = JWKSourceBuilder.create(URI.create(provider.jwksUri()).toURL())
                .cache(1_000, 500).refreshAheadCache(false).rateLimited(false).build();
        JwtDecoder decoder = NimbusJwtDecoder.withJwkSource(keys).build();
        assertThat(decoder.decode(aToken()).getSubject()).isEqualTo("user-1");

        provider.mode(Mode.DOWN);
        Thread.sleep(1_200);

        assertThatThrownBy(() -> decoder.decode(aToken())).isInstanceOf(JwtException.class);
    }

    /** Nimbus's outage tolerance, through {@code NimbusJwtDecoder.withJwkSource}: the last keys are kept while down. */
    @Test
    void anOutageTolerantSourceKeepsAcceptingAfterTheTimeToLive() throws Exception {
        JWKSource<SecurityContext> keys = JWKSourceBuilder.create(URI.create(provider.jwksUri()).toURL())
                .cache(1_000, 500).refreshAheadCache(false).rateLimited(false)
                .outageTolerantForever().build();
        JwtDecoder decoder = NimbusJwtDecoder.withJwkSource(keys).build();
        assertThat(decoder.decode(aToken()).getSubject()).isEqualTo("user-1");

        provider.mode(Mode.DOWN);
        Thread.sleep(1_200);

        assertThat(decoder.decode(aToken()).getSubject()).isEqualTo("user-1");
    }

    /**
     * With a Spring {@code Cache} ({@code NimbusJwtDecoder.withJwkSetUri(...).cache(cache)}), Nimbus's own cache is off
     * and the Spring cache holds the key set until a token names a key ID it does not hold. A key that rotates in
     * while the provider is down is refused; it is accepted once the provider is up.
     */
    @Test
    void aSpringCacheKeepsTheKeysUntilAnUnknownKeyIdAndFetchesOnlyThen() {
        ConcurrentMapCache cache = new ConcurrentMapCache("jwks");
        JwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(provider.jwksUri()).cache(cache).build();
        assertThat(decoder.decode(aToken()).getSubject()).isEqualTo("user-1");

        provider.mode(Mode.DOWN);
        for (int i = 0; i < 5; i++) {
            assertThat(decoder.decode(aToken()).getSubject()).isEqualTo("user-1");
        }
        assertThat(provider.keyFetches()).isEqualTo(1);

        RSAKey rotated = newKey("k2");
        provider.keys(key, rotated);
        String signedWithTheNewKey = token(rotated, provider.issuer(), "user-1", Instant.now());
        assertThatThrownBy(() -> decoder.decode(signedWithTheNewKey)).isInstanceOf(JwtException.class);

        provider.mode(Mode.UP);
        assertThat(decoder.decode(signedWithTheNewKey).getSubject()).isEqualTo("user-1");
    }

    /**
     * A provider that hangs holds each request for the read timeout of the decoder's HTTP client: 30 seconds by
     * default ({@code sun.net.client.defaultReadTimeout}), read when the decoder is built. Here it is set to 500 ms.
     */
    @Test
    void aHangingProviderHoldsTheRequestForTheReadTimeout() {
        String before = System.getProperty("sun.net.client.defaultReadTimeout");
        System.setProperty("sun.net.client.defaultReadTimeout", "500");
        try {
            JwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(provider.jwksUri()).build();
            provider.mode(Mode.HANG);
            String token = aToken();

            long started = System.nanoTime();
            assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
            Duration waited = Duration.ofNanos(System.nanoTime() - started);
            System.out.println("SPIKE-AUTH-S2 a hanging provider held the request for " + waited.toMillis() + " ms");

            assertThat(waited).isBetween(Duration.ofMillis(400), Duration.ofSeconds(5));
        } finally {
            if (before == null) {
                System.clearProperty("sun.net.client.defaultReadTimeout");
            } else {
                System.setProperty("sun.net.client.defaultReadTimeout", before);
            }
        }
    }

    // (b) An instance that starts while the provider is down.

    /**
     * {@code jwk-set-uri}: the decoder is built without calling the provider, so the instance starts; every token is
     * refused until the provider is back, then accepted without a restart.
     */
    @Test
    void jwkSetUriStartsWhileTheProviderIsDownRefusesTokensAndRecovers() {
        provider.mode(Mode.DOWN);
        JwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(provider.jwksUri()).build();
        assertThat(provider.keyFetches()).isEqualTo(0);

        assertThatThrownBy(() -> decoder.decode(aToken())).isInstanceOf(JwtException.class);

        provider.mode(Mode.UP);
        assertThat(decoder.decode(aToken()).getSubject()).isEqualTo("user-1");
    }

    /**
     * {@code issuer-uri} alone: Boot wraps the decoder in a {@code SupplierJwtDecoder}, so discovery waits for the
     * first token. While the provider is down, that token is refused; the next one after it is back is accepted:
     * the failed discovery is not remembered.
     */
    @Test
    void issuerUriStartsWhileTheProviderIsDownRefusesTokensAndRecovers() {
        provider.mode(Mode.DOWN);
        JwtDecoder decoder = new SupplierJwtDecoder(() -> NimbusJwtDecoder.withIssuerLocation(provider.issuer())
                .build());

        assertThatThrownBy(() -> decoder.decode(aToken())).isInstanceOf(JwtDecoderInitializationException.class);

        provider.mode(Mode.UP);
        assertThat(decoder.decode(aToken()).getSubject()).isEqualTo("user-1");
    }

    /**
     * A key set kept from an earlier run: a Spring {@code Cache} that already holds the key set's JSON is read when
     * the decoder is built, so a new instance accepts tokens while the provider is down, with no fetch. Spring
     * Security keeps the cache in memory only; a store that outlives the process is the application's.
     */
    @Test
    void aKeySetKeptFromAnEarlierRunLetsANewInstanceAcceptTokensWhileTheProviderIsDown() {
        ConcurrentMapCache kept = new ConcurrentMapCache("jwks");
        kept.put(provider.jwksUri(), provider.keys().toString());
        provider.mode(Mode.DOWN);

        JwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(provider.jwksUri()).cache(kept).build();

        assertThat(decoder.decode(aToken()).getSubject()).isEqualTo("user-1");
        assertThat(provider.keyFetches()).isEqualTo(0);
    }

    /**
     * {@code public-key-location}: no provider at all, so a provider that is down changes nothing; a token signed
     * with a rotated key is refused until the setting and the instance change.
     */
    @Test
    void aConfiguredPublicKeyNeedsNoProviderAndDoesNotRotate() throws Exception {
        provider.stop();
        JwtDecoder decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();

        assertThat(decoder.decode(aToken()).getSubject()).isEqualTo("user-1");

        RSAKey rotated = newKey("k2");
        assertThatThrownBy(() -> decoder.decode(token(rotated, provider.issuer(), "user-1", Instant.now())))
                .isInstanceOf(JwtException.class);
    }
}
