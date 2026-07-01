/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.systemtest.tools;

import com.fasterxml.jackson.databind.JsonNode;
import io.fabric8.kubernetes.api.model.Namespace;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.Story;
import io.quarkiverse.mcp.server.test.McpAssured;
import io.skodjob.kubetest4j.annotations.ClassNamespace;
import io.skodjob.kubetest4j.annotations.InjectResourceManager;
import io.skodjob.kubetest4j.resources.KubeResourceManager;
import io.streamshub.mcp.systemtest.AbstractST;
import io.streamshub.mcp.systemtest.Constants;
import io.streamshub.mcp.systemtest.Environment;
import io.streamshub.mcp.systemtest.clients.McpClientFactory;
import io.streamshub.mcp.systemtest.setup.mcp.ConnectivitySetup;
import io.streamshub.mcp.systemtest.setup.mcp.McpServerSetup;
import io.streamshub.mcp.systemtest.setup.strimzi.StrimziSetup;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaNodePoolTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaTemplates;
import io.streamshub.mcp.systemtest.templates.strimzi.KafkaUserTemplates;
import io.strimzi.api.kafka.model.kafka.listener.GenericKafkaListenerBuilder;
import io.strimzi.api.kafka.model.kafka.listener.KafkaListenerType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * System tests for KafkaUser MCP tools.
 * Deploys the MCP server and KafkaUsers with various authentication types
 * and ACL configurations, then verifies the tools return correct data
 * without exposing credential secrets.
 */
@Epic("Strimzi MCP E2E")
@Feature("KafkaUser Tools")
class KafkaUserToolsST extends AbstractST {

    private static final Logger LOGGER = LoggerFactory.getLogger(KafkaUserToolsST.class);

    @InjectResourceManager
    KubeResourceManager krm;

    @ClassNamespace(name = Constants.MCP_NAMESPACE)
    static Namespace mcpNamespace;

    @ClassNamespace(name = Constants.STRIMZI_NAMESPACE, labels = {"app=strimzi"})
    static Namespace strimziNamespace;

    @ClassNamespace(name = Constants.KAFKA_NAMESPACE, labels = {"app=strimzi"})
    static Namespace kafkaNamespace;

    private static McpAssured.McpStreamableTestClient mcpClient;

    KafkaUserToolsST() {
    }

