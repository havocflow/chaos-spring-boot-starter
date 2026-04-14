package io.havocflow;

import io.havocflow.annotation.InjectChaos;
import io.havocflow.annotation.SuppressChaos;
import io.havocflow.autoconfigure.ChaosAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies that {@code @SuppressChaos} exempts individual methods from class-level
 * {@code @InjectChaos} chaos injection.
 */
@SpringBootTest(classes = {SuppressChaosTest.TestConfig.class})
@Import(ChaosAutoConfiguration.class)
@TestPropertySource(properties = {
    "chaos.enabled=true",
    "chaos.default-failure-rate=1.0"
})
class SuppressChaosTest {

    @Autowired
    private TestService service;

    @Test
    void suppressedMethod_proceedsWithoutChaos() {
        // failureRate=1.0 would always throw on an un-suppressed method;
        // @SuppressChaos must make this call succeed cleanly.
        String result = service.safeMethod();
        assertEquals("safe", result);
    }

    @Test
    void unsuppressedMethod_receivesChaos() {
        // With failureRate=1.0 this MUST throw — proving chaos is still active.
        assertThrows(RuntimeException.class, () -> service.chaosMethod());
    }

    // -----------------------------------------------------------------------
    // Test fixtures
    // -----------------------------------------------------------------------

    @InjectChaos(failureRate = 1.0)
    static class TestService {

        @SuppressChaos
        public String safeMethod() {
            return "safe";
        }

        public String chaosMethod() {
            return "chaos";
        }
    }

    @SpringBootConfiguration
    static class TestConfig {
        @Bean
        TestService testService() {
            return new TestService();
        }
    }
}
