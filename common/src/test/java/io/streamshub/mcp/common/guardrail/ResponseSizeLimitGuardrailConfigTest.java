/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.guardrail;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests the default of {@code mcp.guardrail.max-response-bytes}.
 */
@QuarkusTest
class ResponseSizeLimitGuardrailConfigTest {

    @Inject
    ResponseSizeLimitGuardrail guardrail;

    ResponseSizeLimitGuardrailConfigTest() {
    }

    @Test
    void testDefaultLimitFitsCommonClients() {
        assertEquals(50_000, guardrail.maxResponseBytes);
    }
}
