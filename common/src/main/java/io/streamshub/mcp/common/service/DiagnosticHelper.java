/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.mcp.server.Cancellation;
import io.quarkiverse.mcp.server.Elicitation;
import io.quarkiverse.mcp.server.ElicitationRequest;
import io.quarkiverse.mcp.server.ElicitationResponse;
import io.quarkiverse.mcp.server.InputRequiredException;
import io.quarkiverse.mcp.server.InputResponses;
import io.quarkiverse.mcp.server.MrtrRequest;
import io.quarkiverse.mcp.server.Progress;
import io.quarkiverse.mcp.server.Sampling;
import io.quarkiverse.mcp.server.SamplingMessage;
import io.quarkiverse.mcp.server.SamplingResponse;
import io.quarkiverse.mcp.server.ToolCallException;
import io.streamshub.mcp.common.util.InputUtils;
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
     * Build an ElicitationRequest for single-select from a list of options.
     *
     * <p>This is a reusable builder for both stateful (server-initiated via
     * {@link #elicitSelection}) and stateless (MRTR InputRequiredException)
     * elicitation paths. The request includes a message and a required single-select
     * enum schema of the provided options.</p>
     *
     * @param elicitation  the MCP Elicitation interface
     * @param message      the prompt message shown to the user
     * @param propertyName the schema property name (e.g., "namespace")
     * @param description  the property description
     * @param options      the list of options to choose from
     * @return the built ElicitationRequest (ready for .sendAndAwait() or for .addElicitationRequest())
     */
    public static ElicitationRequest buildSingleSelectRequest(final Elicitation elicitation,
                                                               final String message,
                                                               final String propertyName,
                                                               final String description,
                                                               final List<String> options) {
        return elicitation.requestBuilder()
            .setMessage(message)
            .addSchemaProperty(propertyName,
                ElicitationRequest.SingleSelectEnumSchema.builder(options)
                    .setDescription(description)
                    .setRequired(true)
                    .build())
            .build();
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
            ElicitationResponse response = buildSingleSelectRequest(
                elicitation, message, propertyName, description, options)
                .sendAndAwait();

            if (cancelled != null && cancelled.get()) {
                return null;
            }
            if (response.actionAccepted()) {
                return response.content().getString(propertyName);
            }
        } catch (InputRequiredException e) {
            throw e;
        } catch (Exception e) {
            LOG.warnf("Elicitation failed: %s", e.getMessage());
        }
        return null;
    }

    /**
     * Resolve the effective namespace for an MRTR round-trip, adapting to transport.
     *
     * <p>For a stateless carrier whose prior round carried a resolved namespace in
     * {@code requestState}, return it (so the analysis-response round does not re-elicit);
     * otherwise return the supplied namespace. Stateful carriers always return the supplied
     * namespace. Required because {@code InputResponses} does not accumulate across round-trips.</p>
     *
     * <p>{@code requestState} is client-supplied and therefore untrusted (a client may tamper with
     * the echoed value), so it is validated as a Kubernetes namespace name at this MRTR boundary
     * before use. A tampered or malformed value is rejected with {@code invalidParams} rather than
     * flowing into a Kubernetes query. This value is never authoritative for access control: the
     * actual authorization is enforced by Kubernetes RBAC on every query, exactly as it is for a
     * namespace passed directly as a tool argument.</p>
     *
     * @param carrier   the MRTR request (Sampling or Elicitation); may be null
     * @param namespace the namespace supplied by the tool caller (may be null)
     * @return the namespace to use for gathering
     * @throws io.quarkiverse.mcp.server.McpException if the {@code requestState} namespace is malformed
     */
    public static String effectiveNamespace(final MrtrRequest carrier, final String namespace) {
        if (carrier != null && !carrier.isServerInitiatedRequestSupported()) {
            String state = InputUtils.normalizeInput(carrier.requestState());
            // normalizeInput() already returns null for blank/"null" input, so a non-null
            // result is guaranteed non-blank.
            if (state != null) {
                // requestState is echoed back by the client and thus attacker-controlled; reject a
                // tampered/garbage value at the boundary instead of passing it to a Kubernetes call.
                InputUtils.validateK8sName(state, "namespace");
                return state;
            }
        }
        return namespace;
    }

    /**
     * Send a server-initiated Sampling request and return the extracted text response.
     *
     * <p>Shared stateful (SSE) sampling logic used by both
     * {@code BaseDiagnosticService.performSampling} and {@link #analysisSamplingMrtr}. Returns
     * {@code null} when sampling is null/unsupported, the operation is cancelled (checked before
     * and after {@code sendAndAwait()}), or the call fails. {@link InputRequiredException} is
     * rethrown so it can never be swallowed.</p>
     *
     * @param sampling     the MCP Sampling interface (may be null)
     * @param mapper       the ObjectMapper for JSON serialization
     * @param systemPrompt the system prompt for the LLM
     * @param data         the data map to serialize as JSON input
     * @param maxTokens    maximum tokens for the response
     * @param cancelled    optional flag set by push-based cancellation callback (may be null)
     * @param logger       the caller's logger, used for failure warnings
     * @return the extracted text, or null when sampling is unavailable, cancelled, or fails
     */
    public static String sendSampling(final Sampling sampling, final ObjectMapper mapper,
            final String systemPrompt, final Map<String, Object> data, final int maxTokens,
            final AtomicBoolean cancelled, final Logger logger) {
        if (sampling == null || !sampling.isSupported()) {
            return null;
        }
        if (cancelled != null && cancelled.get()) {
            return null;
        }
        try {
            String json = mapper.writeValueAsString(data);
            SamplingResponse response = sampling.requestBuilder()
                .setSystemPrompt(systemPrompt)
                .addMessage(SamplingMessage.withUserRole(json))
                .setMaxTokens(maxTokens)
                .build()
                .sendAndAwait();
            if (cancelled != null && cancelled.get()) {
                return null;
            }
            return extractSamplingText(response);
        } catch (InputRequiredException e) {
            throw e;
        } catch (Exception e) {
            logger.warnf("Sampling failed: %s: %s", e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    /**
     * Perform an analysis Sampling request, adapting to the client's transport (MRTR-aware).
     *
     * <p>Stateful clients with sampling support send a server-initiated request and await the
     * response; the {@code cancelled} flag short-circuits the call (returning {@code null}) when
     * set before or after {@code sendAndAwait()}. Stateless clients read a prior keyed response if
     * present, else throw {@link InputRequiredException} requesting it (which must propagate
     * uncaught) — but skip that request (returning {@code null}) when {@code cancelled} is already
     * set, so a cancelled diagnostic never asks the client for more LLM work. Returns {@code null}
     * when sampling is null/unsupported, serialization fails, or the operation was cancelled.</p>
     *
     * @param sampling     the MCP Sampling interface (may be null)
     * @param mapper       the ObjectMapper for JSON serialization
     * @param systemPrompt the system prompt for the LLM
     * @param data         the data map to serialize as JSON input
     * @param maxTokens    maximum tokens for the response
     * @param key          the MRTR request key (e.g., "analysis")
     * @param requestState the request state to preserve across round-trips (may be null)
     * @param cancelled    optional flag set by push-based cancellation callback (may be null)
     * @param logger       the caller's logger, used for serialization failure warnings
     * @return the analysis text, or null when sampling is unavailable, fails, or is cancelled
     */
    public static String analysisSamplingMrtr(final Sampling sampling, final ObjectMapper mapper,
            final String systemPrompt, final Map<String, Object> data, final int maxTokens,
            final String key, final String requestState, final AtomicBoolean cancelled,
            final Logger logger) {
        if (sampling == null) {
            return null;
        }
        if (sampling.isServerInitiatedRequestSupported()) {
            return sendSampling(sampling, mapper, systemPrompt, data, maxTokens, cancelled, logger);
        }
        if (!sampling.isSupported()) {
            return null;
        }
        if (cancelled != null && cancelled.get()) {
            return null;
        }
        InputResponses responses = sampling.inputResponses();
        if (responses.has(key)) {
            return extractSamplingText(responses.getSamplingResponse(key));
        }
        try {
            String json = mapper.writeValueAsString(data);
            InputRequiredException.Builder builder = sampling.inputRequired()
                .addSamplingRequest(key, sampling.requestBuilder()
                    .setSystemPrompt(systemPrompt)
                    .addMessage(SamplingMessage.withUserRole(json))
                    .setMaxTokens(maxTokens)
                    .build());
            // setRequestState() rejects null (Objects.requireNonNull), but the builder permits a
            // null request state when an input request is present. Only set it when non-null so a
            // tool with no namespace to preserve (e.g. compare_kafka_clusters, or an auto-detected
            // namespace) does not NPE instead of raising the MRTR input_required signal.
            if (requestState != null) {
                builder.setRequestState(requestState);
            }
            throw builder.build();
        } catch (JsonProcessingException e) {
            logger.warnf("Failed to serialize analysis data: %s", e.getMessage());
            return null;
        }
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
