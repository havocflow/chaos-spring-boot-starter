package io.havocflow;

import io.havocflow.engine.LatencyParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@DisplayName("LatencyParser")
class LatencyParserTest {

    @Test
    void parsesMilliseconds() {
        assertThat(LatencyParser.parseMillis("500ms")).isEqualTo(500L);
    }

    @Test
    void parsesSeconds() {
        assertThat(LatencyParser.parseMillis("2s")).isEqualTo(2000L);
    }

    @Test
    void parsesMinutes() {
        assertThat(LatencyParser.parseMillis("1m")).isEqualTo(60_000L);
    }

    @Test
    void parsesRangeAlwaysWithinBounds() {
        for (int i = 0; i < 100; i++) {
            long value = LatencyParser.parseMillis("100ms-2s");
            assertThat(value).isBetween(100L, 2000L);
        }
    }

    @Test
    void returnsZeroForBlank() {
        assertThat(LatencyParser.parseMillis("")).isZero();
        assertThat(LatencyParser.parseMillis(null)).isZero();
    }

    @Test
    void throwsOnInvalidFormat() {
        assertThatThrownBy(() -> LatencyParser.parseMillis("5x"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unrecognised latency format");
    }

    @Test
    void throwsWhenRangeIsInverted() {
        assertThatThrownBy(() -> LatencyParser.parseMillis("5s-1s"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("lower bound");
    }
}
