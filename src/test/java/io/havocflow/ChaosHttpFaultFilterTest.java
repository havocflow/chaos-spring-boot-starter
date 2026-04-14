package io.havocflow;

import io.havocflow.autoconfigure.ChaosProperties;
import io.havocflow.web.ChaosHttpFaultFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Arrays;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class ChaosHttpFaultFilterTest {

    private ChaosProperties properties;
    private ChaosHttpFaultFilter filter;

    @BeforeEach
    void setUp() {
        properties = new ChaosProperties();
        properties.getHttpFault().setEnabled(true);
        properties.getHttpFault().setFailureRate(1.0);  // deterministic
        properties.getHttpFault().setHttpStatus(503);
        filter = new ChaosHttpFaultFilter(properties);
    }

    @Test
    @DisplayName("Filter disabled — chain always continues")
    void filterDisabled_chainContinues() throws Exception {
        properties.getHttpFault().setEnabled(false);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/orders");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertThat(chain.getRequest()).isNotNull();   // chain.doFilter was called
        assertThat(resp.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("Path not matching configured pattern — chain continues")
    void pathNotMatching_chainContinues() throws Exception {
        properties.getHttpFault().setPathPatterns(Arrays.asList("/api/**"));

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/health");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(resp.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("Path matches and failureRate=1.0 — 503 returned, chain NOT called")
    void pathMatching_injectsHttpFault() throws Exception {
        properties.getHttpFault().setPathPatterns(Arrays.asList("/api/**"));

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/orders");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertThat(resp.getStatus()).isEqualTo(503);
        assertThat(chain.getRequest()).isNull();  // chain was not called
    }

    @Test
    @DisplayName("No path patterns configured — all paths match")
    void noPathPatterns_allPathsMatch() throws Exception {
        properties.getHttpFault().setPathPatterns(Collections.<String>emptyList());

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/anything");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertThat(resp.getStatus()).isEqualTo(503);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("Latency expression injected before processing")
    void latencyInjected_sleepsBeforeProcessing() throws Exception {
        properties.getHttpFault().setLatency("50ms");
        properties.getHttpFault().setFailureRate(0.0);  // no fault, only latency

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/any");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        long start = System.currentTimeMillis();
        filter.doFilter(req, resp, chain);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isGreaterThanOrEqualTo(40L);  // some tolerance for CI
    }

    @Test
    @DisplayName("Configurable response body is used in the error response")
    void configurableResponseBody_usedInFaultResponse() throws Exception {
        properties.getHttpFault().setResponseBody("custom error message");

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/any");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertThat(resp.getErrorMessage()).isEqualTo("custom error message");
    }

    @Test
    @DisplayName("Default response body is used when not customized")
    void defaultResponseBody_usedWhenNotConfigured() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/any");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertThat(resp.getErrorMessage()).isEqualTo("[HavocFlow] HTTP fault injected");
    }

    @Test
    @DisplayName("maxLatencyMillis cap is respected — latency capped below expression value")
    void maxLatencyCap_respected() throws Exception {
        properties.setMaxLatencyMillis(80);
        properties.getHttpFault().setLatency("500ms");
        properties.getHttpFault().setFailureRate(0.0);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/any");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        long start = System.currentTimeMillis();
        filter.doFilter(req, resp, chain);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(elapsed).isLessThan(400L);  // significantly below 500ms
    }
}
