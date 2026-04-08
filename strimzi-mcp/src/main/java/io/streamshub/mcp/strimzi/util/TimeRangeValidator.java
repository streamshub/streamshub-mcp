/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.util;

import io.quarkiverse.mcp.server.ToolCallException;

/**
 * Utility class for validating time range parameters in metrics queries.
 */
public final class TimeRangeValidator {

    private TimeRangeValidator() {
        // Utility class - prevent instantiation
    }

    /**
     * Validates time range parameters to ensure they are used correctly.
     * <p>
     * Rules:
     * <ul>
     *   <li>Cannot specify both rangeMinutes and absolute time range (startTime/endTime)</li>
     *   <li>If using absolute time range, both startTime and endTime must be provided</li>
     * </ul>
     *
     * @param rangeMinutes relative time range in minutes (optional)
     * @param startTime    absolute start time in ISO 8601 format (optional)
     * @param endTime      absolute end time in ISO 8601 format (optional)
     * @throws ToolCallException if validation fails
     */
    public static void validateTimeRangeParameters(final Integer rangeMinutes,
                                                    final String startTime,
                                                    final String endTime) {
        // Ensure only one time range method is used
        if (rangeMinutes != null && (startTime != null || endTime != null)) {
            throw new ToolCallException(
                "Cannot specify both rangeMinutes and absolute time range (startTime/endTime). "
                    + "Use rangeMinutes for relative ranges or startTime/endTime for absolute ranges.");
        }

        // Ensure both startTime and endTime are provided together
        if (startTime != null && endTime == null || startTime == null && endTime != null) {
            throw new ToolCallException(
                "Both startTime and endTime must be specified for absolute time range queries.");
        }
    }
}
