/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service.metrics;

/**
 * Thrown when a metrics provider fails to query metrics from the backing store.
 */
public class MetricsQueryException extends RuntimeException {

    /**
     * Creates a new metrics query exception.
     *
     * @param message description of the query failure
     */
    public MetricsQueryException(final String message) {
        super(message);
    }

    /**
     * Creates a new metrics query exception with a cause.
     *
     * @param message description of the query failure
     * @param cause   the underlying exception
     */
    public MetricsQueryException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
