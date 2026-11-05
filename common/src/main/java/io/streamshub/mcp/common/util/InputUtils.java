/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.util;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Pure-function utilities for normalizing user-supplied input
 * (namespace names, cluster names, etc.) before passing to Kubernetes APIs.
 */
public final class InputUtils {

    /**
     * Kubernetes DNS-1123 subdomain name pattern.
     * Must consist of lowercase alphanumeric characters, '-', or '.',
     * and must start and end with an alphanumeric character.
     */
    private static final Pattern K8S_NAME_PATTERN =
        Pattern.compile("^[a-z0-9]([a-z0-9.\\-]*[a-z0-9])?$");

    private static final int K8S_NAME_MAX_LENGTH = 253;

    /** Characters Kafka allows in a topic name. */
    private static final Pattern KAFKA_TOPIC_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9._\\-]+$");

    private static final int KAFKA_TOPIC_NAME_MAX_LENGTH = 249;

    private InputUtils() {
        // Utility class — no instantiation
    }

    /**
     * Normalize a user-supplied input string (namespace, cluster name, etc.).
     * Returns null if the input is blank or the literal string "null",
     * allowing callers to trigger auto-discovery.
     *
     * @param input the raw input
     * @return normalized lowercase trimmed value, or null
     */
    public static String normalizeInput(String input) {
        if (input == null || input.isBlank() || "null".equalsIgnoreCase(input.trim())) {
            return null;
        }
        return input.toLowerCase(Locale.ENGLISH).trim();
    }

    /**
     * Normalize a case-sensitive name such as a Kafka topic name. Like
     * {@link #normalizeInput(String)} it returns null for blank input or the literal string
     * "null", but it only trims and keeps the original case.
     *
     * @param input the raw input
     * @return the trimmed value, or null
     */
    public static String normalizeName(String input) {
        if (input == null || input.isBlank() || "null".equalsIgnoreCase(input.trim())) {
            return null;
        }
        return input.trim();
    }

    /**
     * Check whether a string is a valid Kubernetes resource name (DNS-1123 subdomain).
     *
     * @param name the name to check
     * @return true when the name is a valid Kubernetes resource name
     */
    public static boolean isValidK8sName(String name) {
        return name != null && name.length() <= K8S_NAME_MAX_LENGTH
            && K8S_NAME_PATTERN.matcher(name).matches();
    }

    /**
     * Validate that a string is a legal Kafka topic name: 1 to 249 characters from
     * {@code [a-zA-Z0-9._-]}, and not {@code .} or {@code ..}. Unlike a Kubernetes resource
     * name it may contain uppercase letters and underscores (for example {@code __consumer_offsets}).
     *
     * @param name  the name to validate
     * @param label a human-readable label for error messages
     * @throws io.quarkiverse.mcp.server.McpException if the name is not valid
     */
    public static void validateKafkaTopicName(String name, String label) {
        if (name == null) {
            return;
        }
        if (name.length() > KAFKA_TOPIC_NAME_MAX_LENGTH) {
            throw McpErrors.invalidParams("Invalid " + label + " '" + name
                + "': exceeds maximum length of " + KAFKA_TOPIC_NAME_MAX_LENGTH + " characters");
        }
        if (".".equals(name) || "..".equals(name) || !KAFKA_TOPIC_NAME_PATTERN.matcher(name).matches()) {
            throw McpErrors.invalidParams("Invalid " + label + " '" + name
                + "': may only contain letters, digits, '.', '_' and '-'");
        }
    }

    /**
     * Validate that a string is a valid Kubernetes resource name (DNS-1123 subdomain).
     * Must consist of lowercase alphanumeric characters, '-', or '.', start and end
     * with an alphanumeric character, and be at most 253 characters.
     *
     * @param name  the name to validate
     * @param label a human-readable label for error messages (e.g., "cluster name", "namespace")
     * @throws io.quarkiverse.mcp.server.McpException if the name is not valid
     */
    public static void validateK8sName(String name, String label) {
        if (name == null) {
            return;
        }
        if (name.length() > K8S_NAME_MAX_LENGTH) {
            throw McpErrors.invalidParams(
                "Invalid " + label + " '" + name + "': exceeds maximum length of " + K8S_NAME_MAX_LENGTH + " characters");
        }
        if (!isValidK8sName(name)) {
            throw McpErrors.invalidParams(
                "Invalid " + label + " '" + name + "': must consist of lowercase alphanumeric characters, '-', or '.', "
                    + "and must start and end with an alphanumeric character");
        }
    }

    /**
     * Parse an ISO 8601 timestamp string into an {@link Instant}.
     *
     * @param value     the timestamp string to parse
     * @param paramName a human-readable label for error messages (e.g., "startTime")
     * @return the parsed Instant
     * @throws io.quarkiverse.mcp.server.McpException if the value is null or cannot be parsed
     */
    public static Instant parseIso8601(String value, String paramName) {
        if (value == null) {
            throw McpErrors.invalidParams(
                "Invalid " + paramName + " format: null."
                    + " Expected ISO 8601 format (e.g., 2025-01-15T10:00:00Z).");
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw McpErrors.invalidParams(
                "Invalid " + paramName + " format: '" + value
                    + "'. Expected ISO 8601 format (e.g., 2025-01-15T10:00:00Z).");
        }
    }
}
