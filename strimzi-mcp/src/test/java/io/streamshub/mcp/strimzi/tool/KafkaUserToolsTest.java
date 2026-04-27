/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.tool;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.common.dto.ConditionInfo;
import io.streamshub.mcp.strimzi.dto.KafkaUserResponse;
import io.streamshub.mcp.strimzi.service.KafkaUserService;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * MCP integration tests for KafkaUser tools.
 */
@QuarkusTest
class KafkaUserToolsTest {

    @ConfigProperty(name = "quarkus.http.test-port")
    int testPort;

    @InjectMock
    KafkaUserService userService;

    private McpAssured.McpSseTestClient client;

    KafkaUserToolsTest() {
    }

    @BeforeEach
    void setUp() {
        McpAssured.baseUri = URI.create("http://localhost:" + testPort);
        client = McpAssured.newConnectedSseClient();
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.disconnect();
        }
    }

    /**
     * Verify list_kafka_users returns user summaries.
     */
    @Test
    void testListKafkaUsers() {
        when(userService.listUsers(null, "my-cluster")).thenReturn(List.of(
            KafkaUserResponse.summary("alice", "kafka", "my-cluster",
                "scram-sha-512", "simple", 3, "alice",
                "alice", "Ready",
                List.of(ConditionInfo.of("Ready", "True", null, null, null)))
        ));

        client.when()
            .toolsCall("list_kafka_users",
                Map.of("clusterName", "my-cluster"), response -> {
                    assertFalse(response.isError());
                    String json = response.content().getFirst().asText().text();
                    assertTrue(json.contains("alice"));
                    assertTrue(json.contains("scram-sha-512"));
                    assertTrue(json.contains("my-cluster"));
                    assertTrue(json.contains("\"acl_count\":3"));
                })
            .thenAssertResults();
    }

    /**
     * Verify list_kafka_users returns empty list when no users exist.
     */
    @Test
    void testListKafkaUsersEmpty() {
        when(userService.listUsers(null, null)).thenReturn(List.of());

        client.when()
            .toolsCall("list_kafka_users", Map.of(), response -> {
                assertFalse(response.isError());
            })
            .thenAssertResults();
    }

    /**
     * Verify get_kafka_user returns detailed user with ACL rules and quotas.
     */
    @Test
    void testGetKafkaUser() {
        when(userService.getUser(null, "alice")).thenReturn(
            KafkaUserResponse.of("alice", "kafka", "my-cluster",
                "scram-sha-512", "simple", 2,
                KafkaUserResponse.QuotaInfo.of(1048576, 2097152, 55, null),
                List.of(
                    KafkaUserResponse.AclRuleInfo.of("allow", "topic", "orders",
                        "literal", "*", List.of("Read", "Describe")),
                    KafkaUserResponse.AclRuleInfo.of("allow", "group", "order-consumers",
                        "literal", "*", List.of("Read"))
                ),
                "alice", "alice",
                "Ready",
                List.of(ConditionInfo.of("Ready", "True", null, null, null)))
        );

        client.when()
            .toolsCall("get_kafka_user",
                Map.of("userName", "alice"), response -> {
                    assertFalse(response.isError());
                    String json = response.content().getFirst().asText().text();
                    assertTrue(json.contains("alice"));
                    assertTrue(json.contains("scram-sha-512"));
                    assertTrue(json.contains("orders"));
                    assertTrue(json.contains("Read"));
                    assertTrue(json.contains("producer_byte_rate"));
                    assertTrue(json.contains("1048576"));
                })
            .thenAssertResults();
    }

    /**
     * Verify get_kafka_user response never contains secret data.
     */
    @Test
    void testGetKafkaUserNoSecretData() {
        when(userService.getUser(null, "bob")).thenReturn(
            KafkaUserResponse.of("bob", "kafka", "my-cluster",
                "tls", "simple", 1, null,
                List.of(KafkaUserResponse.AclRuleInfo.of("allow", "topic", "*",
                    "literal", "*", List.of("All"))),
                "CN=bob", "bob",
                "Ready",
                List.of(ConditionInfo.of("Ready", "True", null, null, null)))
        );

        client.when()
            .toolsCall("get_kafka_user",
                Map.of("userName", "bob"), response -> {
                    assertFalse(response.isError());
                    String json = response.content().getFirst().asText().text();
                    assertFalse(json.contains("password"));
                    assertFalse(json.contains("certificate"));
                    assertFalse(json.contains("private_key"));
                    assertFalse(json.contains("ca.crt"));
                    assertTrue(json.contains("\"secret_name\":\"bob\""));
                })
            .thenAssertResults();
    }
}
