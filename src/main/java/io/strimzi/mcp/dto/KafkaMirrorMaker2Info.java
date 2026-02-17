/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Information about a Kafka MirrorMaker2 instance.
 *
 * @param name the MirrorMaker2 instance name
 * @param namespace the Kubernetes namespace
 * @param version the MirrorMaker2 version
 * @param replicas the number of replicas
 * @param sourceClusters the list of source cluster aliases
 * @param targetClusters the list of target cluster aliases
 * @param status the current status of the MirrorMaker2 instance
 */
public record KafkaMirrorMaker2Info(
    @JsonProperty("name") String name,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("version") String version,
    @JsonProperty("replicas") Integer replicas,
    @JsonProperty("source_clusters") List<String> sourceClusters,
    @JsonProperty("target_clusters") List<String> targetClusters,
    @JsonProperty("status") String status
) {
    /**
     * Returns a human-readable display name for this MirrorMaker2 instance.
     *
     * @return the display name in "name (namespace: ns)" format
     */
    public String getDisplayName() {
        return String.format("%s (namespace: %s)", name, namespace);
    }
}