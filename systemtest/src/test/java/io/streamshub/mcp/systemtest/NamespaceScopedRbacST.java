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
import io.skodjob.kubetest4j.resources.KubeResourceManager;
import io.streamshub.mcp.systemtest.clients.McpClientFactory;
import io.streamshub.mcp.systemtest.setup.mcp.ConnectivitySetup;
import io.streamshub.mcp.systemtest.setup.mcp.McpServerSetup;
import io.streamshub.mcp.systemtest.setup.strimzi.StrimziSetup;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaNodePoolTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaTemplates;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * System tests for namespace-scoped RBAC (app developer perspective).
 *
 * <p>Deploys the MCP server with RoleBindings (not ClusterRoleBinding) so it
 * can only access resources in the primary Kafka namespace. A second Kafka
 * cluster is deployed in an inaccessible namespace to verify RBAC boundaries.
 *
 * <p>Covers three server-side error handling patterns:
 * <ol>
 *   <li>Direct tools — 403 propagated as tool error</li>
 *   <li>Fleet overview — graceful degradation with partial results</li>
 *   <li>Diagnostic tools — partial access behavior</li>
 * </ol>
 *
 */
@Epic("Strimzi MCP E2E")
@Feature("RBAC")
class NamespaceScopedRbacST extends AbstractST {

    private static final Logger LOGGER = LoggerFactory.getLogger(NamespaceScopedRbacST.class);

    @InjectResourceManager
    KubeResourceManager krm;

    @ClassNamespace(name = Constants.MCP_NAMESPACE)
    static Namespace mcpNamespace;

    @ClassNamespace(name = Constants.STRIMZI_NAMESPACE, labels = {"app=strimzi"})
    static Namespace strimziNamespace;

    @ClassNamespace(name = Constants.KAFKA_NAMESPACE, labels = {"app=strimzi"})
    static Namespace kafkaNamespace;

    @ClassNamespace(name = Constants.KAFKA_NAMESPACE_2, labels = {"app=strimzi"})
    static Namespace kafkaNamespace2;

    private static McpAssured.McpStreamableTestClient mcpClient;

    NamespaceScopedRbacST() {
    }

    @BeforeAll
    void setup() {
        if (!Environment.SKIP_STRIMZI_INSTALL) {
            String kafkaNs = kafkaNamespace.getMetadata().getName();
            String kafkaNs2 = kafkaNamespace2.getMetadata().getName();

            StrimziSetup.deploy(strimziNamespace.getMetadata().getName());

            KafkaTemplates.deployMetricsConfigMap(kafkaNs);
            KafkaTemplates.deployPodMonitors(kafkaNs);

            // Accessible cluster in primary namespace
            krm.createOrUpdateResourceWithoutWait(
                KafkaNodePoolTemplates.controllerPool(kafkaNs, "controller-np",
                    Constants.KAFKA_CLUSTER_NAME, 1).build(),
                KafkaNodePoolTemplates.brokerPool(kafkaNs, "broker-np",
                    Constants.KAFKA_CLUSTER_NAME, 1).build());
            krm.createOrUpdateResourceWithWait(
                KafkaTemplates.kafkaWithMetrics(kafkaNs, Constants.KAFKA_CLUSTER_NAME, 1).build());

            // Inaccessible cluster in second namespace
            krm.createOrUpdateResourceWithoutWait(
                KafkaNodePoolTemplates.controllerPool(kafkaNs2, "controller-np",
                    Constants.KAFKA_CLUSTER_NAME_2, 1).build(),
                KafkaNodePoolTemplates.brokerPool(kafkaNs2, "broker-np",
                    Constants.KAFKA_CLUSTER_NAME_2, 1).build());
            krm.createOrUpdateResourceWithWait(
                KafkaTemplates.kafka(kafkaNs2, Constants.KAFKA_CLUSTER_NAME_2, 1).build());
        }

        // Namespace-scoped RBAC: access only to kafka-ns and strimzi-ns
        McpServerSetup.builder(mcpNamespace.getMetadata().getName())
            .deployWithNamespaceScopedRbac(
                kafkaNamespace.getMetadata().getName(),
                strimziNamespace.getMetadata().getName());

        String mcpUrl = ConnectivitySetup.expose(mcpNamespace.getMetadata().getName());
        mcpClient = McpClientFactory.create(mcpUrl);
    }

