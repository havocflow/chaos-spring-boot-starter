package io.havocflow.web;

import io.havocflow.autoconfigure.ChaosProperties;
import io.havocflow.core.LatencyParser;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.AntPathMatcher;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Jakarta Servlet filter that injects HTTP-layer faults into matching requests.
 *
 * <p>This is the Spring Boot 3.x equivalent of {@link ChaosHttpFaultFilter}. It implements
 * {@code jakarta.servlet.Filter} directly to avoid compile-time coupling to Spring's
 * {@code GenericFilterBean} which changed its base servlet API between Spring 5 and 6.
 *
 * <p>Activated when {@code chaos.http-fault.enabled=true} on a Spring Boot 3.x application.
 * Registered automatically by {@code ChaosAutoConfiguration} when
 * {@code jakarta.servlet.FilterChain} is on the classpath.
 *
 * <p>Example configuration:
 * <pre>
 * chaos:
 *   http-fault:
 *     enabled: true
 *     failure-rate: 0.1
 *     http-status: 503
 *     latency: "200ms"
 *     response-body: "Service temporarily unavailable"
 *     path-patterns:
 *       - "/api/**"
 * </pre>
 *
 * @see ChaosHttpFaultFilter
 */
public class ChaosHttpFaultFilterJakarta implements Filter {

    private static final Logger log = LoggerFactory.getLogger(ChaosHttpFaultFilterJakarta.class);

    private final ChaosProperties properties;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public ChaosHttpFaultFilterJakarta(ChaosProperties properties) {
        this.properties = properties;
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        // no-op
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        ChaosProperties.HttpFaultProperties httpFault = properties.getHttpFault();
        if (!httpFault.isEnabled()) {
            chain.doFilter(request, response);
            return;
        }

        HttpServletRequest httpReq = (HttpServletRequest) request;
        HttpServletResponse httpResp = (HttpServletResponse) response;

        String requestUri = httpReq.getRequestURI();
        if (!matchesPathPatterns(requestUri, httpFault.getPathPatterns())) {
            chain.doFilter(request, response);
            return;
        }

        // Inject latency (before processing — models slow upstream dependency)
        String latencyExpr = httpFault.getLatency();
        if (latencyExpr != null && !latencyExpr.trim().isEmpty()) {
            long rawLatency = LatencyParser.parseMillis(latencyExpr);
            long latency = Math.min(rawLatency, properties.getMaxLatencyMillis());
            if (latency > 0) {
                try {
                    log.debug("[HavocFlow][HTTP] Injecting {}ms latency on {}", latency, requestUri);
                    Thread.sleep(latency);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        // Inject HTTP error response
        if (ThreadLocalRandom.current().nextDouble() < httpFault.getFailureRate()) {
            int statusCode = httpFault.getHttpStatus();
            log.debug("[HavocFlow][HTTP] Injecting HTTP {} on {}", statusCode, requestUri);
            httpResp.sendError(statusCode, httpFault.getResponseBody());
            return;
        }

        chain.doFilter(request, response);
    }

    @Override
    public void destroy() {
        // no-op
    }

    /**
     * Returns {@code true} if the request URI matches at least one of the configured
     * path patterns. When no patterns are configured all paths match.
     */
    private boolean matchesPathPatterns(String requestUri, List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return true;
        }
        for (String pattern : patterns) {
            if (pathMatcher.match(pattern, requestUri)) {
                return true;
            }
        }
        return false;
    }
}
