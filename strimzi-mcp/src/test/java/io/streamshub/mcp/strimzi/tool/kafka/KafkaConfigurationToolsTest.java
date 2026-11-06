/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.tool.kafka;

import io.quarkiverse.mcp.server.test.McpAssured;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaEffectiveConfigResponse;
import io.streamshub.mcp.strimzi.service.kafka.KafkaConfigService;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * MCP integration tests for {@link KafkaConfigurationTools}.
 */
@QuarkusTest
class KafkaConfigurationToolsTest {

    @ConfigProperty(name = "quarkus.http.test-port")
    int testPort;

    @InjectMock
    KafkaConfigService kafkaConfigService;

    private McpAssured.McpSseTestClient client;

    KafkaConfigurationToolsTest() {
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

    private static KafkaEffectiveConfigResponse config(final String name, final String namespace) {
        return new KafkaEffectiveConfigResponse(name, namespace, "4.2.0", "4.2-IV1",
            Map.of("default.replication.factor", 3), null, null, null, null, null, null, null, null, null,
            null, null, null, null);
    }

    @Test
    void testGetKafkaClusterConfigReturnsEffectiveConfig() {
        when(kafkaConfigService.getEffectiveConfig("kafka", "my-cluster"))
            .thenReturn(config("my-cluster", "kafka"));

        client.when()
            .toolsCall("get_kafka_cluster_config",
                Map.of("clusterName", "my-cluster", "namespace", "kafka"), response -> {
                    assertFalse(response.isError());
                    String json = response.structuredContent().toString();
                    assertTrue(json.contains("\"name\":\"my-cluster\""), json);
                    assertTrue(json.contains("\"kafka_version\":\"4.2.0\""), json);
                    assertTrue(json.contains("default.replication.factor"), json);
                })
            .thenAssertResults();
    }

    @Test
    void testGetKafkaClusterConfigPassesNullNamespaceForAutoDiscovery() {
        when(kafkaConfigService.getEffectiveConfig(null, "my-cluster"))
            .thenReturn(config("my-cluster", "discovered"));

        client.when()
            .toolsCall("get_kafka_cluster_config", Map.of("clusterName", "my-cluster"), response -> {
                assertFalse(response.isError());
                assertTrue(response.structuredContent().toString().contains("\"namespace\":\"discovered\""));
            })
            .thenAssertResults();
        verify(kafkaConfigService).getEffectiveConfig(null, "my-cluster");
    }

    @Test
    void testGetKafkaClusterConfigRejectsBlankClusterName() {
        client.when()
            .toolsCall("get_kafka_cluster_config", Map.of("clusterName", ""), response ->
                assertTrue(response.isError()))
            .thenAssertResults();
        verifyNoInteractions(kafkaConfigService);
    }
}
