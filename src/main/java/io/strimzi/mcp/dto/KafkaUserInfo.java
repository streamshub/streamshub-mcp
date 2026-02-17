/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.mcp.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Information about a Kafka User.
 *
 * @param name the user name
 * @param namespace the Kubernetes namespace
 * @param kafkaCluster the associated Kafka cluster name
 * @param authentication the authentication type
 * @param authorization the authorization type
 * @param aclRules the list of ACL rules
 * @param status the current status of the user
 */
public record KafkaUserInfo(
    @JsonProperty("name") String name,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("kafka_cluster") String kafkaCluster,
    @JsonProperty("authentication") String authentication,
    @JsonProperty("authorization") String authorization,
    @JsonProperty("acl_rules") List<String> aclRules,
    @JsonProperty("status") String status
) {
    /**
     * Returns a human-readable display name for this user.
     *
     * @return the display name in "name (cluster: cluster, namespace: ns)" format
     */
    public String getDisplayName() {
        return String.format("%s (cluster: %s, namespace: %s)",
            name, kafkaCluster, namespace);
    }
}