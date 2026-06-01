/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service.log;

/**
 * Thrown when a log provider fails to query logs from the backing store.
 */
public class LogQueryException extends RuntimeException {

    /**
     * Creates a new log query exception.
     *
     * @param message description of the query failure
     * @param cause   the underlying exception
     */
    public LogQueryException(final String message, final Throwable cause) {
        super(message, cause);
    }
}