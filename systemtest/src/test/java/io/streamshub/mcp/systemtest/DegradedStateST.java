/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest;

import com.fasterxml.jackson.databind.JsonNode;
import io.fabric8.kubernetes.api.model.Namespace;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.skodjob.kubetest4j.annotations.ClassNamespace;
import io.skodjob.kubetest4j.annotations.InjectResourceManager;
import io.skodjob.kubetest4j.annotations.KubernetesTest;
import io.skodjob.kubetest4j.resources.KubeResourceManager;
import io.streamshub.mcp.systemtest.clients.McpClientFactory;
import io.streamshub.mcp.systemtest.setup.mcp.ConnectivitySetup;
import io.streamshub.mcp.systemtest.setup.mcp.McpServerSetup;
import io.streamshub.mcp.systemtest.setup.strimzi.StrimziSetup;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaConnectTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaConnectorTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaNodePoolTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaTemplates;
import io.strimzi.api.kafka.model.connector.KafkaConnector;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * System tests for MCP tool behavior under degraded Kafka component states.
 * Deploys a Kafka cluster with KafkaConnect, then injects faults (invalid
 * connector configurations) to verify that MCP tools and diagnostics handle
 * unhealthy resources gracefully without crashing.
 */
@KubernetesTest
@DisplayName("Degraded State MCP Tools")
@Epic("Strimzi MCP E2E")
@Feature("Degraded State")
class DegradedStateST extends AbstractST {

    private static final Logger LOGGER = LoggerFactory.getLogger(DegradedStateST.class);
    private static final String CONNECT_CLUSTER_NAME = "mcp-connect";
    private static final String FAILED_CONNECTOR_NAME = "mcp-failed-connector";

    @InjectResourceManager
    KubeResourceManager krm;

    @ClassNamespace(name = Constants.MCP_NAMESPACE)
    static Namespace mcpNamespace;

    @ClassNamespace(name = Constants.STRIMZI_NAMESPACE, labels = {"app=strimzi"})
    static Namespace strimziNamespace;

    @ClassNamespace(name = Constants.KAFKA_NAMESPACE, labels = {"app=strimzi"})
    static Namespace kafkaNamespace;

    private static McpAssured.McpStreamableTestClient mcpClient;

    DegradedStateST() {
    }

    @BeforeAll
    void setup() {
        if (!Environment.SKIP_STRIMZI_INSTALL) {
            String kafkaNs = kafkaNamespace.getMetadata().getName();

            StrimziSetup.deploy(strimziNamespace.getMetadata().getName());

            KafkaTemplates.deployMetricsConfigMap(kafkaNs);

            krm.createOrUpdateResourceWithoutWait(
                KafkaNodePoolTemplates.controllerPool(kafkaNs, "controller-np",
                    Constants.KAFKA_CLUSTER_NAME, 1).build(),
                KafkaNodePoolTemplates.brokerPool(kafkaNs, "broker-np",
                    Constants.KAFKA_CLUSTER_NAME, 1).build());

            krm.createOrUpdateResourceWithWait(
                KafkaTemplates.kafka(kafkaNs, Constants.KAFKA_CLUSTER_NAME, 1).build());

            krm.createOrUpdateResourceWithWait(
                KafkaConnectTemplates.kafkaConnect(
                    kafkaNs, CONNECT_CLUSTER_NAME, Constants.KAFKA_CLUSTER_NAME, 1).build());
        }

        McpServerSetup.deploy(mcpNamespace.getMetadata().getName());

        String mcpUrl = ConnectivitySetup.expose(mcpNamespace.getMetadata().getName());
        mcpClient = McpClientFactory.create(mcpUrl);
    }

    @AfterAll
    static void cleanup() {
        if (mcpClient != null) {
            mcpClient.disconnect();
        }
    }

    // ---- Connector in FAILED state ----