    @BeforeAll
    void setup() {
        if (!Environment.SKIP_STRIMZI_INSTALL) {
            String kafkaNs = kafkaNamespace.getMetadata().getName();
            
            StrimziSetup.deploy(strimziNamespace.getMetadata().getName());
            
            krm.createOrUpdateResourceWithoutWait(
                KafkaNodePoolTemplates.controllerPool(kafkaNs, "controller-np",
                    Constants.KAFKA_CLUSTER_NAME, 1).build(),
                KafkaNodePoolTemplates.brokerPool(kafkaNs, "broker-np",
                    Constants.KAFKA_CLUSTER_NAME, 1).build());
            
            krm.createOrUpdateResourceWithWait(
                KafkaTemplates.kafka(kafkaNs, Constants.KAFKA_CLUSTER_NAME, 1)
                    .editSpec()
                        .editKafka()
                            .withNewKafkaAuthorizationSimple()
                            .endKafkaAuthorizationSimple()
                            .withListeners(
                                new GenericKafkaListenerBuilder()
                                    .withType(KafkaListenerType.INTERNAL)
                                    .withName("plain")
                                    .withPort(9092)
                                    .withTls(false)
                                    .build(),
                                new GenericKafkaListenerBuilder()
                                    .withType(KafkaListenerType.INTERNAL)
                                    .withName("scramsha")
                                    .withPort(9093)
                                    .withTls(true)
                                    .withNewKafkaListenerAuthenticationTlsAuth()
                                    .endKafkaListenerAuthenticationTlsAuth()
                                    .build(),
                                new GenericKafkaListenerBuilder()
                                    .withType(KafkaListenerType.INTERNAL)
                                    .withName("tls")
                                    .withPort(9096)
                                    .withTls(true)
                                    .withNewKafkaListenerAuthenticationScramSha512Auth()
                                    .endKafkaListenerAuthenticationScramSha512Auth()
                                    .build()
                            )
                        .endKafka()
                    .endSpec()
                    .build());
            
            krm.createOrUpdateResourceWithWait(
                KafkaUserTemplates.scramUserWithAcls(kafkaNs,
                    KafkaUserTemplates.SCRAM_USER_NAME, Constants.KAFKA_CLUSTER_NAME).build(),
                KafkaUserTemplates.tlsUser(kafkaNs,
                    KafkaUserTemplates.TLS_USER_NAME, Constants.KAFKA_CLUSTER_NAME).build(),
                KafkaUserTemplates.adminUser(kafkaNs,
                    KafkaUserTemplates.ADMIN_USER_NAME, Constants.KAFKA_CLUSTER_NAME).build());
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

    @Test
    @Story("list_kafka_users returns pre-deployed users")
    void testListKafkaUsers() {
        Map<String, Object> args = Map.of(
            "clusterName", Constants.KAFKA_CLUSTER_NAME,
            "namespace", Environment.KAFKA_NAMESPACE);

        mcpClient.when()
            .toolsCall("list_kafka_users", args, response -> {
                assertToolSuccess(response);

                assertTrue(response.content().size() >= 2,
                    "Should have at least 2 content entries (one per user), got "
                        + response.content().size());
                // Each list item is a separate content entry; find both users
                JsonNode scramUser = null;
                JsonNode tlsUser = null;
                for (var entry : response.content()) {
                    String entryJson = entry.asText().text();
                    LOGGER.info("list_kafka_users content entry:\n{}", entryJson);
                    JsonNode node = parseJson(entryJson);
                    JsonNode found = findByName(node, KafkaUserTemplates.SCRAM_USER_NAME);
                    if (found != null) {
                        scramUser = found;
                    }
                    found = findByName(node, KafkaUserTemplates.TLS_USER_NAME);
                    if (found != null) {
                        tlsUser = found;
                    }
                }
                assertNotNull(scramUser, "Should find SCRAM user across content entries");
                assertEquals("scram-sha-512", scramUser.path("authentication").asText());
                assertEquals("simple", scramUser.path("authorization").asText());
                assertTrue(scramUser.path("acl_count").asInt() > 0, "SCRAM user should have ACLs");
                assertEquals("Ready", scramUser.path("readiness").asText());
                assertEquals(Constants.KAFKA_CLUSTER_NAME, scramUser.path("cluster").asText());
                assertNotNull(tlsUser, "Should find TLS user across content entries");
                assertEquals("tls", tlsUser.path("authentication").asText());
                assertEquals("simple", tlsUser.path("authorization").asText());
                assertEquals("Ready", tlsUser.path("readiness").asText());
                assertEquals(Constants.KAFKA_CLUSTER_NAME, tlsUser.path("cluster").asText());
            })
            .thenAssertResults();
    }

    @Test
    @Story("list_kafka_users filtered by nonexistent cluster returns no users")
    void testListKafkaUsersFilteredByCluster() {
        Map<String, Object> args = Map.of(
            "clusterName", "nonexistent-cluster",
            "namespace", Environment.KAFKA_NAMESPACE);

        mcpClient.when()
            .toolsCall("list_kafka_users", args, response -> {
                assertFalse(response.isError(), "Tool call should not return error");
                assertTrue(response.content().isEmpty(),
                    "Should return empty content for nonexistent cluster");
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_kafka_user returns detailed SCRAM user with ACLs and quotas")
    void testGetKafkaUserScram() {
        Map<String, Object> args = Map.of(
            "userName", KafkaUserTemplates.SCRAM_USER_NAME,
            "namespace", Environment.KAFKA_NAMESPACE);

        mcpClient.when()
            .toolsCall("get_kafka_user", args, response -> {
                JsonNode user = assertToolSuccess(response);

                String json = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_user (SCRAM) response:\n{}", json);
                assertEquals(KafkaUserTemplates.SCRAM_USER_NAME, user.path("name").asText());
                assertEquals("scram-sha-512", user.path("authentication").asText());
                assertEquals("simple", user.path("authorization").asText());
                assertEquals("Ready", user.path("readiness").asText());
                // ACL rules should be present for detail
                JsonNode aclRules = user.path("acl_rules");
                assertTrue(aclRules.isArray() && !aclRules.isEmpty(), "Should have ACL rules");
                // Verify ACL structure
                boolean hasTopicRule = false;
                boolean hasGroupRule = false;
                for (JsonNode rule : aclRules) {
                    String resourceType = rule.path("resource_type").asText();
                    if ("topic".equals(resourceType)) {
                        hasTopicRule = true;
                        assertEquals("prefix", rule.path("pattern_type").asText(),
                            "Topic ACL should use prefix pattern");
                        assertTrue(rule.path("operations").isArray(),
                            "Should have operations array");
                    }
                    if ("group".equals(resourceType)) {
                        hasGroupRule = true;
                    }
                }
                assertTrue(hasTopicRule, "Should have topic ACL rule");
                assertTrue(hasGroupRule, "Should have group ACL rule");
                // Quotas should be present
                JsonNode quotas = user.path("quotas");
                assertFalse(quotas.isMissingNode(), "Should have quotas");
                assertEquals(1048576, quotas.path("producer_byte_rate").asInt());
                assertEquals(2097152, quotas.path("consumer_byte_rate").asInt());
                assertEquals(55, quotas.path("request_percentage").asInt());
                // Username and secret should be present
                assertFalse(user.path("username").isMissingNode(), "Should have username");
                assertFalse(user.path("secret_name").isMissingNode(), "Should have secret_name");
                // CRITICAL: verify no secret data is exposed
                assertFalse(json.contains("password"), "Must NOT contain password data");
                assertFalse(json.contains("sasl.jaas.config"), "Must NOT contain JAAS config");
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_kafka_user returns detailed TLS user")
    void testGetKafkaUserTls() {
        Map<String, Object> args = Map.of(
            "userName", KafkaUserTemplates.TLS_USER_NAME,
            "namespace", Environment.KAFKA_NAMESPACE);

        mcpClient.when()
            .toolsCall("get_kafka_user", args, response -> {
                JsonNode user = assertToolSuccess(response);

                String json = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_user (TLS) response:\n{}", json);
                assertEquals(KafkaUserTemplates.TLS_USER_NAME, user.path("name").asText());
                assertEquals("tls", user.path("authentication").asText());
                assertEquals("Ready", user.path("readiness").asText());
                // TLS user should have CN= prefix in username
                String username = user.path("username").asText();
                assertTrue(username.startsWith("CN="),
                    "TLS user's Kafka principal should start with CN=, got: " + username);
                // CRITICAL: verify no certificate data is exposed
                assertFalse(json.contains("BEGIN CERTIFICATE"), "Must NOT contain certificate PEM");
                assertFalse(json.contains("private_key"), "Must NOT contain private key");
                assertFalse(json.contains("ca.crt"), "Must NOT contain CA cert data");
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_kafka_user returns error for non-existent user")
    void testGetKafkaUserNotFound() {
        Map<String, Object> args = Map.of(
            "userName", "non-existent-user",
            "namespace", Environment.KAFKA_NAMESPACE);

        mcpClient.when()
            .toolsCall("get_kafka_user", args, response -> {
                assertToolError(response, "not found", "non-existent-user");
            })
            .thenAssertResults();
    }

    @Test
    @Story("get_kafka_user returns detailed admin user with ACLs")
    void testGetKafkaUserAdmin() {
        Map<String, Object> args = Map.of(
            "userName", KafkaUserTemplates.ADMIN_USER_NAME,
            "namespace", kafkaNamespace.getMetadata().getName());

        mcpClient.when()
            .toolsCall("get_kafka_user", args, response -> {
                JsonNode user = assertToolSuccess(response);

                String json = response.content().getFirst().asText().text();
                LOGGER.info("get_kafka_user (admin) response:\n{}", json);
                assertEquals("scram-sha-512", user.path("authentication").asText(),
                    "Admin user should use scram-sha-512 authentication");
                // ACL rules should be present
                JsonNode aclRules = user.path("acl_rules");
                assertTrue(aclRules.isArray() && !aclRules.isEmpty(),
                    "Admin user should have ACL rules");
                // CRITICAL: verify no secret data is exposed
                assertFalse(json.contains("password"), "Must NOT contain password data");
                assertFalse(json.contains("sasl.jaas.config"), "Must NOT contain JAAS config");
            })
            .thenAssertResults();
    }
}
