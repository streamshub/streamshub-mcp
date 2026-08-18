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
import io.quarkiverse.mcp.server.Progress;
import io.quarkiverse.mcp.server.SamplingResponse;
import io.quarkiverse.mcp.server.ToolCallException;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

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
     * Send a progress update to the MCP client.
     *
     * @param progress   the MCP progress (may be null)
     * @param step       the current step number
     * @param totalSteps the total number of steps
     * @param message    descriptive message for this step
     */
    public static void sendProgress(final Progress progress, final int step,
                                     final int totalSteps, final String message) {
        if (progress != null && progress.token().isPresent()) {
            progress.notificationBuilder()
                .setProgress(step)
                .setTotal(totalSteps)
                .setMessage(message)
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
     * Register a push-based cancellation callback for async operations.
     *
     * <p>This method sets up a callback that immediately fires when the MCP client
     * cancels the operation, setting the provided AtomicBoolean to true. This is
     * more responsive than polling with {@link #checkCancellation(Cancellation)}
     * and is suitable for async operations like Sampling and Elicitation that may
     * take seconds to minutes.</p>
     *
     * <p>The callback does not throw — it only sets the flag. Callers should check
     * the flag before and after expensive operations using {@link #checkAsyncCancellation(AtomicBoolean)}.</p>
     *
     * <p>Use {@link #checkCancellation(Cancellation)} for synchronous code paths
     * where polling is sufficient. Use this method for async operations where
     * immediate notification is important to avoid wasted work.</p>
     *
     * @param cancellation  the MCP cancellation (may be null)
     * @param cancelledFlag the flag to set when cancellation occurs (must not be null if cancellation is non-null)
     */
    public static void registerCancellationCallback(final Cancellation cancellation,
                                                     final AtomicBoolean cancelledFlag) {
        if (cancellation != null) {
            cancellation.onCancelled(reason -> {
                LOG.infof("Cancellation received: %s", reason.orElse("no reason provided"));
                cancelledFlag.set(true);
            });
        }
    }

    /**
     * Check if the operation was cancelled during an async operation (sampling/elicitation).
     *
     * <p>This method checks the flag set by {@link #registerCancellationCallback(Cancellation, AtomicBoolean)}
     * and throws if cancellation has occurred. Use this after async operations like Sampling
     * to detect cancellation that occurred during the operation.</p>
     *
     * @param cancelledFlag the flag set by the cancellation callback
     * @throws ToolCallException if the operation was cancelled
     */
    public static void checkAsyncCancellation(final AtomicBoolean cancelledFlag) {
        if (cancelledFlag != null && cancelledFlag.get()) {
            throw new ToolCallException("Operation cancelled");
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
        return elicitSelection(elicitation, message, propertyName, description, options, null);
    }

    /**
     * Ask the user to select a single value from a list via MCP Elicitation with cancellation support.
     *
     * <p>This is a generic Elicitation wrapper usable for any disambiguation
     * (namespace selection, cluster selection, etc.).</p>
     *
     * @param elicitation  the MCP Elicitation interface
     * @param message      the prompt message shown to the user
     * @param propertyName the schema property name (e.g., "namespace")
     * @param description  the property description
     * @param options      the list of options to choose from
     * @param cancelled    optional flag set by push-based cancellation callback (may be null)
     * @return the selected value, or null if the user declined, elicitation failed, or operation was cancelled
     */
    public static String elicitSelection(final Elicitation elicitation,
                                          final String message,
                                          final String propertyName,
                                          final String description,
                                          final List<String> options,
                                          final AtomicBoolean cancelled) {
        if (cancelled != null && cancelled.get()) {
            return null;
        }
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

            if (cancelled != null && cancelled.get()) {
                return null;
            }
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
