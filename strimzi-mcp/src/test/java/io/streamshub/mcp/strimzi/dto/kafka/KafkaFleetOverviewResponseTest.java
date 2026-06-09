/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaFleetOverviewResponse.ClusterSummary;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaFleetOverviewResponse.ClusterWarning;
import io.streamshub.mcp.strimzi.dto.kafka.KafkaFleetOverviewResponse.StatusDistribution;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
/**
 * Unit tests for {@link KafkaFleetOverviewResponse}.
 */
class KafkaFleetOverviewResponseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    KafkaFleetOverviewResponseTest() {
    }

    @Test
    void jsonSerializationUsesSnakeCaseFieldNames() throws Exception {
        KafkaFleetOverviewResponse response = new KafkaFleetOverviewResponse(
            2, 6,
            new StatusDistribution(1, 1, 0, 0),
            List.of(
                new ClusterSummary("prod", "kafka-prod", "Ready", "4.2.0", 3, 3, 1000L,
                    42, 5, 0, 1, 0, 0, null),
                new ClusterSummary("staging", "kafka-staging", "NotReady", "4.2.0", 3, 2, 500L,
                    10, 2, 1, 0, 0, 0, null)
            ),
            List.of(new ClusterWarning("staging", "kafka-staging", "NotReady", "Cluster is not ready")),
            Instant.parse("2026-06-05T10:00:00Z"),
            null,
            null
        );

        String json = MAPPER.writeValueAsString(response);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = MAPPER.readValue(json, Map.class);

        assertEquals(2, map.get("total_clusters"));
        assertEquals(6, map.get("total_brokers"));
        assertTrue(map.containsKey("status_distribution"));
        assertTrue(map.containsKey("clusters"));
        assertTrue(map.containsKey("warnings"));
        assertFalse(map.containsKey("namespace_filter"));
    }

    @Test
    void nullNamespaceFilterIsExcludedFromJson() throws Exception {
        KafkaFleetOverviewResponse response = new KafkaFleetOverviewResponse(
            0, 0,
            new StatusDistribution(0, 0, 0, 0),
            List.of(), List.of(),
            Instant.now(),
            null,
            null
        );

        String json = MAPPER.writeValueAsString(response);
        assertFalse(json.contains("namespace_filter"));
    }

    @Test
    void clusterSummaryRelationshipFieldsSerialized() throws Exception {
        ClusterSummary summary = new ClusterSummary(
            "prod", "kafka", "Ready", "4.2.0", 3, 3, 1000L,
            42, 5, 1, 2, 1, 0, null);

        String json = MAPPER.writeValueAsString(summary);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = MAPPER.readValue(json, Map.class);

        assertEquals(42, map.get("topic_count"));
        assertEquals(5, map.get("user_count"));
        assertEquals(1, map.get("active_rebalances"));
        assertEquals(2, map.get("connect_count"));
        assertEquals(1, map.get("bridge_count"));
        assertEquals(0, map.get("mirror_maker_count"));
    }

    @Test
    void clusterSummaryNullRelationshipsExcludedFromJson() throws Exception {
        ClusterSummary summary = new ClusterSummary(
            "test", "ns", "Ready", "4.2.0", 3, 3, 1000L,
            null, null, null, null, null, null, null);

        String json = MAPPER.writeValueAsString(summary);
        assertFalse(json.contains("topic_count"));
        assertFalse(json.contains("user_count"));
        assertFalse(json.contains("active_rebalances"));
        assertFalse(json.contains("connect_count"));
        assertFalse(json.contains("bridge_count"));
        assertFalse(json.contains("mirror_maker_count"));
    }

    @Test
    void clusterSummaryNullAgeIsExcludedFromJson() throws Exception {
        ClusterSummary summary = new ClusterSummary(
            "test", "ns", "Ready", "4.2.0", 3, 3, null,
            null, null, null, null, null, null, null);

        String json = MAPPER.writeValueAsString(summary);
        assertFalse(json.contains("age_minutes"));
    }

    @Test
    void statusDistributionSerializesAllFields() throws Exception {
        StatusDistribution dist = new StatusDistribution(5, 2, 1, 0);

        String json = MAPPER.writeValueAsString(dist);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = MAPPER.readValue(json, Map.class);

        assertEquals(5, map.get("ready"));
        assertEquals(2, map.get("not_ready"));
        assertEquals(1, map.get("error"));
        assertEquals(0, map.get("unknown"));
    }

    @Test
    void clusterWarningSerializesAllFields() throws Exception {
        ClusterWarning warning = new ClusterWarning("prod", "kafka", "Error", "Cluster in error");

        String json = MAPPER.writeValueAsString(warning);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = MAPPER.readValue(json, Map.class);

        assertEquals("prod", map.get("cluster_name"));
        assertEquals("kafka", map.get("namespace"));
        assertEquals("Error", map.get("warning_type"));
        assertEquals("Cluster in error", map.get("message"));
    }

    @Test
    void resourceErrorsIncludedWhenPresent() throws Exception {
        KafkaFleetOverviewResponse response = new KafkaFleetOverviewResponse(
            1, 3,
            new StatusDistribution(1, 0, 0, 0),
            List.of(new ClusterSummary("prod", "kafka", "Ready", "4.2.0", 3, 3, 1000L,
                null, null, 0, 1, 0, 0, List.of("KafkaTopic", "KafkaUser"))),
            List.of(),
            Instant.now(),
            null,
            List.of("KafkaBridge")
        );

        String json = MAPPER.writeValueAsString(response);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = MAPPER.readValue(json, Map.class);

        assertEquals(List.of("KafkaBridge"), map.get("resource_errors"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> clusters = (List<Map<String, Object>>) map.get("clusters");
        assertEquals(List.of("KafkaTopic", "KafkaUser"), clusters.get(0).get("resource_errors"));
    }

    @Test
    void resourceErrorsExcludedWhenNull() throws Exception {
        KafkaFleetOverviewResponse response = new KafkaFleetOverviewResponse(
            0, 0,
            new StatusDistribution(0, 0, 0, 0),
            List.of(), List.of(),
            Instant.now(),
            null,
            null
        );

        String json = MAPPER.writeValueAsString(response);
        assertFalse(json.contains("resource_errors"));
    }

    @Test
    void namespaceFilterIsIncludedWhenSet() throws Exception {
        KafkaFleetOverviewResponse response = new KafkaFleetOverviewResponse(
            1, 3,
            new StatusDistribution(1, 0, 0, 0),
            List.of(), List.of(),
            Instant.now(),
            "kafka-prod",
            null
        );

        String json = MAPPER.writeValueAsString(response);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = MAPPER.readValue(json, Map.class);

        assertEquals("kafka-prod", map.get("namespace_filter"));
    }
}
