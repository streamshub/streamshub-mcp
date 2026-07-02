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
import io.streamshub.mcp.systemtest.resources.strimzi.KafkaMirrorMaker2Type;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        KafkaMirrorMaker2Type.class,
        KafkaUserType.class,
        KafkaRebalanceType.class
    }
)
public abstract class AbstractST {

    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractST.class);

    /**
     * Helper method to jet json object from json string
     *
     * @param json json string
     * @return json object
     */
    protected static JsonNode parseJson(final String json) {
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
     * Core response assertion: logs the response, verifies isError matches
     * expectation (logging at INFO on mismatch), and returns parsed JSON
     * content or {@code null} if the response has no content.
     */
    private static JsonNode assertToolResponse(final ToolResponse response,
                                               final boolean expectError) {
        LOGGER.debug("Tool response: {}", response);
        if (response.isError() != expectError) {
            String content = response.content().isEmpty()
                ? "<empty>" : response.content().getFirst().asText().text();
            LOGGER.error("Unexpected tool response (expected {}): {}",
                expectError ? "error" : "success", content);
        }
        assertEquals(expectError, response.isError(),
            expectError ? "Tool call should return error" : "Tool call should not return error");
        if (response.content().isEmpty() || expectError) {
            return null;
        }
        return parseJson(response.content().getFirst().asText().text());
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
        assertToolResponse(response, true);
        assertFalse(response.content().isEmpty(), "Error response should have content");
        String text = response.content().getFirst().asText().text();
        for (String sub : expectedSubstrings) {
            assertTrue(text.toLowerCase(Locale.ROOT).contains(sub.toLowerCase(Locale.ROOT)),
                "Error message should contain '" + sub + "', got: " + text);
        }
    }

    /**
     * Assert that an MCP tool call succeeded and return the parsed JSON body.
     * Returns {@code null} if the response has no content.
     *
     * @param response the MCP tool call response
     * @return parsed JSON root node, or {@code null} if content is empty
     */
    protected static JsonNode assertToolSuccess(final ToolResponse response) {
        return assertToolResponse(response, false);
    }

    protected static void assertDiagnosticReport(final JsonNode root) {
        JsonNode steps = root.path("steps_completed");
        assertTrue(steps.isArray() && !steps.isEmpty(),
            "steps_completed should be a non-empty array");
        assertFalse(root.path("timestamp").isMissingNode(), "Should have timestamp");
        assertFalse(root.path("message").isMissingNode(), "Should have message");
    }

    protected static void assertPodSummaryResponse(final JsonNode root) {
        assertTrue(root.path("total_pods").asInt() > 0,
            "Should have at least one pod");
        assertTrue(root.path("ready_pods").asInt() > 0,
            "Should have at least one ready pod");
        assertFalse(root.path("health_status").isMissingNode(),
            "Should have health_status");
        assertFalse(root.path("timestamp").isMissingNode(),
            "Should have timestamp");
    }

    protected static void assertLogsResponse(final JsonNode root,
                                             final String resourceNameField,
                                             final String expectedName) {
        assertEquals(expectedName, root.path(resourceNameField).asText(),
            resourceNameField + " should match");
        assertTrue(root.path("pods").isArray() && !root.path("pods").isEmpty(),
            "pods should be a non-empty array");
        assertTrue(root.path("log_lines").isNumber(),
            "log_lines should be a number");
        assertFalse(root.path("timestamp").isMissingNode(),
            "Should have timestamp");
        assertFalse(root.path("message").isMissingNode(),
            "Should have message");
    }

    protected static void assertMetricsResponse(final JsonNode root,
                                                final String nameField,
                                                final String expectedName) {
        assertEquals(expectedName, root.path(nameField).asText(), nameField + " should match");
        assertFalse(root.path("namespace").isMissingNode(), "Should have namespace");
        assertTrue(root.path("categories").isArray(), "categories should be an array");
        assertTrue(root.path("time_series").isArray(), "time_series should be an array");
        assertTrue(root.path("time_series").size() > 0, "time_series should not be empty");
        assertTrue(root.path("metric_count").isNumber(), "metric_count should be a number");
        assertTrue(root.path("metric_count").asInt() > 0, "metric_count should be greater than 0");
        assertTrue(root.path("sample_count").isNumber(), "sample_count should be a number");
        assertFalse(root.path("timestamp").isMissingNode(), "Should have timestamp");
        assertFalse(root.path("message").isMissingNode(), "Should have message");
    }

    protected static void assertNoStackTrace(final String text) {
        assertFalse(text.contains("java.lang."),
            "Response should not contain Java package references");
        assertFalse(text.contains("NullPointerException"),
            "Response should not contain NullPointerException");
        assertFalse(text.contains("\tat "),
            "Response should not contain stack trace frames");
    }

    protected static void assertClusterLogsResponse(final JsonNode root,
                                                     final String expectedClusterName) {
        assertEquals(expectedClusterName, root.path("cluster_name").asText(),
            "cluster_name should match");
        assertTrue(root.path("pods").isArray() && !root.path("pods").isEmpty(),
            "pods should be a non-empty array");
        assertTrue(root.path("log_lines").isNumber(), "log_lines should be a number");
        assertFalse(root.path("timestamp").isMissingNode(), "Should have timestamp");
        assertFalse(root.path("message").isMissingNode(), "Should have message");
    }

    protected static void assertEventsResponse(final JsonNode root,
                                               final String expectedResourceName,
                                               final String expectedNamespace) {
        assertEquals(expectedResourceName, root.path("resource_name").asText(),
            "resource_name should match");
        assertEquals(expectedNamespace, root.path("namespace").asText(),
            "namespace should match");
        assertTrue(root.path("total_events").asInt() >= 0,
            "total_events should be non-negative");
        assertFalse(root.path("timestamp").isMissingNode(),
            "Should have timestamp");
        assertFalse(root.path("message").isMissingNode(),
            "Should have message");
    }
}
