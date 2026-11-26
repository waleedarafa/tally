package com.tally.platform.internal;

import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.JwkSetUriJwtDecoderBuilderCustomizer;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestTemplate;

/**
 * Every request to the API carries a token from the identity provider, and Tally checks it itself (ADR-032; CLAUDE.md
 * rule 16). Spring Boot builds the decoder from {@code spring.security.oauth2.resourceserver.jwt.*}: the key set's
 * location, the issuer and billing's audience (spike S-1). This class adds three things to it:
 * <ul>
 *   <li>the expiry is checked on the injected {@link Clock} (rule 4): Boot's default check reads the system clock
 *       unless a {@code JwtTimestampValidator} is given (spike S-1);</li>
 *   <li>the keys are kept in memory until a token names a key ID that is not held, so a running instance keeps
 *       accepting tokens while the provider is down (ADR-039, option B for billing; spike S-2);</li>
 *   <li>short timeouts on fetching the keys, so a provider that hangs cannot hold requests for 30 seconds
 *       (ADR-039; spike S-2).</li>
 * </ul>
 * The health and metrics endpoints are not under {@code /v1/} and stay open, on the management port.
 * Everything under /v1/admin/ also needs the scope billing:admin (ADR-053); a token without it gets 403.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class ApiSecurity {

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        http.securityMatcher("/v1/**")
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/v1/admin/**").hasAuthority("SCOPE_billing:admin")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(server -> server.jwt(Customizer.withDefaults()))
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // A bearer token is sent by the client, never by a browser on its own: no cookie, so no CSRF.
                .csrf(csrf -> csrf.disable());
        return http.build();
    }

    /** The expiry, on the injected clock; Boot then adds no system-clock check of its own (spike S-1). */
    @Bean
    OAuth2TokenValidator<Jwt> expiryOnTheInjectedClock(Clock clock) {
        JwtTimestampValidator validator = new JwtTimestampValidator();
        validator.setClock(clock);
        return validator;
    }

    @Bean
    JwkSetUriJwtDecoderBuilderCustomizer keysInMemoryWithShortTimeouts(
            @Value("${tally.security.key-fetch-timeout:2s}") Duration timeout, ApiMetrics.KeyFetchCounter fetches) {
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(timeout);
        requests.setReadTimeout(timeout);
        RestTemplate client = new RestTemplate(requests);
        // Each fetch counted, for IdentityKeysUnavailable (ADR-030).
        client.getInterceptors().add(fetches.interceptor());
        return builder -> builder.cache(new ConcurrentMapCache("identity-provider-keys")).restOperations(client);
    }
}
