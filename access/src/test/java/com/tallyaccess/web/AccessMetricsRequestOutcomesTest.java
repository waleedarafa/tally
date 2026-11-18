package com.tallyaccess.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Review of #93: a request whose handler, or a later filter, throws still has status 200 when the counting filter
 * sees it; the container makes it a 500 only after the filter returns. It must count as {@code failed}, and the
 * exception must go on to the container.
 */
class AccessMetricsRequestOutcomesTest {

    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final AccessMetrics.RequestOutcomes outcomes = new AccessMetrics.RequestOutcomes(meters, "requests");

    double count(String outcome) {
        return meters.get("requests").tag("outcome", outcome).counter().count();
    }

    @Test
    void aRequestWhoseHandlerThrowsIsCountedAsFailed() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/access/00000000-0000-0000-0000-000000000001");
        IllegalStateException thrown = new IllegalStateException("the handler failed");

        assertThatThrownBy(() -> outcomes.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            throw thrown;
        })).isSameAs(thrown);

        assertThat(count("failed")).isEqualTo(1);
        assertThat(count("answered")).isZero();
    }

    @Test
    void anAnswerIsCountedByItsStatus() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        outcomes.doFilter(new MockHttpServletRequest("GET", "/v1/access/00000000-0000-0000-0000-000000000001"), response,
                (req, res) -> response.setStatus(401));

        assertThat(count("unauthenticated")).isEqualTo(1);
        assertThat(count("failed")).isZero();
    }
}