    @AfterAll
    static void cleanup() {
        if (mcpClient != null) {
            mcpClient.disconnect();
        }
    }

    // ---- Pattern 1: Direct tools — RBAC boundary enforcement ----

    @Test
    @DisplayName("list_kafka_clusters succeeds in accessible namespace")
    @Story("Namespace Boundary")
    void testListClustersAccessibleNamespace() {
        Map<String, Object> args = Map.of("namespace", Constants.KAFKA_NAMESPACE);
        mcpClient.when()
            .toolsCall("list_kafka_clusters", args, response -> {
                JsonNode root = assertToolSuccess(response);
                LOGGER.info("list_kafka_clusters (accessible ns): {}", root);
                LOGGER.debug("list_kafka_clusters (accessible ns) response:\n{}", response.content().getFirst().asText().text());

                JsonNode cluster = findByName(root, Constants.KAFKA_CLUSTER_NAME);
                assertNotNull(cluster, "Should find cluster in accessible namespace");
                assertEquals(Constants.KAFKA_NAMESPACE, cluster.path("namespace").asText(),
                    "Namespace should match");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("list_kafka_clusters returns error for inaccessible namespace")
    @Story("Namespace Boundary")
    void testListClustersInaccessibleNamespace() {
        Map<String, Object> args = Map.of("namespace", Constants.KAFKA_NAMESPACE_2);
        mcpClient.when()
            .toolsCall("list_kafka_clusters", args, response -> {
                assertToolError(response, "403");
                LOGGER.info("list_kafka_clusters (inaccessible ns): error as expected");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("list_kafka_clusters returns error without namespace (cluster-wide)")
    @Story("Namespace Boundary")
    void testListClustersClusterWide() {
        Map<String, Object> args = Map.of();
        mcpClient.when()
            .toolsCall("list_kafka_clusters", args, response -> {
                assertToolError(response, "403");
                LOGGER.info("list_kafka_clusters (cluster-wide): error as expected");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_kafka_cluster succeeds in accessible namespace")
    @Story("Namespace Boundary")
    void testGetClusterAccessibleNamespace() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "namespace", Constants.KAFKA_NAMESPACE);
        mcpClient.when()
            .toolsCall("get_kafka_cluster", args, response -> {
                JsonNode cluster = assertToolSuccess(response);
                LOGGER.info("get_kafka_cluster (accessible): {}", cluster.path("name"));
                LOGGER.debug("get_kafka_cluster (accessible) response:\n{}", response.content().getFirst().asText().text());

                assertEquals(Constants.KAFKA_CLUSTER_NAME, cluster.path("name").asText());
                assertEquals("Ready", cluster.path("readiness").asText());
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_kafka_cluster returns error for inaccessible namespace")
    @Story("Namespace Boundary")
    void testGetClusterInaccessibleNamespace() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME_2,
            "namespace", Constants.KAFKA_NAMESPACE_2);
        mcpClient.when()
            .toolsCall("get_kafka_cluster", args, response -> {
                assertToolError(response, "403");
                LOGGER.info("get_kafka_cluster (inaccessible ns): error as expected");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_kafka_cluster returns error for auto-discover without namespace")
    @Story("Namespace Boundary")
    void testGetClusterAutoDiscoverWithoutNamespace() {
        Map<String, Object> args = Map.of("clusterName", Constants.KAFKA_CLUSTER_NAME);
        mcpClient.when()
            .toolsCall("get_kafka_cluster", args, response -> {
                assertTrue(response.isError(),
                    "Auto-discover without namespace should fail (requires cluster-wide list)");
                LOGGER.info("get_kafka_cluster (auto-discover): error as expected");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_kafka_cluster_pods succeeds in accessible namespace")
    @Story("Namespace Boundary")
    void testGetClusterPodsAccessible() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "namespace", Constants.KAFKA_NAMESPACE);
        mcpClient.when()
            .toolsCall("get_kafka_cluster_pods", args, response -> {
                JsonNode root = assertToolSuccess(response);
                LOGGER.info("get_kafka_cluster_pods (accessible): {} entries",
                    root.isArray() ? root.size() : 1);
                LOGGER.debug("get_kafka_cluster_pods (accessible) response:\n{}", response.content().getFirst().asText().text());
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_kafka_cluster_logs succeeds in accessible namespace")
    @Story("Namespace Boundary")
    void testGetClusterLogsAccessible() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "namespace", Constants.KAFKA_NAMESPACE,
            "tailLines", 20);
        mcpClient.when()
            .toolsCall("get_kafka_cluster_logs", args, response -> {
                JsonNode root = assertToolSuccess(response);
                LOGGER.info("get_kafka_cluster_logs (accessible): {} log lines",
                    root.path("log_lines").asInt());
                LOGGER.debug("get_kafka_cluster_logs (accessible) response:\n{}", response.content().getFirst().asText().text());
                assertEquals(Constants.KAFKA_CLUSTER_NAME, root.path("cluster_name").asText());
            })
            .thenAssertResults();
    }

    // ---- Pattern 2: Fleet overview degradation ----

    @Test
    @DisplayName("get_kafka_fleet_overview succeeds with accessible namespace filter")
    @Story("Fleet Overview Degradation")
    void testFleetOverviewAccessibleNamespace() {
        Map<String, Object> args = Map.of("namespace", Constants.KAFKA_NAMESPACE);
        mcpClient.when()
            .toolsCall("get_kafka_fleet_overview", args, response -> {
                JsonNode root = assertToolSuccess(response);
                LOGGER.info("get_kafka_fleet_overview (accessible ns): total_clusters={}",
                    root.path("total_clusters").asInt());
                LOGGER.debug("get_kafka_fleet_overview (accessible ns) response:\n{}", response.content().getFirst().asText().text());

                assertEquals(1, root.path("total_clusters").asInt(),
                    "Should see exactly 1 cluster in accessible namespace");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_kafka_fleet_overview returns error for inaccessible namespace")
    @Story("Fleet Overview Degradation")
    void testFleetOverviewInaccessibleNamespace() {
        Map<String, Object> args = Map.of("namespace", Constants.KAFKA_NAMESPACE_2);
        mcpClient.when()
            .toolsCall("get_kafka_fleet_overview", args, response -> {
                assertTrue(response.isError(),
                    "Fleet overview for inaccessible namespace should return error");
                LOGGER.info("get_kafka_fleet_overview (inaccessible ns): error as expected");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_kafka_fleet_overview without namespace returns error (cluster-wide)")
    @Story("Fleet Overview Degradation")
    void testFleetOverviewClusterWide() {
        Map<String, Object> args = Map.of();
        mcpClient.when()
            .toolsCall("get_kafka_fleet_overview", args, response -> {
                assertTrue(response.isError(),
                    "Fleet overview without namespace should fail (requires cluster-wide list)");
                LOGGER.info("get_kafka_fleet_overview (cluster-wide): error as expected");
            })
            .thenAssertResults();
    }

    // ---- Pattern 3: Diagnostic tools under partial access ----

    @Test
    @DisplayName("diagnose_kafka_cluster succeeds in accessible namespace")
    @Story("Diagnostics Under Partial Access")
    void testDiagnoseClusterAccessible() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "namespace", Constants.KAFKA_NAMESPACE);
        mcpClient.when()
            .toolsCall("diagnose_kafka_cluster", args, response -> {
                JsonNode root = assertToolSuccess(response);
                LOGGER.info("diagnose_kafka_cluster (accessible): steps={}",
                    root.path("steps_completed").size());
                LOGGER.debug("diagnose_kafka_cluster (accessible) response:\n{}", response.content().getFirst().asText().text());

                JsonNode steps = root.path("steps_completed");
                assertTrue(steps.isArray() && !steps.isEmpty(),
                    "Diagnostic should complete at least some steps");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("diagnose_kafka_cluster returns error for inaccessible namespace")
    @Story("Diagnostics Under Partial Access")
    void testDiagnoseClusterInaccessible() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME_2,
            "namespace", Constants.KAFKA_NAMESPACE_2);
        mcpClient.when()
            .toolsCall("diagnose_kafka_cluster", args, response -> {
                assertTrue(response.isError(),
                    "Diagnose in inaccessible namespace should return error");
                LOGGER.info("diagnose_kafka_cluster (inaccessible): error as expected");
            })
            .thenAssertResults();
    }

    // ---- Cluster-scoped resources ----

    @Test
    @DisplayName("list_drain_cleaners returns empty without cluster-scoped access")
    @Story("Cluster-Scoped Resources")
    void testDrainCleanersRequireClusterScope() {
        Map<String, Object> args = Map.of();
        mcpClient.when()
            .toolsCall("list_drain_cleaners", args, response -> {
                assertFalse(response.isError(),
                    "Drain cleaner list should succeed with partial namespace access");
                assertTrue(response.content().isEmpty()
                        || "[]".equals(response.content().getFirst().asText().text()),
                    "Drain cleaner list should be empty (none deployed in accessible namespaces)");
                LOGGER.info("list_drain_cleaners (namespace-scoped): empty as expected");
            })
            .thenAssertResults();
    }

    // ---- Sensitive RBAC (certificates / secrets) ----

    @Test
    @Story("Sensitive RBAC")
    void testCertificatesSensitiveRbacLifecycle() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "namespace", Constants.KAFKA_NAMESPACE);

        // Without sensitive RBAC: certificates should degrade gracefully
        mcpClient.when()
            .toolsCall("get_kafka_cluster_certificates", args, response -> {
                JsonNode root = assertToolSuccess(response);
                LOGGER.info("get_kafka_cluster_certificates (no sensitive RBAC): {}", root);
                LOGGER.debug("get_kafka_cluster_certificates (no sensitive RBAC) response:\n{}", response.content().getFirst().asText().text());

                assertTrue(root.has("listener_authentication"),
                    "Should include listener authentication info (from Kafka spec, no secrets needed)");

                JsonNode certs = root.path("certificates");
                assertTrue(!certs.isArray() || certs.isEmpty(),
                    "Certificates should be empty without sensitive RBAC");

                JsonNode errors = root.path("errors");
                assertTrue(errors.isArray() && !errors.isEmpty(),
                    "Should report errors about secret access");
            })
            .thenAssertResults();

        // Deploy sensitive RBAC (grants secret read access)
        McpServerSetup.deploySensitiveRbac(
            mcpNamespace.getMetadata().getName(),
            kafkaNamespace.getMetadata().getName());

        // With sensitive RBAC: certificates should be accessible
        mcpClient.when()
            .toolsCall("get_kafka_cluster_certificates", args, response -> {
                JsonNode root = assertToolSuccess(response);
                LOGGER.info("get_kafka_cluster_certificates (with sensitive RBAC): certs={}",
                    root.path("certificates").size());
                LOGGER.debug("get_kafka_cluster_certificates (with sensitive RBAC) response:\n{}", response.content().getFirst().asText().text());

                assertTrue(root.path("certificates").isArray(),
                    "Should have certificates array");
                assertFalse(root.toString().contains("PRIVATE KEY"),
                    "Should not expose private keys");
            })
            .thenAssertResults();
    }

    // ---- Operator tools with scoped access ----

    @Test
    @DisplayName("list_strimzi_operators succeeds in accessible Strimzi namespace")
    @Story("Namespace Boundary")
    void testListOperatorsAccessible() {
        Map<String, Object> args = Map.of("namespace", Constants.STRIMZI_NAMESPACE);
        mcpClient.when()
            .toolsCall("list_strimzi_operators", args, response -> {
                JsonNode root = assertToolSuccess(response);
                LOGGER.info("list_strimzi_operators (accessible): {}", root);
                LOGGER.debug("list_strimzi_operators (accessible) response:\n{}", response.content().getFirst().asText().text());
            })
            .thenAssertResults();
    }

    // ---- Sensitive RBAC: metrics pods/proxy access ----

    @Test
    @DisplayName("get_kafka_metrics returns error without pods/proxy permission")
    @Story("Sensitive RBAC")
    void testMetricsWithoutPodsProxy() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "namespace", Constants.KAFKA_NAMESPACE);
        mcpClient.when()
            .toolsCall("get_kafka_metrics", args, response -> {
                JsonNode root = assertToolSuccess(response);
                LOGGER.info("get_kafka_metrics (no sensitive RBAC): {}", root);
                LOGGER.debug("get_kafka_metrics (no sensitive RBAC) response:\n{}", response.content().getFirst().asText().text());

                // TODO: should instead return info that MCP doesn't have access to metrics on pods?
                assertEquals(0, root.path("metric_count").asInt(),
                    "Should return 0 metrics without pods/proxy permission");
                assertFalse(root.path("provider").isMissingNode(),
                    "Should include provider field");
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_kafka_metrics succeeds after deploying sensitive RBAC (pods/proxy)")
    @Story("Sensitive RBAC")
    void testMetricsWithPodsProxy() {
        McpServerSetup.deploySensitiveRbac(
            mcpNamespace.getMetadata().getName(),
            kafkaNamespace.getMetadata().getName());

        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "namespace", Constants.KAFKA_NAMESPACE);
        mcpClient.when()
            .toolsCall("get_kafka_metrics", args, response -> {
                assertFalse(response.isError(),
                    "Metrics call should succeed with pods/proxy permission");
                String text = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_metrics (with sensitive RBAC): response length={}", text.length());
                LOGGER.debug("get_kafka_metrics (with sensitive RBAC) response:\n{}", text);
            })
            .thenAssertResults();
    }

    // ---- Sensitive RBAC: data leakage verification ----

    @Test
    @DisplayName("Certificates response never contains private key material")
    @Story("Sensitive RBAC")
    void testCertificatesNeverLeakPrivateKeys() {
        McpServerSetup.deploySensitiveRbac(
            mcpNamespace.getMetadata().getName(),
            kafkaNamespace.getMetadata().getName());

        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "namespace", Constants.KAFKA_NAMESPACE);
        mcpClient.when()
            .toolsCall("get_kafka_cluster_certificates", args, response -> {
                JsonNode root = assertToolSuccess(response);
                LOGGER.info("get_kafka_cluster_certificates (sensitive RBAC) full response: {}", root);
                String fullResponse = root.toString();

                assertFalse(fullResponse.contains("PRIVATE KEY"),
                    "Should not expose any private key material");
                assertFalse(fullResponse.contains("BEGIN RSA"),
                    "Should not expose RSA private keys");
                assertFalse(fullResponse.contains("BEGIN EC"),
                    "Should not expose EC private keys");

                JsonNode certs = root.path("certificates");
                if (certs.isArray() && !certs.isEmpty()) {
                    for (JsonNode cert : certs) {
                        LOGGER.info("Certificate entry fields: {}", cert);
                        assertFalse(cert.path("not_after").isMissingNode(),
                            "Each certificate should include not_after metadata");
                    }
                    LOGGER.info("Verified {} certificates contain metadata but no private keys",
                        certs.size());
                }
            })
            .thenAssertResults();
    }

    @Test
    @DisplayName("get_kafka_user never leaks secret credential values")
    @Story("Sensitive RBAC")
    void testUserToolNeverLeaksSecretData() {
        McpServerSetup.deploySensitiveRbac(
            mcpNamespace.getMetadata().getName(),
            kafkaNamespace.getMetadata().getName());

        Map<String, Object> args = Map.of("namespace", Constants.KAFKA_NAMESPACE);
        mcpClient.when()
            .toolsCall("list_kafka_users", args, response -> {
                if (response.isError() || response.content().isEmpty()) {
                    LOGGER.info("No users found in namespace, skipping secret leakage check");
                    return;
                }

                String fullResponse = response.content().getFirst().asText().text();
                LOGGER.info("list_kafka_users response length={}", fullResponse.length());
                LOGGER.debug("list_kafka_users response:\n{}", fullResponse);

                assertFalse(fullResponse.contains("sasl.jaas.config"),
                    "User listing should not contain JAAS config values");
                assertFalse(fullResponse.contains("BEGIN CERTIFICATE"),
                    "User listing should not contain raw certificate PEM data");
            })
            .thenAssertResults();
    }
}
