package com.tally.support;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Slice 4 PR-2, test only: every request a test sends to billing's API carries a bearer token. The server checks no
 * token yet (PR-3 does); this filter answers 401 to a {@code /v1/} request that has none, so a test that forgets
 * the token fails now, not in PR-3. It reads only the header's form, never the token. PR-3 removes it: the server's
 * own check takes its place.
 */
@TestConfiguration(proxyBeanMethods = false)
public class EveryRequestCarriesAToken {

    public static final class Filter extends OncePerRequestFilter {

        @Override
        protected boolean shouldNotFilter(HttpServletRequest request) {
            return !request.getRequestURI().startsWith("/v1/");
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String authorization = request.getHeader("Authorization");
            if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() <= 7) {
                response.sendError(401, "A test sent " + request.getMethod() + " " + request.getRequestURI()
                        + " without a bearer token");
                return;
            }
            chain.doFilter(request, response);
        }
    }

    @Bean
    FilterRegistrationBean<Filter> everyRequestCarriesAToken() {
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(new Filter());
        registration.addUrlPatterns("/v1/*");
        return registration;
    }
}
