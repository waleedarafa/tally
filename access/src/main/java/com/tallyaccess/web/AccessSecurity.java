package com.tallyaccess.web;

import com.tallyaccess.persistence.JdbcKeySetCache;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.JwkSetUriJwtDecoderBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Access answers only the streaming platform (Slice 4 brief, decision 1, option A; CLAUDE.md rule 16). The platform
 * calls with a service token: Access's audience and the scope {@code access:read}.
 * <ul>
 *   <li>No token, or one that fails a check (signature, issuer, audience, expiry): 401. A token of the identity
 *       provider for Access or for billing passes the audience check, so that a household's token, made for billing,
 *       is told 403: it is a valid token, refused here.</li>
 *   <li>A valid token that is not a service token for Access (a household's, or one without the scope): 403.</li>
 *   <li>A token that cannot be checked, because the provider's key set is neither kept nor fetched: 503, with
 *       {@code Retry-After}. It is not the token's fault, so the platform keeps its token and asks again.</li>
 *   <li>The expiry is checked on the injected {@link Clock} (spike S-1).</li>
 *   <li>The provider's key set is kept in Access's database and fetched again only for an unknown key ID, so a
 *       running instance and one that starts during an outage both check tokens (ADR-039, option A for Access); the
 *       fetch has short timeouts, so a provider that hangs cannot hold requests for 30 seconds (spike S-2).</li>
 * </ul>
 * The health and metrics endpoints are not under {@code /v1/} and stay open to the platform that runs Access.
 */
@Configuration(proxyBeanMethods = false)
class AccessSecurity {

    static final String AUDIENCE = "tally-access";
    static final String SCOPE = "SCOPE_access:read";

    @Bean
    SecurityFilterChain access(HttpSecurity http) throws Exception {
        http.securityMatcher("/v1/**")
                .authorizeHttpRequests(requests -> requests.anyRequest().access((authentication, context) ->
                        new AuthorizationDecision(isTheStreamingPlatform(authentication.get()))))
                .oauth2ResourceServer(server -> server.jwt(Customizer.withDefaults()))
                .addFilterBefore(new KeysUnavailable(), BearerTokenAuthenticationFilter.class)
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // A bearer token is sent by the platform, never by a browser on its own: no cookie, so no CSRF.
                .csrf(csrf -> csrf.disable());
        return http.build();
    }

    /**
     * Spring Security rethrows the decoder's failure to get the key set as an {@code AuthenticationServiceException},
     * which would be a 500: here it is 503, with {@code Retry-After}, as the provider is unavailable.
     */
    static final class KeysUnavailable extends OncePerRequestFilter {

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            try {
                chain.doFilter(request, response);
            } catch (AuthenticationServiceException unavailable) {
                response.setHeader(HttpHeaders.RETRY_AFTER, "5");
                response.sendError(HttpStatus.SERVICE_UNAVAILABLE.value(), "The token cannot be checked now");
            }
        }
    }

    static boolean isTheStreamingPlatform(Authentication authentication) {
        return authentication instanceof JwtAuthenticationToken token
                && token.getToken().getAudience().contains(AUDIENCE)
                && token.getAuthorities().stream().anyMatch(authority -> SCOPE.equals(authority.getAuthority()));
    }

    /** The expiry, on the injected clock; Spring Boot then adds no system-clock check of its own (spike S-1). */
    @Bean
    OAuth2TokenValidator<Jwt> expiryOnTheInjectedClock(Clock clock) {
        JwtTimestampValidator validator = new JwtTimestampValidator();
        validator.setClock(clock);
        return validator;
    }

    /**
     * The key set kept in Access's database, and short timeouts on fetching it. {@code JdbcTemplate} is made after
     * Flyway has migrated the database, so the table is there when the decoder reads it, as it is built.
     */
    @Bean
    JwkSetUriJwtDecoderBuilderCustomizer keySetKeptInTheDatabase(JdbcTemplate jdbc, Clock clock,
            @Value("${tally.access.security.key-fetch-timeout:2s}") Duration timeout) {
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(timeout);
        requests.setReadTimeout(timeout);
        JdbcKeySetCache keySet = new JdbcKeySetCache(jdbc, clock);
        return builder -> builder.cache(keySet).restOperations(new RestTemplate(requests));
    }
}
