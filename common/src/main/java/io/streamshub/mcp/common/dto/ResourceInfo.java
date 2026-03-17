/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Flattened CPU and memory resource requests and limits.
 * Reusable across pod summaries, container details, and any resource that reports resource allocation.
 *
 * @param cpuRequest    the CPU request value (e.g. "1", "500m")
 * @param cpuLimit      the CPU limit value
 * @param memoryRequest the memory request value (e.g. "4Gi")
 * @param memoryLimit   the memory limit value
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResourceInfo(
    @JsonProperty("cpu_request") String cpuRequest,
    @JsonProperty("cpu_limit") String cpuLimit,
    @JsonProperty("memory_request") String memoryRequest,
    @JsonProperty("memory_limit") String memoryLimit
) {
}
