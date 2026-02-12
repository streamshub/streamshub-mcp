/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.mcp.util;

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
     * Normalize namespace to handle various input formats.
     * Returns null if no namespace is provided, allowing callers to trigger auto-discovery.
     *
     * @param namespace the raw namespace input
     * @return normalized namespace or null
     */
    public static String normalizeNamespace(String namespace) {
        if (namespace == null || namespace.isBlank() || "null".equalsIgnoreCase(namespace.trim())) {
            return null;
        }
        return namespace.toLowerCase(Locale.ENGLISH).trim();
    }

    /**
     * Normalize cluster name to handle various input formats.
     * Returns null if no cluster name is provided.
     *
     * @param clusterName the raw cluster name input
     * @return normalized cluster name or null
     */
    public static String normalizeClusterName(String clusterName) {
        if (clusterName == null || clusterName.isBlank() || "null".equalsIgnoreCase(clusterName.trim())) {
            return null;
        }
        return clusterName.toLowerCase(Locale.ENGLISH).trim();
    }
}
