/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.skodjob.kubetest4j.annotations.CleanupStrategy;
import io.skodjob.kubetest4j.annotations.KubernetesTest;
import io.skodjob.kubetest4j.annotations.LogCollectionStrategy;
import io.skodjob.kubetest4j.resources.ClusterRoleBindingType;
import io.skodjob.kubetest4j.resources.ClusterRoleType;
import io.skodjob.kubetest4j.resources.ConfigMapType;
import io.skodjob.kubetest4j.resources.DeploymentType;
import io.skodjob.kubetest4j.resources.JobType;
import io.skodjob.kubetest4j.resources.KubeResourceManager;
import io.skodjob.kubetest4j.resources.RoleBindingType;
import io.skodjob.kubetest4j.resources.RoleType;
import io.skodjob.kubetest4j.resources.SecretType;
import io.skodjob.kubetest4j.resources.ServiceAccountType;
import io.skodjob.kubetest4j.resources.ServiceType;
import io.streamshub.mcp.systemtest.resources.strimzi.KafkaBridgeType;
import io.streamshub.mcp.systemtest.resources.strimzi.KafkaConnectType;
import io.streamshub.mcp.systemtest.resources.strimzi.KafkaConnectorType;
import io.streamshub.mcp.systemtest.resources.strimzi.KafkaNodePoolType;
import io.streamshub.mcp.systemtest.resources.strimzi.KafkaRebalanceType;
import io.streamshub.mcp.systemtest.resources.strimzi.KafkaTopicType;
import io.streamshub.mcp.systemtest.resources.strimzi.KafkaType;
import io.streamshub.mcp.systemtest.resources.strimzi.KafkaUserType;
import io.strimzi.api.kafka.model.bridge.KafkaBridge;
import io.strimzi.api.kafka.model.connect.KafkaConnect;
import io.strimzi.api.kafka.model.connector.KafkaConnector;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.mirrormaker2.KafkaMirrorMaker2;
import io.strimzi.api.kafka.model.nodepool.KafkaNodePool;
import io.strimzi.api.kafka.model.rebalance.KafkaRebalance;
import io.strimzi.api.kafka.model.topic.KafkaTopic;
import io.strimzi.api.kafka.model.user.KafkaUser;
import org.junit.jupiter.api.TestInstance;

/**
 * Base class for all system tests. Registers kubetest4j resource types
 * so the framework knows how to create, wait for readiness, and clean up resources.
 * All setup classes use {@link KubeResourceManager#get()} singleton directly.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@KubernetesTest(
    cleanup = CleanupStrategy.AUTOMATIC,
    collectLogs = true,
    logCollectionStrategy = LogCollectionStrategy.ON_FAILURE,
    collectPreviousLogs = true,
    collectNamespacedResources = {
        "pods", "services", "configmaps", "secrets", "deployments",
        Kafka.RESOURCE_SINGULAR,
        KafkaNodePool.RESOURCE_SINGULAR,
        KafkaTopic.RESOURCE_SINGULAR,
        KafkaUser.RESOURCE_SINGULAR,
        KafkaConnect.RESOURCE_SINGULAR,
        KafkaConnector.RESOURCE_SINGULAR,
        KafkaBridge.RESOURCE_SINGULAR,
        KafkaMirrorMaker2.RESOURCE_SINGULAR,
        KafkaRebalance.RESOURCE_SINGULAR
    },
    resourceTypes = {
        DeploymentType.class,
        ServiceType.class,
        ServiceAccountType.class,
        ClusterRoleType.class,
        ClusterRoleBindingType.class,
        RoleType.class,
        RoleBindingType.class,
        JobType.class,
        ConfigMapType.class,
        SecretType.class,
        // Strimzi custom resource types
        KafkaType.class,
        KafkaNodePoolType.class,
        KafkaTopicType.class,
        KafkaBridgeType.class,
        KafkaConnectType.class,
        KafkaConnectorType.class,
        KafkaUserType.class,
        KafkaRebalanceType.class
    }
)
public abstract class AbstractST {

    /**
     * Helper method to jet json object from json string
     *
     * @param json json string
     * @return json object
     */
    static JsonNode parseJson(final String json) {
        final ObjectMapper mapper = new ObjectMapper();
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new AssertionError("Failed to parse JSON response: " + json, e);
        }
    }

    static JsonNode findByName(final JsonNode root, final String name) {
        if (root.isArray()) {
            for (JsonNode node : root) {
                if (name.equals(node.path("name").asText(""))) {
                    return node;
                }
            }
        } else if (name.equals(root.path("name").asText(""))) {
            return root;
        }
        return null;
    }
}
