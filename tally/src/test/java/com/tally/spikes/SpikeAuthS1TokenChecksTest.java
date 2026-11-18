package com.tally.spikes;

import static com.tally.spikes.SpikeAuthTokens.AUDIENCE;
import static com.tally.spikes.SpikeAuthTokens.newKey;
import static com.tally.spikes.SpikeAuthTokens.token;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.jwk.RSAKey;
import com.tally.platform.TestClock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Slice 4 spike S-1: with Spring Security 7.1.1 (Spring Boot 4.1.1's version), which checks does a token pass, and can
 * the expiry check use the injected {@code Clock}?
 *
 * <p>The decoder is built as Spring Boot's {@code JwtDecoderConfiguration} builds it for {@code jwk-set-uri}:
 * {@code NimbusJwtDecoder.withJwkSetUri}, then {@code JwtValidators.createDefaultWithValidators} over the issuer
 * validator, an audience validator and every {@code OAuth2TokenValidator<Jwt>} bean. Boot's auto-configuration is not
 * on this test's classpath (it would secure every endpoint before PR-3), so the assembly is repeated here, line for
 * line; slice-4-spikes.md cites the lines.
 */
class SpikeAuthS1TokenChecksTest {

    final RSAKey key = newKey("k1");
    final TestClock clock = new TestClock();
    SpikeAuthTokens.Provider provider;

    @BeforeEach
    void start() throws Exception {
        provider = new SpikeAuthTokens.Provider(key);
    }

    @AfterEach
    void stop() {
        provider.close();
    }

    /** Boot's {@code JwtDecoderConfiguration.getValidator}, with {@code additional} as the validator beans. */
    JwtDecoder decoder(List<OAuth2TokenValidator<Jwt>> additional) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(provider.jwksUri()).build();
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(new JwtIssuerValidator(provider.issuer()));
        validators.add(new JwtClaimValidator<Collection<String>>(JwtClaimNames.AUD,
                aud -> aud != null && aud.contains(AUDIENCE)));
        validators.addAll(additional);
        decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(validators));
        return decoder;
    }

    /** The one bean that puts the injected clock into the expiry check. */
    JwtDecoder decoderOnTheInjectedClock() {
        JwtTimestampValidator timestamps = new JwtTimestampValidator();
        timestamps.setClock(clock);
        return decoder(List.of(timestamps));
    }

    String tokenFor(String subject) {
        return token(key, provider.issuer(), subject, clock.instant());
    }

    @Test
    void aValidTokenGivesItsSubjectAsTheUser() {
        Jwt jwt = decoderOnTheInjectedClock().decode(tokenFor("user-1"));

        assertThat(jwt.getSubject()).isEqualTo("user-1");
        assertThat(jwt.getAudience()).containsExactly(AUDIENCE);
        assertThat(jwt.getIssuer().toString()).isEqualTo(provider.issuer());
    }

    @Test
    void aWrongIssuerAWrongAudienceAndABadSignatureAreEachRefused() {
        JwtDecoder decoder = decoderOnTheInjectedClock();
        Instant now = clock.instant();

        assertThatThrownBy(() -> decoder.decode(token(key, "https://someone-else.example", "u", now)))
                .isInstanceOf(JwtValidationException.class).hasMessageContaining("iss");
        assertThatThrownBy(() -> decoder.decode(token(key, provider.issuer(), "u", List.of("tally-access"), now,
                Duration.ofMinutes(5), JOSEObjectType.JWT)))
                .isInstanceOf(JwtValidationException.class).hasMessageContaining("aud");
        // Signed by another key that claims the same key ID.
        RSAKey impostor = newKey("k1");
        assertThatThrownBy(() -> decoder.decode(token(impostor, provider.issuer(), "u", now)))
                .isInstanceOf(BadJwtException.class).hasMessageContaining("Signed JWT rejected");
    }

    /**
     * The expiry check uses the injected clock when a {@code JwtTimestampValidator} with that clock is one of the
     * validators: {@code createDefaultWithValidators} adds its own system-clock validator only when none is given.
     * Its default leeway is 60 seconds.
     */
    @Test
    void theExpiryCheckUsesTheInjectedClockWithASixtySecondLeeway() {
        JwtDecoder decoder = decoderOnTheInjectedClock();
        String token = tokenFor("user-1"); // expires 5 minutes from now, on the test clock

        clock.advance(Duration.ofMinutes(5).plusSeconds(59));
        assertThat(decoder.decode(token).getSubject()).isEqualTo("user-1");

        clock.advance(Duration.ofSeconds(2));
        assertThatThrownBy(() -> decoder.decode(token))
                .isInstanceOf(JwtValidationException.class).hasMessageContaining("expired");
    }

    /**
     * Without that bean, Boot's default expiry check reads the system clock: moving the injected clock past the
     * token's end changes nothing. The test clock starts in January 2026, so a token issued "now" on it is long
     * expired on the system clock, and one issued now on the system clock is accepted whatever the test clock says.
     */
    @Test
    void withoutTheBeanTheExpiryCheckReadsTheSystemClock() {
        JwtDecoder decoder = decoder(List.of());

        String onTheTestClock = tokenFor("user-1");
        assertThatThrownBy(() -> decoder.decode(onTheTestClock))
                .isInstanceOf(JwtValidationException.class).hasMessageContaining("expired");

        String onTheSystemClock = token(key, provider.issuer(), "user-1", Instant.now());
        clock.advance(Duration.ofDays(1));
        assertThat(decoder.decode(onTheSystemClock).getSubject()).isEqualTo("user-1");
    }

    /**
     * The {@code typ} header: Spring Security 7's default validators accept {@code JWT} or no {@code typ}, and refuse
     * {@code at+jwt} (RFC 9068 access tokens), which some identity providers send.
     */
    @Test
    void theTypeHeaderMustBeJwtOrAbsent() {
        JwtDecoder decoder = decoderOnTheInjectedClock();
        Instant now = clock.instant();

        assertThat(decoder.decode(token(key, provider.issuer(), "u", List.of(AUDIENCE), now, Duration.ofMinutes(5),
                null)).getSubject()).isEqualTo("u");
        assertThatThrownBy(() -> decoder.decode(token(key, provider.issuer(), "u", List.of(AUDIENCE), now,
                Duration.ofMinutes(5), new JOSEObjectType("at+jwt"))))
                .isInstanceOf(JwtValidationException.class);
    }
}
