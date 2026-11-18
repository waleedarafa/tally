package com.tally.platform.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The metrics of authentication (ADR-032; ADR-030, Slice 4 PR-7), read by the rules {@code ApiTokensRefused} and
 * {@code IdentityKeysUnavailable}:
 * <ul>
 *   <li>{@code tally.api.requests}, tagged {@code outcome} ({@code answered}, {@code unauthenticated} for 401,
 *       {@code forbidden} for 403, {@code failed} for 5xx): every answer to a request under {@code /v1/}, counted in
 *       front of the security filters, so a 401 is counted as well as an answer;</li>
 *   <li>{@code tally.identity.key.fetches}, tagged {@code outcome}: every fetch of the identity provider's key set,
 *       {@code success} or {@code failure}.</li>
 * </ul>
 * Each counter is made at start with every value of its tag, so the scrape shows it before the first request (F14).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class ApiMetrics {

    static final String REQUESTS = "tally.api.requests";
    static final String KEY_FETCHES = "tally.identity.key.fetches";

    @Bean
    FilterRegistrationBean<RequestOutcomes> apiRequestOutcomes(MeterRegistry meters) {
        FilterRegistrationBean<RequestOutcomes> registration =
                new FilterRegistrationBean<>(new RequestOutcomes(meters, REQUESTS));
        registration.addUrlPatterns("/v1/*");
        // Before Spring Security's filters, so their 401s are counted.
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /** Counts each fetch of the key set; given to the decoder's HTTP client ({@link ApiSecurity}). */
    @Bean
    KeyFetchCounter keyFetchCounter(MeterRegistry meters) {
        return new KeyFetchCounter(meters, KEY_FETCHES);
    }

    /** The outcome of every answer to a request under {@code /v1/}. */
    static final class RequestOutcomes extends OncePerRequestFilter {

        private final Map<String, Counter> outcomes;

        RequestOutcomes(MeterRegistry meters, String name) {
            this.outcomes = Map.of(
                    "answered", counter(meters, name, "answered"),
                    "unauthenticated", counter(meters, name, "unauthenticated"),
                    "forbidden", counter(meters, name, "forbidden"),
                    "failed", counter(meters, name, "failed"));
        }

        private static Counter counter(MeterRegistry meters, String name, String outcome) {
            return Counter.builder(name).tag("outcome", outcome)
                    .description("Answers to requests under /v1/, by outcome").register(meters);
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            try {
                chain.doFilter(request, response);
            } finally {
                outcomes.get(outcome(response.getStatus())).increment();
            }
        }

        /** A 5xx is {@code failed}: billing answers 500 when it cannot check a token (Slice 4 report). */
        static String outcome(int status) {
            if (status >= 500) {
                return "failed";
            }
            return switch (status) {
                case 401 -> "unauthenticated";
                case 403 -> "forbidden";
                default -> "answered";
            };
        }
    }

    /** Counts the fetches of the identity provider's key set, as an interceptor of the decoder's HTTP client. */
    static final class KeyFetchCounter {

        private final Counter success;
        private final Counter failure;

        KeyFetchCounter(MeterRegistry meters, String name) {
            this.success = Counter.builder(name).tag("outcome", "success")
                    .description("Fetches of the identity provider's key set, by outcome").register(meters);
            this.failure = Counter.builder(name).tag("outcome", "failure")
                    .description("Fetches of the identity provider's key set, by outcome").register(meters);
        }

        double successCount() {
            return success.count();
        }

        double failureCount() {
            return failure.count();
        }

        ClientHttpRequestInterceptor interceptor() {
            return (request, body, execution) -> {
                try {
                    var response = execution.execute(request, body);
                    (response.getStatusCode().is2xxSuccessful() ? success : failure).increment();
                    return response;
                } catch (IOException | RuntimeException e) {
                    failure.increment();
                    throw e;
                }
            };
        }
    }
}
