/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.util;

import io.quarkiverse.mcp.server.ToolCallException;

import java.time.Instant;

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
     *   <li>rangeMinutes must be positive</li>
     *   <li>startTime must be before endTime</li>
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

        // Validate rangeMinutes is positive
        if (rangeMinutes != null && rangeMinutes <= 0) {
            throw new ToolCallException(
                "rangeMinutes must be a positive integer, got: " + rangeMinutes);
        }

        // Ensure both startTime and endTime are provided together
        if (startTime != null && endTime == null || startTime == null && endTime != null) {
            throw new ToolCallException(
                "Both startTime and endTime must be specified for absolute time range queries.");
        }

        // Validate startTime and endTime format and ordering
        if (startTime != null) {
            Instant start = InputUtils.parseIso8601(startTime, "startTime");
            Instant end = InputUtils.parseIso8601(endTime, "endTime");
            if (!start.isBefore(end)) {
                throw new ToolCallException(
                    "startTime must be before endTime. Got startTime=" + startTime + ", endTime=" + endTime);
            }
        }
    }
}
