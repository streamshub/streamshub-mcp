/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.mcp.server.ToolResponse;
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

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /**
     * Find a JSON node by its "name" field in a root that may be an array or single object.
     *
     * @param root JSON root (array or object)
     * @param name value to match against the "name" field
     * @return the matching node, or {@code null} if not found
     */
    protected static JsonNode findByName(final JsonNode root, final String name) {
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

    /**
     * Assert that an MCP tool call returned an error whose message contains all
     * of the given substrings (case-insensitive).
     *
     * @param response           the MCP tool call response
     * @param expectedSubstrings substrings that must appear in the error message
     */
    protected static void assertToolError(final ToolResponse response,
                                          final String... expectedSubstrings) {
        assertTrue(response.isError(), "Tool call should return error");
        assertFalse(response.content().isEmpty(), "Error response should have content");
        String text = response.content().getFirst().asText().text();
        for (String sub : expectedSubstrings) {
            assertTrue(text.toLowerCase(Locale.ROOT).contains(sub.toLowerCase(Locale.ROOT)),
                "Error message should contain '" + sub + "', got: " + text);
        }
    }

    /**
     * Assert that an MCP tool call succeeded and return the parsed JSON body.
     *
     * @param response the MCP tool call response
     * @return parsed JSON root node
     */
    protected static JsonNode assertToolSuccess(final ToolResponse response) {
        assertFalse(response.isError(), "Tool call should not return error");
        assertFalse(response.content().isEmpty(), "Response should have content");
        return parseJson(response.content().getFirst().asText().text());
    }
}
