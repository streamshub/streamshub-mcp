/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest;

import com.fasterxml.jackson.databind.JsonNode;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.skodjob.kubetest4j.annotations.ClassNamespace;
import io.skodjob.kubetest4j.annotations.CleanupStrategy;
import io.skodjob.kubetest4j.annotations.InjectResourceManager;
import io.skodjob.kubetest4j.annotations.KubernetesTest;
import io.skodjob.kubetest4j.resources.KubeResourceManager;
import io.streamshub.mcp.systemtest.clients.McpClientFactory;
import io.streamshub.mcp.systemtest.setup.mcp.ConnectivitySetup;
import io.streamshub.mcp.systemtest.setup.mcp.HelmSetup;
import io.streamshub.mcp.systemtest.setup.strimzi.StrimziSetup;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaNodePoolTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaTemplates;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * System tests for Helm chart installation.
 * Verifies that {@code helm install} produces a working MCP server,
 * that configuration overrides are applied, and that {@code helm upgrade}
 * with sensitive namespaces enables RBAC for secret access.
 */
@KubernetesTest(cleanup = CleanupStrategy.AUTOMATIC, collectLogs = true)
@DisplayName("Helm Install")
@Epic("Strimzi MCP E2E")
@Feature("Helm Deployment")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HelmInstallST extends AbstractST {

    private static final Logger LOGGER = LoggerFactory.getLogger(HelmInstallST.class);

    @InjectResourceManager
    KubeResourceManager krm;

    @ClassNamespace(name = Constants.MCP_NAMESPACE)
    static Namespace mcpNamespace;

    @ClassNamespace(name = Constants.STRIMZI_NAMESPACE, labels = {"app=strimzi"})
    static Namespace strimziNamespace;

    @ClassNamespace(name = Constants.KAFKA_NAMESPACE, labels = {"app=strimzi"})
    static Namespace kafkaNamespace;

    private static McpAssured.McpStreamableTestClient mcpClient;

    HelmInstallST() {
    }

    @BeforeAll
    void setup() {
        if (!Environment.SKIP_STRIMZI_INSTALL) {
            String kafkaNs = kafkaNamespace.getMetadata().getName();

            StrimziSetup.deploy(strimziNamespace.getMetadata().getName());

            KafkaTemplates.deployMetricsConfigMap(kafkaNs);

            krm.createOrUpdateResourceWithoutWait(
                KafkaNodePoolTemplates.controllerPool(kafkaNs, "controller-np",
                    Constants.KAFKA_CLUSTER_NAME, 3).build(),
                KafkaNodePoolTemplates.brokerPool(kafkaNs, "broker-np1",
                    Constants.KAFKA_CLUSTER_NAME, 3).build());

            krm.createOrUpdateResourceWithWait(
                KafkaTemplates.kafka(kafkaNs, Constants.KAFKA_CLUSTER_NAME, 3).build());
        }

        HelmSetup.builder(mcpNamespace.getMetadata().getName())
            .install();

        String mcpUrl = ConnectivitySetup.expose(mcpNamespace.getMetadata().getName());
        mcpClient = McpClientFactory.create(mcpUrl);
    }

    @AfterAll
    static void cleanup() {
        if (mcpClient != null) {
            mcpClient.disconnect();
        }
        try {
            HelmSetup.uninstall(Constants.HELM_RELEASE_NAME, Constants.MCP_NAMESPACE);
        } catch (Exception e) {
            LOGGER.warn("Helm uninstall failed (may already be cleaned up): {}", e.getMessage());
        }
    }

    @Test
    @Order(1)
    @DisplayName("Helm install produces a working MCP server")
    @Story("Helm Install")
    void testHelmInstallAndToolCall() {
        Map<String, Object> args = Map.of("namespace", Environment.KAFKA_NAMESPACE);
        mcpClient.when()
            .toolsCall("list_kafka_clusters", args, response -> {
                assertFalse(response.isError(), "list_kafka_clusters should not return error");
                assertFalse(response.content().isEmpty(), "Should return at least one content entry");

                String json = response.content().getFirst().asText().text();
                LOGGER.info("list_kafka_clusters response (length={}):\n{}", json.length(), json);

                JsonNode root = parseJson(json);
                JsonNode cluster = findCluster(root, Environment.KAFKA_CLUSTER_NAME);
                assertNotNull(cluster,
                    "Should find cluster '" + Environment.KAFKA_CLUSTER_NAME + "' in response");

                assertEquals(Environment.KAFKA_NAMESPACE, cluster.path("namespace").asText(),
                    "Namespace should match");
                assertEquals("Ready", cluster.path("readiness").asText(),
                    "Cluster should be Ready");
            })
            .thenAssertResults();
    }

    @Test
    @Order(2)
    @DisplayName("Helm upgrade with sensitive.namespaces enables certificate access")
    @Story("Sensitive RBAC")
    void testHelmSensitiveNamespaces() {
        String mcpNs = mcpNamespace.getMetadata().getName();

        mcpClient.disconnect();

        HelmSetup.upgrade(Constants.HELM_RELEASE_NAME, mcpNs)
            .withSet("image.repository", Environment.MCP_IMAGE.split(":")[0])
            .withSet("image.tag", Environment.MCP_IMAGE.contains(":")
                ? Environment.MCP_IMAGE.split(":")[1] : "latest")
            .withSet("image.pullPolicy", "IfNotPresent")
            .withSet("sensitive.namespaces[0]", Environment.KAFKA_NAMESPACE)
            .run();

        String mcpUrl = ConnectivitySetup.expose(mcpNs);
        mcpClient = McpClientFactory.create(mcpUrl);

        Map<String, Object> args = Map.of(
            "clusterName", Environment.KAFKA_CLUSTER_NAME,
            "namespace", Environment.KAFKA_NAMESPACE);

        mcpClient.when()
            .toolsCall("get_kafka_cluster_certificates", args, response -> {
                assertFalse(response.isError(),
                    "get_kafka_cluster_certificates should not return error after sensitive RBAC is enabled");

                String json = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_cluster_certificates response:\n{}", json);

                JsonNode root = parseJson(json);
                assertTrue(root.has("certificates") || root.has("cluster_ca") || root.isArray(),
                    "Should contain certificate metadata");

                assertFalse(json.contains("BEGIN CERTIFICATE"),
                    "Should not expose raw certificate PEM data");
                assertFalse(json.contains("private_key"),
                    "Should not expose private key data");
            })
            .thenAssertResults();
    }

    @Test
    @Order(3)
    @DisplayName("Helm values override generates correct env vars")
    @Story("Config Override")
    void testHelmConfigOverride() {
        String mcpNs = mcpNamespace.getMetadata().getName();

        mcpClient.disconnect();

        HelmSetup.upgrade(Constants.HELM_RELEASE_NAME, mcpNs)
            .withSet("image.repository", Environment.MCP_IMAGE.split(":")[0])
            .withSet("image.tag", Environment.MCP_IMAGE.contains(":")
                ? Environment.MCP_IMAGE.split(":")[1] : "latest")
            .withSet("image.pullPolicy", "IfNotPresent")
            .withSet("mcp.logLevel", "DEBUG")
            .withSet("mcp.log.tailLines", "50")
            .run();

        Deployment deployment = KubeResourceManager.get().kubeClient().getClient()
            .apps().deployments()
            .inNamespace(mcpNs)
            .withName(Constants.HELM_RELEASE_NAME)
            .get();

        assertNotNull(deployment, "Deployment should exist");

        List<EnvVar> envVars = deployment.getSpec().getTemplate().getSpec()
            .getContainers().getFirst().getEnv();

        assertEnvVar(envVars, "QUARKUS_LOG_CATEGORY__IO_STREAMSHUB_MCP__LEVEL", "DEBUG");
        assertEnvVar(envVars, "MCP_LOG_TAIL_LINES", "50");

        String mcpUrl = ConnectivitySetup.expose(mcpNs);
        mcpClient = McpClientFactory.create(mcpUrl);

        Map<String, Object> args = Map.of("namespace", Environment.KAFKA_NAMESPACE);
        mcpClient.when()
            .toolsCall("list_kafka_clusters", args, response -> {
                assertFalse(response.isError(), "Server should be functional after config override");
            })
            .thenAssertResults();
    }

    private static JsonNode findCluster(final JsonNode root, final String name) {
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

    private static void assertEnvVar(final List<EnvVar> envVars, final String name,
                                      final String expectedValue) {
        EnvVar found = envVars.stream()
            .filter(e -> name.equals(e.getName()))
            .findFirst()
            .orElse(null);
        assertNotNull(found, "Env var " + name + " should exist");
        assertEquals(expectedValue, found.getValue(),
            "Env var " + name + " should have value " + expectedValue);
    }
}
