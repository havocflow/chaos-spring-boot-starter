package io.havocflow;

import io.havocflow.annotation.InjectChaos;
import io.havocflow.autoconfigure.ChaosAutoConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Service;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test verifying that {@code @InjectChaos} placed on a Spring
 * {@code @Component} interface is honoured by {@code ChaosAspect}.
 */
@SpringBootTest(classes = {ChaosInterfaceAnnotationTest.TestConfig.class})
@Import(ChaosAutoConfiguration.class)
@TestPropertySource(properties = {"chaos.enabled=true"})
class ChaosInterfaceAnnotationTest {

    @Autowired
    private AlwaysFailRepository alwaysFailRepository;

    @Autowired
    private NeverFailRepository neverFailRepository;

    @Autowired
    private MethodLevelRepository methodLevelRepository;

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("Interface-level @InjectChaos with failureRate=1.0 always throws")
    void interfaceAnnotation_failureRate1_alwaysThrows() {
        assertThatThrownBy(() -> alwaysFailRepository.findById("1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HavocFlow");
    }

    @Test
    @DisplayName("Interface-level @InjectChaos with failureRate=0.0 never throws")
    void interfaceAnnotation_failureRate0_noChaos() {
        assertThatCode(() -> neverFailRepository.get()).doesNotThrowAnyException();
        assertThat(neverFailRepository.get()).isEqualTo("ok");
    }

    @Test
    @DisplayName("Method-level annotation on interface takes priority over interface-level annotation")
    void interfaceMethodAnnotation_overridesInterfaceLevel() {
        // The interface is annotated at type level with failureRate=1.0, but
        // the save() method is annotated with failureRate=0.0 — it should NOT throw.
        assertThatCode(() -> methodLevelRepository.save("x")).doesNotThrowAnyException();

        // The findById() method has no method-level override, so it inherits type-level rate=1.0.
        assertThatThrownBy(() -> methodLevelRepository.findById("1"))
                .isInstanceOf(IllegalStateException.class);
    }

    // -----------------------------------------------------------------------
    // Interfaces under test
    // -----------------------------------------------------------------------

    @InjectChaos(failureRate = 1.0, exception = IllegalStateException.class)
    interface AlwaysFailRepository {
        String findById(String id);
    }

    @InjectChaos(failureRate = 0.0)
    interface NeverFailRepository {
        String get();
    }

    @InjectChaos(failureRate = 1.0, exception = IllegalStateException.class)
    interface MethodLevelRepository {
        String findById(String id);  // inherits interface-level: failureRate=1.0

        @InjectChaos(failureRate = 0.0)  // overrides to never fail
        String save(String item);
    }

    // -----------------------------------------------------------------------
    // Implementations
    // -----------------------------------------------------------------------

    @Service
    static class AlwaysFailRepositoryImpl implements AlwaysFailRepository {
        public String findById(String id) { return "item-" + id; }
    }

    @Service
    static class NeverFailRepositoryImpl implements NeverFailRepository {
        public String get() { return "ok"; }
    }

    @Service
    static class MethodLevelRepositoryImpl implements MethodLevelRepository {
        public String findById(String id) { return "item-" + id; }
        public String save(String item)   { return "saved"; }
    }

    // -----------------------------------------------------------------------
    // Spring context
    // -----------------------------------------------------------------------

    @SpringBootConfiguration
    static class TestConfig {
        @Bean
        public AlwaysFailRepository alwaysFailRepository() {
            return new AlwaysFailRepositoryImpl();
        }

        @Bean
        public NeverFailRepository neverFailRepository() {
            return new NeverFailRepositoryImpl();
        }

        @Bean
        public MethodLevelRepository methodLevelRepository() {
            return new MethodLevelRepositoryImpl();
        }
    }
}
