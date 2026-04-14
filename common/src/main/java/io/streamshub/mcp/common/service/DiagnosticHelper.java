/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service;

import com.fasterxml.jackson.core.type.TypeReference;
import io.quarkiverse.mcp.server.Cancellation;
import io.quarkiverse.mcp.server.Elicitation;
import io.quarkiverse.mcp.server.ElicitationRequest;
import io.quarkiverse.mcp.server.ElicitationResponse;
import io.quarkiverse.mcp.server.McpLog;
import io.quarkiverse.mcp.server.Progress;
import io.quarkiverse.mcp.server.SamplingResponse;
import io.quarkiverse.mcp.server.ToolCallException;
import org.jboss.logging.Logger;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared utilities for diagnostic service implementations.
 *
 * <p>Provides common helpers for MCP framework interactions (notifications,
 * progress, cancellation), namespace disambiguation via Elicitation,
 * and Sampling response parsing.</p>
 */
public final class DiagnosticHelper {

    private static final Logger LOG = Logger.getLogger(DiagnosticHelper.class);
    private static final Pattern NAMESPACES_PATTERN = Pattern.compile("namespaces: (.+)\\. Please");

    /**
     * Reusable type reference for deserializing JSON maps from Sampling responses.
     */
    public static final TypeReference<Map<String, Object>> MAP_TYPE_REF = new TypeReference<>() { };

    private DiagnosticHelper() {
    }

    /**
     * Send a log-level notification to the MCP client.
     *
     * @param mcpLog  the MCP log (may be null)
     * @param message the notification message
     */
    public static void sendClientNotification(final McpLog mcpLog, final String message) {
        if (mcpLog != null) {
            mcpLog.info(message);
        }
    }

    /**
     * Send a progress update to the MCP client.
     *
     * @param progress   the MCP progress (may be null)
     * @param step       the current step number
     * @param totalSteps the total number of steps
     * @param label      the diagnostic label (e.g., "Diagnostic", "Connectivity diagnostic")
     */
    public static void sendProgress(final Progress progress, final int step,
                                     final int totalSteps, final String label) {
        if (progress != null && progress.token().isPresent()) {
            progress.notificationBuilder()
                .setProgress(step)
                .setTotal(totalSteps)
                .setMessage(String.format("%s step %d/%d", label, step, totalSteps))
                .build()
                .sendAndForget();
        }
    }

    /**
     * Check if the MCP client has cancelled the operation.
     *
     * @param cancellation the MCP cancellation (may be null)
     */
    public static void checkCancellation(final Cancellation cancellation) {
        if (cancellation != null) {
            cancellation.skipProcessingIfCancelled();
        }
    }

    /**
     * Put a value into a map only if it is not null.
     *
     * @param map   the target map
     * @param key   the key
     * @param value the value (skipped if null)
     */
    public static void putIfNotNull(final Map<String, Object> map, final String key,
                                     final Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    /**
     * Check if a {@link ToolCallException} indicates multiple resources
     * found in different namespaces.
     *
     * @param e the exception
     * @return true if the error is a multi-namespace ambiguity
     */
    public static boolean isMultipleNamespacesError(final ToolCallException e) {
        return e.getMessage() != null && e.getMessage().contains("Multiple clusters named");
    }

    /**
     * Ask the user to select a namespace via MCP Elicitation when a resource
     * exists in multiple namespaces.
     *
     * @param error       the original ambiguity error
     * @param elicitation the MCP Elicitation interface
     * @param context     descriptive context for the prompt (e.g., "diagnosed", "checked for connectivity")
     * @return the selected namespace, or throws the original error
     */
    public static String elicitNamespace(final ToolCallException error,
                                          final Elicitation elicitation,
                                          final String context) {
        List<String> namespaces = parseNamespacesFromError(error.getMessage());
        if (namespaces.isEmpty()) {
            throw error;
        }

        try {
            ElicitationResponse response = elicitation.requestBuilder()
                .setMessage("The cluster exists in multiple namespaces. Which namespace should be "
                    + context + "?")
                .addSchemaProperty("namespace",
                    ElicitationRequest.SingleSelectEnumSchema.builder(namespaces)
                        .setDescription("Select the namespace")
                        .setRequired(true)
                        .build())
                .build()
                .sendAndAwait();

            if (response.actionAccepted()) {
                return response.content().getString("namespace");
            }
        } catch (Exception e) {
            LOG.warnf("Elicitation failed: %s", e.getMessage());
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

    /**
     * Safely extract text content from a Sampling response.
     *
     * @param response the Sampling response
     * @return the text content, or null if the response structure is invalid
     */
    public static String extractSamplingText(final SamplingResponse response) {
        if (response == null || response.content() == null) {
            return null;
        }
        return response.content().asText().text();
    }
}
