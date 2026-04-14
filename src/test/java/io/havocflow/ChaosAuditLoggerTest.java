package io.havocflow;

import io.havocflow.audit.ChaosAuditLogger;
import io.havocflow.core.ChaosDecision;
import io.havocflow.core.FailureMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ChaosAuditLogger}.
 */
class ChaosAuditLoggerTest {

    @TempDir
    Path tempDir;

    @Test
    void record_writesValidJsonLine() throws Exception {
        Path logFile = tempDir.resolve("audit.jsonl");
        ChaosAuditLogger logger = new ChaosAuditLogger(logFile.toString());

        ChaosDecision decision = ChaosDecision.builder()
                .mode(FailureMode.EXCEPTION)
                .latencyMillis(0)
                .exceptionType(RuntimeException.class)
                .scenarioName("db-timeout")
                .build();

        logger.record("com.example.OrderService.findById", decision, "test-thread");
        logger.destroy();

        List<String> lines = Files.readAllLines(logFile);
        assertEquals(1, lines.size(), "expected exactly one audit line");

        String line = lines.get(0);
        assertTrue(line.startsWith("{"), "line should be JSON object");
        assertTrue(line.contains("\"mode\":\"EXCEPTION\""));
        assertTrue(line.contains("\"scenario\":\"db-timeout\""));
        assertTrue(line.contains("\"method\":\"com.example.OrderService.findById\""));
        assertTrue(line.contains("\"thread\":\"test-thread\""));
        assertTrue(line.contains("\"latencyMs\":0"));
        assertTrue(line.contains("\"ts\":\""));
    }

    @Test
    void record_appendsMultipleLines() throws Exception {
        Path logFile = tempDir.resolve("audit-multi.jsonl");
        ChaosAuditLogger logger = new ChaosAuditLogger(logFile.toString());

        ChaosDecision d1 = ChaosDecision.builder()
                .mode(FailureMode.LATENCY)
                .latencyMillis(200)
                .exceptionType(null)
                .scenarioName("inline")
                .build();

        ChaosDecision d2 = ChaosDecision.builder()
                .mode(FailureMode.EXCEPTION)
                .latencyMillis(0)
                .exceptionType(RuntimeException.class)
                .scenarioName("flaky")
                .build();

        logger.record("svc.A", d1, "t1");
        logger.record("svc.B", d2, "t2");
        logger.destroy();

        List<String> lines = Files.readAllLines(logFile);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("\"mode\":\"LATENCY\""));
        assertTrue(lines.get(1).contains("\"mode\":\"EXCEPTION\""));
    }

    @Test
    void destroy_closesFileCleanly() throws Exception {
        Path logFile = tempDir.resolve("audit-close.jsonl");
        ChaosAuditLogger logger = new ChaosAuditLogger(logFile.toString());
        logger.destroy();
        // Second destroy should not throw
        assertDoesNotThrow(logger::destroy);
    }

    @Test
    void invalidPath_throwsIllegalStateException() {
        assertThrows(IllegalStateException.class, () ->
            new ChaosAuditLogger("/nonexistent/dir/audit.jsonl"));
    }
}
