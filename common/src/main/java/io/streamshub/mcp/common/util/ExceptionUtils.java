/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.util;

/**
 * Utility methods for exception handling.
 */
public final class ExceptionUtils {

    private ExceptionUtils() {
    }

    /**
     * Walks the cause chain to the root cause and returns its message.
     * Falls back to the root cause's simple class name if the message is null.
     *
     * @param t the throwable to extract the root cause message from
     * @return the root cause message, or simple class name if no message
     */
    public static String rootCauseMessage(final Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName();
    }
}
