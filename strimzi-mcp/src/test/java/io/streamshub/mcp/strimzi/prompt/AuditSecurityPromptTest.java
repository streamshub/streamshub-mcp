/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.prompt;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link AuditSecurityPrompt} instruction generation.
 */
class AuditSecurityPromptTest {

    private final AuditSecurityPrompt prompt = new AuditSecurityPrompt();

    AuditSecurityPromptTest() {
    }

    @Test
    void testInstructionsLeadWithCompositeTool() {
        String instructions = prompt.buildAuditInstructions("my-cluster", "kafka");
        assertTrue(instructions.contains("audit_kafka_security"),
            "Primary step should reference the audit_kafka_security tool");
        assertTrue(instructions.contains("Primary step"),
            "Should have a primary step leading with the composite tool");
        assertTrue(instructions.contains("Fallback Step 1"),
            "Should retain the granular fallback steps");
        assertTrue(instructions.contains("HIGH RISK"),
            "Should retain the risk taxonomy for the fallback path");
        assertTrue(instructions.contains("my-cluster"),
            "Should include the cluster name");
    }
}
