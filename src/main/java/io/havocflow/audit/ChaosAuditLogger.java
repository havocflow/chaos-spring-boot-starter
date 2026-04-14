package io.havocflow.audit;

import io.havocflow.core.ChaosDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;

/**
 * Appends a structured JSON-lines audit entry for every chaos injection fired.
 *
 * <p>Each line written to the configured file has the form:
 * <pre>
 * {"ts":"2026-04-14T10:00:00Z","method":"com.example.OrderService.findById","mode":"EXCEPTION","scenario":"db-timeout","latencyMs":0,"thread":"http-nio-8080-1"}
 * </pre>
 *
 * <p>The file is opened in append mode so multiple restarts accumulate into a single log.
 * The writer is flushed after every entry and closed cleanly on {@link #destroy()}.
 *
 * <p>No external dependencies are required — JSON is hand-crafted to keep the
 * transitive dependency footprint at zero.
 */
public class ChaosAuditLogger implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ChaosAuditLogger.class);

    private final String path;
    private final BufferedWriter writer;
    private volatile boolean closed = false;

    /**
     * Opens the audit log file in append mode.
     *
     * @param path absolute or relative path to the {@code .jsonl} file
     * @throws IllegalStateException if the file cannot be opened
     */
    public ChaosAuditLogger(String path) {
        this.path = path;
        try {
            this.writer = new BufferedWriter(new FileWriter(path, true));
            log.info("[HavocFlow] Audit log opened — path={}", path);
        } catch (IOException e) {
            throw new IllegalStateException(
                "[HavocFlow] Cannot open audit log at '" + path + "': " + e.getMessage(), e);
        }
    }

    /**
     * Writes a single JSON-lines entry for the given injection event.
     *
     * @param method   fully-qualified method name (e.g. {@code "com.example.MyService.doWork"})
     * @param decision the resolved chaos decision
     * @param thread   name of the thread on which chaos was injected
     */
    public void record(String method, ChaosDecision decision, String thread) {
        if (closed) return;
        String line = buildJsonLine(method, decision, thread);
        try {
            synchronized (writer) {
                writer.write(line);
                writer.newLine();
                writer.flush();
            }
        } catch (IOException e) {
            log.warn("[HavocFlow] Failed to write audit log entry: {}", e.getMessage());
        }
    }

    @Override
    public void destroy() {
        if (closed) return;
        synchronized (writer) {
            if (closed) return;
            closed = true;
            try {
                writer.close();
                log.info("[HavocFlow] Audit log closed — path={}", path);
            } catch (IOException e) {
                log.warn("[HavocFlow] Error closing audit log at '{}': {}", path, e.getMessage());
            }
        }
    }

    // -----------------------------------------------------------------------
    // JSON helpers (no external dependency — hand-crafted to keep zero new deps)
    // -----------------------------------------------------------------------

    private String buildJsonLine(String method, ChaosDecision decision, String thread) {
        return "{\"ts\":\"" + Instant.now() + "\""
            + ",\"method\":\"" + escape(method) + "\""
            + ",\"mode\":\"" + decision.getMode().name() + "\""
            + ",\"scenario\":\"" + escape(decision.getScenarioName()) + "\""
            + ",\"latencyMs\":" + decision.getLatencyMillis()
            + ",\"thread\":\"" + escape(thread) + "\""
            + "}";
    }

    /** Minimal JSON string escaping for the fields we log (method names and thread names). */
    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
