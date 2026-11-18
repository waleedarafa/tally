package com.tally.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.tally.platform.TestClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * The test token issuer's tokens pass the checks the server will make (Slice 4 spike S-1), on the published key set,
 * and each of its shapes fails the check it is meant to fail.
 */
class TestTokensTest {

    final TestClock clock = new TestClock();

    NimbusJwtDecoder decoder(String audience) throws Exception {
        RSAKey key = JWKSet.parse(TestTokens.jwkSet()).getKeys().getFirst().toRSAKey();
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator();
        timestamps.setClock(clock);
        List<OAuth2TokenValidator<Jwt>> validators = List.of(new JwtIssuerValidator(TestTokens.ISSUER),
                new JwtClaimValidator<Collection<String>>(JwtClaimNames.AUD, aud -> aud != null
                        && aud.contains(audience)), timestamps);
        decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(validators));
        return decoder;
    }

    @Test
    void aHouseholdsTokenNamesItsUserForBilling() throws Exception {
        TestTokens.Household household = TestTokens.household();

        Jwt jwt = decoder(TestTokens.BILLING).decode(household.token());

        assertThat(jwt.getSubject()).isEqualTo(household.user());
        assertThat(jwt.getAudience()).containsExactly(TestTokens.BILLING);
        assertThat(household.bearer()).isEqualTo("Bearer " + household.token());
        assertThat(TestTokens.household().user()).isNotEqualTo(household.user());
    }

    @Test
    void theStreamingPlatformsTokenIsForAccessWithItsScope() throws Exception {
        Jwt jwt = decoder(TestTokens.ACCESS).decode(TestTokens.streamingPlatform());

        assertThat(jwt.getSubject()).isEqualTo(TestTokens.STREAMING_PLATFORM);
        assertThat(jwt.getClaimAsString("scope")).isEqualTo(TestTokens.ACCESS_SCOPE);
        assertThatThrownBy(() -> decoder(TestTokens.BILLING).decode(TestTokens.streamingPlatform()))
                .isInstanceOf(JwtValidationException.class).hasMessageContaining("aud");
    }

    /** Valid on the test clock through every move a test makes, and on the system clock. */
    @Test
    void aTokenOutlivesTheTestsThatMoveTheClock() throws Exception {
        String token = TestTokens.household().token();
        NimbusJwtDecoder decoder = decoder(TestTokens.BILLING);

        decoder.decode(token);
        clock.advance(Duration.ofDays(400));
        decoder.decode(token);
        clock.set(Instant.now());
        decoder.decode(token);
    }

    @Test
    void eachShapeFailsItsCheck() throws Exception {
        NimbusJwtDecoder decoder = decoder(TestTokens.BILLING);

        assertThatThrownBy(() -> decoder.decode(TestTokens.token().issuer("https://elsewhere.test").sign()))
                .isInstanceOf(JwtValidationException.class).hasMessageContaining("iss");
        assertThatThrownBy(() -> decoder.decode(TestTokens.token().audience("someone-else").sign()))
                .isInstanceOf(JwtValidationException.class).hasMessageContaining("aud");
        assertThatThrownBy(() -> decoder.decode(TestTokens.token().forged().sign()))
                .isInstanceOf(BadJwtException.class);
        String fiveMinutes = TestTokens.token().valid(clock.instant(), Duration.ofMinutes(5)).sign();
        decoder.decode(fiveMinutes);
        clock.advance(Duration.ofMinutes(7));
        assertThatThrownBy(() -> decoder.decode(fiveMinutes))
                .isInstanceOf(JwtValidationException.class).hasMessageContaining("expired");
    }
}
