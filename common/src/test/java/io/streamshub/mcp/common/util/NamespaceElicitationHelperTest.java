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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link NamespaceElicitationHelper} structured ambiguity detection.
 */
class NamespaceElicitationHelperTest {

    NamespaceElicitationHelperTest() {
    }

    @Test
    void testIsMultipleNamespacesErrorDetectsAmbiguous() {
        McpException e = McpErrors.ambiguous("Kafka cluster", "my-cluster", List.of("kafka-prod", "kafka-dev"));
        assertTrue(NamespaceElicitationHelper.isMultipleNamespacesError(e));
    }

    @Test
    void testIsMultipleNamespacesErrorFalseForNotFound() {
        McpException e = McpErrors.notFound("Kafka cluster", "my-cluster", null);
        assertFalse(NamespaceElicitationHelper.isMultipleNamespacesError(e));
    }

    @Test
    void testIsMultipleNamespacesErrorFalseWhenNoData() {
        McpException e = new McpException("plain error", JsonRpcErrorCodes.INTERNAL_ERROR);
        assertFalse(NamespaceElicitationHelper.isMultipleNamespacesError(e));
    }

    @Test
    void testElicitNamespaceThrowsOriginalWhenNoCandidates() {
        McpException e = new McpException(
            "ambiguous but empty", JsonRpcErrorCodes.INVALID_PARAMS,
            new McpErrorData(McpErrorCategory.AMBIGUOUS, "Kafka cluster", "my-cluster", null, List.of(), null));

        McpException thrown = assertThrows(McpException.class,
            () -> NamespaceElicitationHelper.elicitNamespace(e, null, "diagnosed"));
        assertSame(e, thrown);
    }
}
