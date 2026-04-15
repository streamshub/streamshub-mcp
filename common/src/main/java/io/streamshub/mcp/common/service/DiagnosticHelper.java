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
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * Shared utilities for diagnostic service implementations.
 *
 * <p>Provides common helpers for MCP framework interactions (notifications,
 * progress, cancellation, elicitation) and Sampling response parsing.
 * All methods are generic and reusable across MCP server modules.</p>
 */
public final class DiagnosticHelper {

    private static final Logger LOG = Logger.getLogger(DiagnosticHelper.class);

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
     * Ask the user to select a single value from a list via MCP Elicitation.
     *
     * <p>This is a generic Elicitation wrapper usable for any disambiguation
     * (namespace selection, cluster selection, etc.).</p>
     *
     * @param elicitation  the MCP Elicitation interface
     * @param message      the prompt message shown to the user
     * @param propertyName the schema property name (e.g., "namespace")
     * @param description  the property description
     * @param options      the list of options to choose from
     * @return the selected value, or null if the user declined or elicitation failed
     */
    public static String elicitSelection(final Elicitation elicitation,
                                          final String message,
                                          final String propertyName,
                                          final String description,
                                          final List<String> options) {
        try {
            ElicitationResponse response = elicitation.requestBuilder()
                .setMessage(message)
                .addSchemaProperty(propertyName,
                    ElicitationRequest.SingleSelectEnumSchema.builder(options)
                        .setDescription(description)
                        .setRequired(true)
                        .build())
                .build()
                .sendAndAwait();

            if (response.actionAccepted()) {
                return response.content().getString(propertyName);
            }
        } catch (Exception e) {
            LOG.warnf("Elicitation failed: %s", e.getMessage());
        }
        return null;
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
