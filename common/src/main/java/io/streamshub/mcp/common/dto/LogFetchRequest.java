/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.dto;

import java.util.List;

/**
 * Per-pod request passed to a {@link io.streamshub.mcp.common.service.log.LogCollectorProvider}.
 *
 * <p>Carries exactly the inputs a provider needs to fetch one pod's logs. Orchestration-only
 * concerns ({@code cancelCheck}, {@code progressCallback}) stay in {@link LogCollectionParams}
 * and are not exposed to providers.</p>
 *
 * @param namespace    the Kubernetes namespace of the pod
 * @param podName      the name of the pod
 * @param tailLines    the number of lines to tail per container
 * @param sinceSeconds optional relative time range in seconds; null for no time restriction
 * @param previous     if true, retrieve logs from the previous container instance
 * @param filter       optional filter: "errors", "warnings", or a regex pattern
 * @param keywords     optional list of keywords for line matching (case-insensitive)
 * @param startTime    optional absolute start time in ISO 8601 format (use with endTime)
 * @param endTime      optional absolute end time in ISO 8601 format (use with startTime)
 * @param container    optional container name to narrow collection to a single container;
 *                     null or blank means all containers
 */
public record LogFetchRequest(
    String namespace,
    String podName,
    int tailLines,
    Integer sinceSeconds,
    Boolean previous,
    String filter,
    List<String> keywords,
    String startTime,
    String endTime,
    String container
) {

    /**
     * Build a per-pod request from the shared collection options.
     *
     * @param namespace the Kubernetes namespace of the pod
     * @param podName   the name of the pod
     * @param options   the shared log collection options
     * @return a request carrying the per-pod identity and the options' filtering fields
     */
    public static LogFetchRequest from(final String namespace, final String podName,
                                       final LogCollectionParams options) {
        return new LogFetchRequest(namespace, podName, options.tailLines(),
            options.sinceSeconds(), options.previous(), options.filter(),
            options.keywords(), options.startTime(), options.endTime(), options.container());
    }
}
