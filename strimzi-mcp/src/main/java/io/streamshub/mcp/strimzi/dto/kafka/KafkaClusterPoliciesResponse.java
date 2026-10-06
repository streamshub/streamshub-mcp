/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.kafka;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * PodDisruptionBudgets and NetworkPolicies associated with a Kafka cluster.
 *
 * @param clusterName            the Kafka cluster name
 * @param namespace              the Kubernetes namespace
 * @param podDisruptionBudgets   the PDBs for the cluster
 * @param networkPolicies        the NetworkPolicies for the cluster
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaClusterPoliciesResponse(
    @JsonProperty("cluster_name") String clusterName,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("pod_disruption_budgets") List<KafkaPodDisruptionBudgetInfo> podDisruptionBudgets,
    @JsonProperty("network_policies") List<KafkaNetworkPolicyInfo> networkPolicies
) {

    /**
     * Factory method.
     *
     * @param clusterName          cluster name
     * @param namespace            namespace
     * @param podDisruptionBudgets list of PDBs
     * @param networkPolicies      list of NetworkPolicies
     * @return response record
     */
    public static KafkaClusterPoliciesResponse of(
            final String clusterName,
            final String namespace,
            final List<KafkaPodDisruptionBudgetInfo> podDisruptionBudgets,
            final List<KafkaNetworkPolicyInfo> networkPolicies) {
        return new KafkaClusterPoliciesResponse(clusterName, namespace, podDisruptionBudgets, networkPolicies);
    }
}
