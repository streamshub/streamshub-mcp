/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.kafka;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Detailed NetworkPolicy information for a Kafka cluster.
 *
 * @param name        the NetworkPolicy resource name
 * @param namespace   the namespace
 * @param component   the Strimzi component (e.g., kafka, entity-operator)
 * @param policyTypes policy types (Ingress, Egress)
 * @param ingressRuleCount number of ingress rules defined
 * @param egressRuleCount  number of egress rules defined
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KafkaNetworkPolicyInfo(
    @JsonProperty("name") String name,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("component") String component,
    @JsonProperty("policy_types") List<String> policyTypes,
    @JsonProperty("ingress_rules_count") Integer ingressRuleCount,
    @JsonProperty("egress_rules_count") Integer egressRuleCount
) {
}
