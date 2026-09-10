/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.util;

import io.quarkiverse.mcp.server.JsonRpcErrorCodes;
import io.quarkiverse.mcp.server.McpException;
import io.streamshub.mcp.common.dto.error.McpErrorCategory;
import io.streamshub.mcp.common.dto.error.McpErrorData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link McpErrors} structured error factories.
 */
class McpErrorsTest {

    McpErrorsTest() {
    }

    @Test
    void testNotFoundWithNamespaceCarriesCodeAndData() {
        McpException ex = McpErrors.notFound("Kafka", "my-cluster", "prod");

        assertEquals(JsonRpcErrorCodes.RESOURCE_NOT_FOUND, ex.getJsonRpcErrorCode());
        McpErrorData data = (McpErrorData) ex.getData();
        assertEquals(McpErrorCategory.RESOURCE_NOT_FOUND, data.category());
        assertEquals("Kafka", data.resourceKind());
        assertEquals("my-cluster", data.resourceName());
        assertEquals("prod", data.namespace());
        assertTrue(ex.getMessage().contains("my-cluster"), ex.getMessage());
        assertTrue(ex.getMessage().contains("prod"), ex.getMessage());
        assertTrue(ex.getMessage().contains("not found"), ex.getMessage());
    }

    @Test
    void testNotFoundWithoutNamespace() {
        McpException ex = McpErrors.notFound("Kafka", "my-cluster", null);

        McpErrorData data = (McpErrorData) ex.getData();
        assertNull(data.namespace());
        assertTrue(ex.getMessage().contains("any namespace"), ex.getMessage());
    }

    @Test
    void testAmbiguousCarriesCandidates() {
        McpException ex = McpErrors.ambiguous("Kafka", "my-cluster", List.of("ns1", "ns2"));

        assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, ex.getJsonRpcErrorCode());
        McpErrorData data = (McpErrorData) ex.getData();
        assertEquals(McpErrorCategory.AMBIGUOUS, data.category());
        assertEquals(List.of("ns1", "ns2"), data.candidates());
        assertTrue(ex.getMessage().contains("Multiple"), ex.getMessage());
        assertTrue(ex.getMessage().contains("ns1"), ex.getMessage());
    }

    @Test
    void testNotFoundWithoutNameOmitsNameFromMessage() {
        McpException ex = McpErrors.notFound("Strimzi operator pods", null, "prod");

        McpErrorData data = (McpErrorData) ex.getData();
        assertNull(data.resourceName());
        assertEquals("prod", data.namespace());
        assertTrue(ex.getMessage().contains("Strimzi operator pods not found in namespace prod"), ex.getMessage());
        assertFalse(ex.getMessage().contains("'"), ex.getMessage());
    }

    @Test
    void testAmbiguousWithoutNameOmitsNameFromMessage() {
        McpException ex = McpErrors.ambiguous("Strimzi operator", null, List.of("ns1", "ns2"));

        McpErrorData data = (McpErrorData) ex.getData();
        assertEquals(McpErrorCategory.AMBIGUOUS, data.category());
        assertEquals(List.of("ns1", "ns2"), data.candidates());
        assertTrue(ex.getMessage().contains("Multiple Strimzi operator"), ex.getMessage());
        assertFalse(ex.getMessage().contains("named"), ex.getMessage());
    }

    @Test
    void testInvalidParams() {
        McpException ex = McpErrors.invalidParams("bad name");

        assertEquals(JsonRpcErrorCodes.INVALID_PARAMS, ex.getJsonRpcErrorCode());
        McpErrorData data = (McpErrorData) ex.getData();
        assertEquals(McpErrorCategory.INVALID_PARAMS, data.category());
        assertEquals("bad name", ex.getMessage());
    }
}
