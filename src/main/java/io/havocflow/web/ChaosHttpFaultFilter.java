package io.havocflow.web;

import io.havocflow.autoconfigure.ChaosProperties;
import io.havocflow.core.LatencyParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.GenericFilterBean;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Servlet filter that injects HTTP-layer faults into matching requests.
 *
 * <p>Activated when {@code chaos.http-fault.enabled=true}. Applies latency and/or
 * returns an error HTTP status for a configurable fraction of requests matching
 * the configured path patterns.
 *
 * <p>The filter respects {@code chaos.max-latency-millis} as an upper bound on
 * any injected latency to prevent thread-pool starvation.
 *
 * <p>Example configuration:
 * <pre>
 * chaos:
 *   http-fault:
 *     enabled: true
 *     failure-rate: 0.1
 *     http-status: 503
 *     latency: "200ms"
 *     path-patterns:
 *       - "/api/**"
 * </pre>
 */
public class ChaosHttpFaultFilter extends GenericFilterBean {

    private static final Logger log = LoggerFactory.getLogger(ChaosHttpFaultFilter.class);

    private final ChaosProperties properties;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public ChaosHttpFaultFilter(ChaosProperties properties) {
        this.properties = properties;
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
            httpResp.sendError(statusCode, "[HavocFlow] HTTP fault injected");
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * Returns {@code true} if the request URI matches at least one of the configured
     * path patterns. When no patterns are configured all paths match.
     *
     * <p>Supports simple Ant-style wildcards: {@code *} matches within a path segment,
     * {@code **} matches across segments.
     */
    private boolean matchesPathPatterns(String requestUri, List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return true;
        }
        for (String pattern : patterns) {
            if (antMatch(pattern, requestUri)) {
                return true;
            }
        }
        return false;
    }

    private boolean antMatch(String pattern, String path) {
        return pathMatcher.match(pattern, path);
    }
}
