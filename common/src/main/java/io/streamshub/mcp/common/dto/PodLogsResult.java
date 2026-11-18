/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.dto;

import java.util.List;

/**
 * Result of collecting logs from a list of pods.
 *
 * @param podNames      the names of pods logs were collected from
 * @param logs          the concatenated log content (filtered if a filter was applied)
 * @param errorCount    the number of lines with an ERROR/FATAL level or an exception/error class name
 *                      ({@code LogFilterUtils.ERRORS_REGEX})
 * @param totalLines    the total number of log lines retrieved before filtering
 * @param filteredLines the number of log lines after filtering (equals totalLines when no filter)
 * @param hasMore       whether more log lines are available beyond the requested tail limit
 * @param failedPods    the number of pods whose log fetch failed
 * @param warnings      per-pod failure details and notes on limits the log provider applied
 *                      (empty when all pods succeeded and no limit applied)
 */
public record PodLogsResult(
    List<String> podNames,
    String logs,
    int errorCount,
    int totalLines,
    int filteredLines,
    boolean hasMore,
    int failedPods,
    List<String> warnings
) {

    /**
     * Whether any error lines were found in the logs or any pod fetches failed.
     *
     * @return true if errors were detected or pod fetches failed
     */
    public boolean hasErrors() {
        return errorCount > 0 || failedPods > 0;
    }
}
