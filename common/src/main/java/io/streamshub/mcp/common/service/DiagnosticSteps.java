/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service;

import java.util.List;

/**
 * The step-reporting convention shared by diagnostic reports.
 *
 * <p>A report has up to three step lists:</p>
 * <ul>
 *   <li>{@code steps_completed}: steps that gathered all their data</li>
 *   <li>{@code steps_degraded}: steps that returned data but hit errors on part of it, as
 *       {@code "step: reason"}. They are not listed in {@code steps_completed}.</li>
 *   <li>{@code steps_failed}: steps that returned no data, as {@code "step: reason"}</li>
 * </ul>
 *
 * <p>An empty list is left out of the report, and the summary message counts only the
 * lists that have entries.</p>
 */
public final class DiagnosticSteps {

    private DiagnosticSteps() {
    }

    /**
     * Returns the list, or null when it is null or empty, so the report leaves it out.
     *
     * @param steps the step list
     * @return the list, or null when there is nothing in it
     */
    public static List<String> nonEmptyOrNull(final List<String> steps) {
        return steps == null || steps.isEmpty() ? null : steps;
    }

    /**
     * Builds the step counts for a report message, for example
     * {@code "5 steps succeeded, 1 step degraded, 1 step failed"}.
     *
     * @param completed the completed steps, or null
     * @param degraded  the degraded steps, or null
     * @param failed    the failed steps, or null
     * @return the step counts; the degraded and failed counts appear only when non-zero
     */
    public static String summary(final List<String> completed, final List<String> degraded,
                                 final List<String> failed) {
        StringBuilder sb = new StringBuilder(count(size(completed), "succeeded"));
        if (size(degraded) > 0) {
            sb.append(", ").append(count(size(degraded), "degraded"));
        }
        if (size(failed) > 0) {
            sb.append(", ").append(count(size(failed), "failed"));
        }
        return sb.toString();
    }

    private static int size(final List<String> steps) {
        return steps != null ? steps.size() : 0;
    }

    private static String count(final int n, final String outcome) {
        return n + (n == 1 ? " step " : " steps ") + outcome;
    }
}
