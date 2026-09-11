/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.util;

import io.quarkiverse.mcp.server.Elicitation;
import io.quarkiverse.mcp.server.McpException;
import io.streamshub.mcp.common.dto.error.McpErrorCategory;
import io.streamshub.mcp.common.dto.error.McpErrorData;
import io.streamshub.mcp.common.service.DiagnosticHelper;

import java.util.List;

/**
 * Helpers for resolving namespace ambiguity via MCP Elicitation.
 *
 * <p>When a Kubernetes resource exists in multiple namespaces, the originating
 * {@link McpException} carries an {@link McpErrorData} with category
 * {@link McpErrorCategory#AMBIGUOUS} and the candidate namespaces. These utilities
 * read that structured data and delegate to {@link DiagnosticHelper#elicitSelection}
 * for the generic Elicitation call.</p>
 */
public final class NamespaceElicitationHelper {

    private NamespaceElicitationHelper() {
    }

    /**
     * Check if an {@link McpException} indicates a resource found in multiple namespaces.
     *
     * @param e the exception
     * @return true if the error carries structured data with the AMBIGUOUS category
     */
    public static boolean isMultipleNamespacesError(final McpException e) {
        return e.getData() instanceof McpErrorData data
            && data.category() == McpErrorCategory.AMBIGUOUS;
    }

    /**
     * Ask the user to select a namespace via MCP Elicitation when a resource exists in
     * multiple namespaces. Reads the candidate namespaces from the exception's structured
     * data and delegates to {@link DiagnosticHelper#elicitSelection}.
     *
     * @param error       the original ambiguity error
     * @param elicitation the MCP Elicitation interface
     * @param context     descriptive context for the prompt (e.g., "diagnosed", "checked for connectivity")
     * @return the selected namespace
     * @throws McpException the original error if no candidates are present,
     *                      elicitation fails, or the user declines
     */
    public static String elicitNamespace(final McpException error,
                                          final Elicitation elicitation,
                                          final String context) {
        List<String> namespaces = error.getData() instanceof McpErrorData data ? data.candidates() : null;
        if (namespaces == null || namespaces.isEmpty()) {
            throw error;
        }

        String selected = DiagnosticHelper.elicitSelection(
            elicitation,
            "The Kafka cluster exists in multiple namespaces. Which namespace should be " + context + "?",
            "namespace",
            "Select the namespace",
            namespaces);

        if (selected != null) {
            return selected;
        }
        throw error;
    }
}
