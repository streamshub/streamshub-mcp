/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.util;

import io.quarkiverse.mcp.server.Elicitation;
import io.quarkiverse.mcp.server.ToolCallException;
import io.streamshub.mcp.common.service.DiagnosticHelper;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helpers for resolving namespace ambiguity via MCP Elicitation.
 *
 * <p>When a Kubernetes resource exists in multiple namespaces, these utilities
 * parse the error message and delegate to {@link DiagnosticHelper#elicitSelection}
 * for the generic Elicitation call.</p>
 */
public final class NamespaceElicitationHelper {

    private static final Pattern NAMESPACES_PATTERN = Pattern.compile("namespaces: (.+)\\. Please");

    private NamespaceElicitationHelper() {
    }

    /**
     * Check if a {@link ToolCallException} indicates multiple Kafka clusters
     * found in different namespaces.
     *
     * @param e the exception
     * @return true if the error is a multi-namespace ambiguity
     */
    public static boolean isMultipleNamespacesError(final ToolCallException e) {
        return e.getMessage() != null && e.getMessage().contains("Multiple clusters named");
    }

    /**
     * Ask the user to select a namespace via MCP Elicitation when a Kafka cluster
     * exists in multiple namespaces. Parses the namespace list from the Strimzi
     * error message and delegates to {@link DiagnosticHelper#elicitSelection}.
     *
     * @param error       the original ambiguity error
     * @param elicitation the MCP Elicitation interface
     * @param context     descriptive context for the prompt (e.g., "diagnosed", "checked for connectivity")
     * @return the selected namespace
     * @throws ToolCallException the original error if namespaces cannot be parsed,
     *                           elicitation fails, or the user declines
     */
    public static String elicitNamespace(final ToolCallException error,
                                          final Elicitation elicitation,
                                          final String context) {
        List<String> namespaces = parseNamespacesFromError(error.getMessage());
        if (namespaces.isEmpty()) {
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

    /**
     * Parse namespace names from a "Multiple clusters named 'x' found in namespaces: a, b" error.
     *
     * @param message the error message
     * @return list of namespace names
     */
    public static List<String> parseNamespacesFromError(final String message) {
        if (message == null) {
            return List.of();
        }
        Matcher matcher = NAMESPACES_PATTERN.matcher(message);
        if (matcher.find()) {
            return Arrays.stream(matcher.group(1).split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        }
        return List.of();
    }
}
