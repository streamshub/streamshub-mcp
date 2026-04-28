/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service;

/**
 * Thrown when a Kubernetes API query fails (e.g., RBAC denial,
 * network error, API server unavailable).
 */
public class KubernetesQueryException extends RuntimeException {

    /**
     * Creates a new query exception.
     *
     * @param message context about what was being queried
     * @param cause   the original exception from the Kubernetes client
     */
    public KubernetesQueryException(String message, Throwable cause) {
        super(message, cause);
    }
}
