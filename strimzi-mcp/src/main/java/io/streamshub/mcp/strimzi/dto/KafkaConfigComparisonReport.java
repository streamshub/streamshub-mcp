/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * Report from comparing the effective configuration of two Kafka clusters.
 * Contains both cluster configs and optional LLM-generated analysis via Sampling.
 *
 * <p>Each config field is null when the gathering step failed
 * (recorded in {@code stepsFailed}).</p>
 *
 * @param cluster1Config effective configuration of the first cluster
 * @param cluster2Config effective configuration of the second cluster
 * @param analysis       LLM-generated comparison analysis via Sampling, or null
 * @param stepsCompleted steps that completed successfully
 * @param stepsFailed    steps that failed with error descriptions
 * @param timestamp      when this report was generated
 * @param message        human-readable summary of the comparison run
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaConfigComparisonReport(
    @JsonProperty("cluster1_config") KafkaEffectiveConfigResponse cluster1Config,
    @JsonProperty("cluster2_config") KafkaEffectiveConfigResponse cluster2Config,
    @JsonProperty("analysis") String analysis,
    @JsonProperty("steps_completed") List<String> stepsCompleted,
    @JsonProperty("steps_failed") List<String> stepsFailed,
    @JsonProperty("timestamp") Instant timestamp,
    @JsonProperty("message") String message
) {

    /**
     * Creates a comparison report with gathered data and step tracking.
     *
     * @param cluster1Config config of the first cluster, or null if gathering failed
     * @param cluster2Config config of the second cluster, or null if gathering failed
     * @param analysis       LLM analysis text or null
     * @param stepsCompleted steps that succeeded
     * @param stepsFailed    steps that failed with reasons
     * @return a new comparison report
     */
    public static KafkaConfigComparisonReport of(final KafkaEffectiveConfigResponse cluster1Config,
                                                 final KafkaEffectiveConfigResponse cluster2Config,
                                                 final String analysis,
                                                 final List<String> stepsCompleted,
                                                 final List<String> stepsFailed) {
        String name1 = cluster1Config != null ? cluster1Config.name() : "unknown";
        String name2 = cluster2Config != null ? cluster2Config.name() : "unknown";
        int completed = stepsCompleted != null ? stepsCompleted.size() : 0;
        int failed = stepsFailed != null ? stepsFailed.size() : 0;

        String msg;
        if (failed == 0) {
            msg = String.format("Comparison of '%s' and '%s' completed: %d steps succeeded",
                name1, name2, completed);
        } else {
            msg = String.format("Comparison of '%s' and '%s' completed: %d steps succeeded, %d steps failed",
                name1, name2, completed, failed);
        }

        return new KafkaConfigComparisonReport(cluster1Config, cluster2Config, analysis,
            stepsCompleted, stepsFailed, Instant.now(), msg);
    }
}
