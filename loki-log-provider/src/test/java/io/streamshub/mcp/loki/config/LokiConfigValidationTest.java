/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.loki.config;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the constraints on {@link LokiConfig}. Quarkus checks them when the server starts,
 * so a lookback below one minute fails startup instead of producing an empty or inverted
 * Loki query window at request time.
 */
@QuarkusTest
class LokiConfigValidationTest {

    private static final List<String> LOOKBACKS = List.of("maxLookback", "filterLookback");

    @Inject
    Validator validator;

    @Inject
    LokiConfig config;

    LokiConfigValidationTest() {
    }

    @Test
    void testLookbackBelowOneMinuteIsRejected() throws NoSuchMethodException {
        for (String lookback : LOOKBACKS) {
            Method method = LokiConfig.class.getMethod(lookback);
            for (Duration invalid : List.of(Duration.ofSeconds(-60), Duration.ZERO, Duration.ofSeconds(59))) {
                Set<ConstraintViolation<LokiConfig>> violations =
                    validator.forExecutables().validateReturnValue(config, method, invalid);
                assertEquals(1, violations.size(), lookback + " = " + invalid);
            }
        }
    }

    @Test
    void testLookbackOfOneMinuteOrMoreIsAccepted() throws NoSuchMethodException {
        for (String lookback : LOOKBACKS) {
            Method method = LokiConfig.class.getMethod(lookback);
            for (Duration valid : List.of(Duration.ofMinutes(1), Duration.ofHours(1), Duration.ofHours(720))) {
                assertTrue(validator.forExecutables().validateReturnValue(config, method, valid).isEmpty(),
                    lookback + " = " + valid);
            }
        }
    }
}