    @Test
    @DisplayName("get_kafka_connector reports FAILED state for invalid connector")
    @Story("Connector Failure")
    void testConnectorFailedState() {
        String kafkaNs = kafkaNamespace.getMetadata().getName();

        krm.createOrUpdateResourceWithoutWait(
            KafkaConnectorTemplates.connector(kafkaNs, FAILED_CONNECTOR_NAME,
                CONNECT_CLUSTER_NAME, "org.apache.kafka.connect.NonExistentConnector", 1).build());

        waitForConnectorCondition(kafkaNs);

        Map<String, Object> args = Map.of(
            "connectorName", FAILED_CONNECTOR_NAME,
            "namespace", kafkaNs);
        mcpClient.when()
            .toolsCall("get_kafka_connector", args, response -> {
                JsonNode root = assertToolSuccess(response);
                LOGGER.info("get_kafka_connector (failed): state={}",
                    root.path("state").asText());

                String state = root.path("state").asText("").toLowerCase(Locale.ROOT);
                assertTrue(state.contains("fail") || state.contains("unassign"),
                    "Connector with invalid class should be in FAILED or UNASSIGNED state, "
                        + "got: " + root.path("state").asText());
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("diagnose_kafka_connector handles failed connector gracefully")
    @Story("Connector Failure")
    void testDiagnoseFailedConnector() {
        String kafkaNs = kafkaNamespace.getMetadata().getName();

        krm.createOrUpdateResourceWithoutWait(
            KafkaConnectorTemplates.connector(kafkaNs, FAILED_CONNECTOR_NAME,
                CONNECT_CLUSTER_NAME, "org.apache.kafka.connect.NonExistentConnector", 1).build());

        waitForConnectorCondition(kafkaNs);

        Map<String, Object> args = Map.of(
            "connectorName", FAILED_CONNECTOR_NAME,
            "namespace", kafkaNs);
        mcpClient.when()
            .toolsCall("diagnose_kafka_connector", args, response -> {
                JsonNode root = assertToolSuccess(response);
                LOGGER.info("diagnose_kafka_connector (failed): steps={}",
                    root.path("steps_completed").size());

                JsonNode steps = root.path("steps_completed");
                assertTrue(steps.isArray() && !steps.isEmpty(),
                    "Diagnostic should complete at least some steps even for failed connector");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("diagnose_kafka_connect handles mixed healthy and failed connectors")
    @Story("Connector Failure")
    void testDiagnoseConnectWithMixedConnectors() {
        String kafkaNs = kafkaNamespace.getMetadata().getName();

        krm.createOrUpdateResourceWithoutWait(
            KafkaConnectorTemplates.connector(kafkaNs, FAILED_CONNECTOR_NAME,
                CONNECT_CLUSTER_NAME, "org.apache.kafka.connect.NonExistentConnector", 1).build());

        waitForConnectorCondition(kafkaNs);

        Map<String, Object> args = Map.of(
            "connectName", CONNECT_CLUSTER_NAME,
            "namespace", kafkaNs);
        mcpClient.when()
            .toolsCall("diagnose_kafka_connect", args, response -> {
                JsonNode root = assertToolSuccess(response);
                LOGGER.info("diagnose_kafka_connect (mixed connectors): steps={}",
                    root.path("steps_completed").size());

                JsonNode steps = root.path("steps_completed");
                assertTrue(steps.isArray() && !steps.isEmpty(),
                    "Diagnostic should complete steps with mixed connector health");

                assertFalse(root.toString().contains("NullPointerException"),
                    "Should not contain NullPointerException in response");
            })
            .thenAssertResults();
    }

    private void waitForConnectorCondition(final String namespace) {
        LOGGER.info("Waiting for KafkaConnector '{}' to have conditions in namespace '{}'",
            FAILED_CONNECTOR_NAME, namespace);
        try {
            krm.kubeClient().getClient()
                .resources(KafkaConnector.class)
                .inNamespace(namespace)
                .withName(FAILED_CONNECTOR_NAME)
                .waitUntilCondition(
                    c -> c != null && c.getStatus() != null
                        && c.getStatus().getConditions() != null
                        && !c.getStatus().getConditions().isEmpty(),
                    3, TimeUnit.MINUTES);
        } catch (Exception e) {
            LOGGER.warn("Timeout waiting for connector conditions, continuing anyway", e);
        }
    }
}
