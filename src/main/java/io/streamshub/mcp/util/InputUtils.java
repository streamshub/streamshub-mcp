/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.util;

import java.util.Locale;

/**
 * Pure-function utilities for normalizing user-supplied input
 * (namespace names, cluster names, etc.) before passing to Kubernetes APIs.
 */
public final class InputUtils {

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
}
