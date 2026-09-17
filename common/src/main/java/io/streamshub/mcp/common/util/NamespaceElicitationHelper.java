/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.util;

import io.quarkiverse.mcp.server.Elicitation;
import io.quarkiverse.mcp.server.ElicitationResponse;
import io.quarkiverse.mcp.server.InputRequiredException;
import io.quarkiverse.mcp.server.InputResponses;
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

    /** Schema property name for the elicited namespace selection. */
    private static final String NAMESPACE_PROPERTY = "namespace";

    /** Schema property description shown next to the namespace selector. */
    private static final String NAMESPACE_DESCRIPTION = "Select the namespace";

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
     * Build the disambiguation prompt, naming the actual resource kind from the error data.
     *
     * @param data    the structured error data (may be null)
     * @param context descriptive context for the prompt (e.g. "diagnosed")
     * @return the prompt message
     */
    private static String namespacePrompt(final McpErrorData data, final String context) {
        String kind = data != null && data.resourceKind() != null ? data.resourceKind() : "resource";
        return "Multiple " + kind + " resources exist in different namespaces. Which namespace should be "
            + context + "?";
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
        McpErrorData data = error.getData() instanceof McpErrorData d ? d : null;
        List<String> namespaces = data != null ? data.candidates() : null;
        if (namespaces == null || namespaces.isEmpty()) {
            throw error;
        }

        String selected = DiagnosticHelper.elicitSelection(
            elicitation,
            namespacePrompt(data, context),
            NAMESPACE_PROPERTY,
            NAMESPACE_DESCRIPTION,
            namespaces);

        if (selected != null) {
            return selected;
        }
        throw error;
    }

    /**
     * Resolve namespace ambiguity, adapting to the client's transport (MRTR-aware).
     *
     * <p>Stateful clients elicit via server-initiated request. Stateless clients read a
     * prior elicitation response keyed by {@code key} if present, otherwise this throws
     * {@link InputRequiredException} requesting a namespace selection. A stateless client
     * that did not declare form-mode elicitation falls back to the structured error so it
     * can re-call with an explicit namespace.</p>
     *
     * @param error       the original AMBIGUOUS error
     * @param elicitation the MCP Elicitation interface
     * @param context     descriptive context for the prompt
     * @param key         the MRTR request key (e.g. "namespace")
     * @return the selected namespace
     * @throws McpException            the original error if no candidates / declined / no form mode
     * @throws InputRequiredException  when a stateless client must supply the namespace
     */
    public static String elicitNamespaceMrtr(final McpException error, final Elicitation elicitation,
                                             final String context, final String key) {
        McpErrorData data = error.getData() instanceof McpErrorData d ? d : null;
        List<String> namespaces = data != null ? data.candidates() : null;
        if (namespaces == null || namespaces.isEmpty()) {
            throw error;
        }
        if (elicitation != null && !elicitation.isServerInitiatedRequestSupported()) {
            // Stateless (MRTR) path requires form-mode elicitation. If the client did not declare
            // it, fall back to the structured error so it re-calls with an explicit namespace
            // (rather than throwing IllegalStateException from requestBuilder()).
            if (!elicitation.isFormModeSupported()) {
                throw error;
            }
            InputResponses responses = elicitation.inputResponses();
            if (responses != null && responses.has(key)) {
                ElicitationResponse resp = responses.getElicitationResponse(key);
                if (resp != null && resp.actionAccepted()) {
                    return resp.content().getString(NAMESPACE_PROPERTY);
                }
                throw error;
            }
            throw elicitation.inputRequired()
                .addElicitationRequest(key, DiagnosticHelper.buildSingleSelectRequest(
                    elicitation,
                    namespacePrompt(data, context),
                    NAMESPACE_PROPERTY,
                    NAMESPACE_DESCRIPTION,
                    namespaces))
                .build();
        }
        return elicitNamespace(error, elicitation, context);
    }
}
