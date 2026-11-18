/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.opensearch.config;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the constraint on {@link OpenSearchConfig#filterLookback()}: below one minute the
 * server must not start.
 */
@QuarkusTest
class OpenSearchConfigValidationTest {

    @Inject
    Validator validator;

    @Inject
    OpenSearchConfig config;

    OpenSearchConfigValidationTest() {
    }

    @Test
    void testFilterLookbackDefaultsToOneHour() {
        assertEquals(Duration.ofHours(1), config.filterLookback());
    }

    @Test
    void testFilterLookbackBelowOneMinuteIsRejected() throws NoSuchMethodException {
        Method method = OpenSearchConfig.class.getMethod("filterLookback");
        for (Duration invalid : List.of(Duration.ofSeconds(-60), Duration.ZERO, Duration.ofSeconds(59))) {
            assertEquals(1, validator.forExecutables().validateReturnValue(config, method, invalid).size(),
                "filterLookback = " + invalid);
        }
    }

    @Test
    void testFilterLookbackOfOneMinuteOrMoreIsAccepted() throws NoSuchMethodException {
        Method method = OpenSearchConfig.class.getMethod("filterLookback");
        for (Duration valid : List.of(Duration.ofMinutes(1), Duration.ofHours(1), Duration.ofDays(30))) {
            assertTrue(validator.forExecutables().validateReturnValue(config, method, valid).isEmpty(),
                "filterLookback = " + valid);
        }
    }
}
