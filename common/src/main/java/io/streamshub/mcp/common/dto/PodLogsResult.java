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
 * @param errorCount    the number of lines containing errors or exceptions
 * @param totalLines    the total number of log lines retrieved before filtering
 * @param filteredLines the number of log lines after filtering (equals totalLines when no filter)
 */
public record PodLogsResult(
    List<String> podNames,
    String logs,
    int errorCount,
    int totalLines,
    int filteredLines
) {

    /**
     * Whether any error lines were found in the logs.
     *
     * @return true if errors were detected
     */
    public boolean hasErrors() {
        return errorCount > 0;
    }
}
