/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.mcp;

import io.strimzi.mcp.dto.KafkaClusterInfo;
import io.strimzi.mcp.dto.KafkaClustersResult;
import io.strimzi.mcp.dto.KafkaTopicsResult;
import io.strimzi.mcp.dto.OperatorLogsResult;
import io.strimzi.mcp.dto.OperatorStatusResult;
import io.strimzi.mcp.dto.PodsResult;
import io.strimzi.mcp.dto.TopicInfo;
import io.strimzi.mcp.util.InputUtils;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests validation logic and edge cases that could occur in real usage scenarios.
 * These tests verify that the system handles invalid inputs, edge cases, and boundary conditions correctly.
 */
class ValidationTest {

    ValidationTest() {
    }

    @Test
    void namespace_validation_against_kubernetes_rules() {
        // Valid Kubernetes namespace names (after normalization)
        String[] validNames = {
            "default",
            "kube-system",
            "kafka",
            "production",
            "team-namespace",
            "ns123",
            "a", // minimum length
            "x".repeat(63) // maximum length
        };

        for (String name : validNames) {
            String normalized = InputUtils.normalizeNamespace(name);
            assertNotNull(normalized, "Valid name should not be null: " + name);
            assertEquals(name.toLowerCase(Locale.ENGLISH), normalized);
        }
    }

    @Test
    void cluster_name_validation_against_kafka_rules() {
        // Valid Kafka cluster names (after normalization)
        String[] validNames = {
            "my-cluster",
            "production-kafka",
            "team.cluster",
            "cluster123",
            "a",
            "kafka-v1.2.3"
        };

        for (String name : validNames) {
            String normalized = InputUtils.normalizeClusterName(name);
            assertNotNull(normalized, "Valid cluster name should not be null: " + name);
            assertEquals(name.toLowerCase(Locale.ENGLISH), normalized);
        }
    }

    @Test
    void input_sanitization_for_special_characters() {
        // Names with underscores (valid in Kubernetes)
        assertEquals("my_namespace", InputUtils.normalizeNamespace("MY_NAMESPACE"));
        assertEquals("cluster_name", InputUtils.normalizeClusterName("CLUSTER_NAME"));

        // Names with dots (valid in Kubernetes)
        assertEquals("team.prod.v1", InputUtils.normalizeNamespace("TEAM.PROD.V1"));
        assertEquals("main.cluster", InputUtils.normalizeClusterName("Main.Cluster"));

        // Names with numbers
        assertEquals("ns123", InputUtils.normalizeNamespace("NS123"));
        assertEquals("kafka2", InputUtils.normalizeClusterName("KAFKA2"));

        // Mixed case with special chars
        assertEquals("my-team.kafka_v1", InputUtils.normalizeNamespace("My-Team.Kafka_V1"));
    }

    @Test
    void boundary_value_testing() {
        // Test minimum valid inputs
        assertNotNull(InputUtils.normalizeNamespace("a"));
        assertNotNull(InputUtils.normalizeClusterName("x"));

        // Test maximum length inputs (Kubernetes limit is 253 chars for some resources, 63 for others)
        String maxLength = "a".repeat(63);
        assertEquals(maxLength, InputUtils.normalizeNamespace(maxLength.toUpperCase(Locale.ENGLISH)));
        assertEquals(maxLength, InputUtils.normalizeClusterName(maxLength.toUpperCase(Locale.ENGLISH)));

        // Test very long inputs (beyond reasonable limits)
        String veryLong = "namespace".repeat(50); // 450 characters
        String normalizedLong = InputUtils.normalizeNamespace(veryLong.toUpperCase(Locale.ENGLISH));
        assertEquals(veryLong.toLowerCase(Locale.ENGLISH), normalizedLong);
    }

    @Test
    void dto_field_validation() {
        // Test TopicInfo with various partition/replica combinations
        TopicInfo validTopic = new TopicInfo("test", "cluster", 1, 1, "Ready");
        assertEquals(1, validTopic.partitions());
        assertEquals(1, validTopic.replicas());

        TopicInfo highVolumeTopic = new TopicInfo("events", "cluster", 50, 3, "Ready");
        assertEquals(50, highVolumeTopic.partitions());
        assertEquals(3, highVolumeTopic.replicas());

        TopicInfo unknownTopic = new TopicInfo("incomplete", "cluster", null, null, "Unknown");
        assertNull(unknownTopic.partitions());
        assertNull(unknownTopic.replicas());

        // Test with zero values (edge case)
        TopicInfo zeroTopic = new TopicInfo("zero", "cluster", 0, 0, "Error");
        assertEquals(0, zeroTopic.partitions());
        assertEquals(0, zeroTopic.replicas());

        // Test with negative values (invalid but should be handled)
        TopicInfo negativeTopic = new TopicInfo("negative", "cluster", -1, -1, "Error");
        assertEquals(-1, negativeTopic.partitions());
        assertEquals(-1, negativeTopic.replicas());
    }

    @Test
    void pod_info_validation() {
        // Valid pod scenarios
        PodsResult.PodInfo healthyPod = PodsResult.PodInfo.summary(
            "kafka-0", "Running", true, "kafka", 0, 3600
        );
        assertTrue(healthyPod.ready());
        assertEquals(0, healthyPod.restarts());

        // Pod with many restarts (troubleshooting scenario)
        PodsResult.PodInfo troublePod = PodsResult.PodInfo.summary(
            "kafka-1", "CrashLoopBackOff", false, "kafka", 25, 120
        );
        assertFalse(troublePod.ready());
        assertEquals(25, troublePod.restarts());

        // Very old pod
        PodsResult.PodInfo ancientPod = PodsResult.PodInfo.summary(
            "kafka-legacy", "Running", true, "kafka", 2, 525600 // 1 year in minutes
        );
        assertEquals(525600, ancientPod.ageMinutes());

        // Edge case: negative age or restarts (shouldn't happen but handle gracefully)
        PodsResult.PodInfo edgePod = PodsResult.PodInfo.summary(
            "edge-case", "Unknown", false, "unknown", -1, -1
        );
        assertEquals(-1, edgePod.restarts());
        assertEquals(-1, edgePod.ageMinutes());
    }

    @Test
    void timestamp_validation() {
        Instant before = Instant.now();

        // Create multiple DTOs and verify all timestamps are reasonable
        OperatorLogsResult logs = OperatorLogsResult.notFound("test");
        PodsResult pods = PodsResult.empty("test", "test");
        OperatorStatusResult status = OperatorStatusResult.notFound("test");
        KafkaTopicsResult topics = KafkaTopicsResult.empty("test", "test");
        KafkaClustersResult clusters = KafkaClustersResult.empty("test");

        Instant after = Instant.now();

        // All timestamps should be within a reasonable range
        Instant[] timestamps = {
            logs.timestamp(),
            pods.timestamp(),
            status.timestamp(),
            topics.timestamp(),
            clusters.timestamp()
        };

        for (Instant timestamp : timestamps) {
            assertNotNull(timestamp);
            assertTrue(timestamp.isAfter(before.minusSeconds(1)));
            assertTrue(timestamp.isBefore(after.plusSeconds(1)));
        }
    }

    @Test
    void collection_validation() {
        // Empty collections should be handled gracefully
        KafkaTopicsResult emptyTopics = KafkaTopicsResult.of("ns", "cluster", List.of());
        assertEquals(0, emptyTopics.totalTopics());
        assertTrue(emptyTopics.topics().isEmpty());

        PodsResult emptyPods = PodsResult.of("ns", "cluster", List.of());
        assertEquals(0, emptyPods.totalPods());
        assertTrue(emptyPods.pods().isEmpty());

        KafkaClustersResult emptyClusters = KafkaClustersResult.of(List.of());
        assertEquals(0, emptyClusters.totalClusters());
        assertTrue(emptyClusters.clusters().isEmpty());

        // Large collections should be handled
        List<TopicInfo> manyTopics = List.of();
        for (int i = 0; i < 1000; i++) {
            manyTopics = new java.util.ArrayList<>(manyTopics);
            manyTopics.add(new TopicInfo("topic-" + i, "cluster", i % 50 + 1, 3, "Ready"));
        }

        KafkaTopicsResult largeTopic = KafkaTopicsResult.of("ns", "cluster", manyTopics);
        assertEquals(1000, largeTopic.totalTopics());
        assertEquals(manyTopics.size(), largeTopic.topics().size());
    }

    @Test
    void message_content_validation() {
        // Messages should contain relevant information and be helpful to users

        // Error messages should include context
        OperatorLogsResult notFound = OperatorLogsResult.notFound("production");
        String notFoundMsg = notFound.message();
        assertTrue(notFoundMsg.contains("production"));
        assertTrue(notFoundMsg.contains("No Strimzi operator pods found"));
        assertTrue(notFoundMsg.contains("Ensure the operator is deployed"));

        // Success messages should include counts
        List<TopicInfo> topics = List.of(
            new TopicInfo("orders", "cluster", 6, 3, "Ready"),
            new TopicInfo("events", "cluster", 12, 3, "Ready")
        );
        KafkaTopicsResult topicResult = KafkaTopicsResult.of("kafka", "my-cluster", topics);
        String topicMsg = topicResult.message();
        assertTrue(topicMsg.contains("Found 2 topics"));
        assertTrue(topicMsg.contains("my-cluster"));
        assertTrue(topicMsg.contains("kafka"));

        // Error messages should be descriptive
        OperatorLogsResult error = OperatorLogsResult.error("kafka", "Connection timeout after 30s");
        String errorMsg = error.message();
        assertTrue(errorMsg.contains("kafka"));
        assertTrue(errorMsg.contains("Connection timeout"));
        assertTrue(errorMsg.contains("Error retrieving operator logs"));
    }

    @Test
    void component_breakdown_validation() {
        List<PodsResult.PodInfo> mixedPods = List.of(
            PodsResult.PodInfo.summary("kafka-0", "Running", true, "kafka", 0, 60),
            PodsResult.PodInfo.summary("kafka-1", "Running", true, "kafka", 0, 58),
            PodsResult.PodInfo.summary("zk-0", "Running", true, "zookeeper", 1, 65),
            PodsResult.PodInfo.summary("zk-1", "Running", false, "zookeeper", 3, 62),
            PodsResult.PodInfo.summary("operator-1", "Running", true, "operator", 0, 120),
            PodsResult.PodInfo.summary("unknown-pod", "Pending", false, "unknown", 0, 5)
        );

        PodsResult result = PodsResult.of("kafka", "test", mixedPods);

        // Validate counts match
        assertEquals(6, result.totalPods());
        Map<String, Integer> breakdown = result.componentBreakdown();
        assertEquals(2, breakdown.get("kafka"));
        assertEquals(2, breakdown.get("zookeeper"));
        assertEquals(1, breakdown.get("operator"));
        assertEquals(1, breakdown.get("unknown"));

        // Validate breakdown sum equals total
        int breakdownSum = breakdown.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(result.totalPods(), breakdownSum);
    }

    @Test
    void cluster_info_display_validation() {
        // Test display name generation with various inputs
        String[][] testCases = {
            {"simple", "default", "simple (namespace: default)"},
            {"my-cluster", "kafka", "my-cluster (namespace: kafka)"},
            {"prod-events-v2", "production", "prod-events-v2 (namespace: production)"},
            {"team.main", "team.namespace", "team.main (namespace: team.namespace)"},
            {"", "", " (namespace: )"},
            {"a", "b", "a (namespace: b)"}
        };

        for (String[] testCase : testCases) {
            String clusterName = testCase[0];
            String namespace = testCase[1];
            String expectedDisplay = testCase[2];

            KafkaClusterInfo cluster = new KafkaClusterInfo(clusterName, namespace, List.of());
            assertEquals(expectedDisplay, cluster.getDisplayName());
        }
    }

    @Test
    void realistic_error_scenarios() {
        // Test scenarios that would actually occur in production

        // Multiple namespaces found scenario
        OperatorLogsResult multipleFound = OperatorLogsResult.error("multiple-found",
            "Found Strimzi operator in multiple namespaces: kafka, strimzi-system, production. " +
            "Please specify which one: 'Show me operator logs from the kafka namespace'");

        assertTrue(multipleFound.message().contains("multiple namespaces"));
        assertTrue(multipleFound.message().contains("kafka, strimzi-system, production"));

        // Permission denied scenario
        PodsResult accessDenied = PodsResult.error("production", "my-cluster",
            "pods is forbidden: User \"alice\" cannot list resource \"pods\" in API group \"\" in the namespace \"production\"");

        assertTrue(accessDenied.message().contains("production"));
        assertTrue(accessDenied.message().contains("forbidden"));

        // CRD not found scenario
        KafkaTopicsResult crdNotFound = KafkaTopicsResult.error("kafka", "my-cluster",
            "the server could not find the requested resource (get kafkas.kafka.strimzi.io)");

        assertTrue(crdNotFound.message().contains("kafkas.kafka.strimzi.io"));

        // Network timeout scenario
        OperatorStatusResult timeout = OperatorStatusResult.error("kafka",
            "dial tcp 192.168.1.100:6443: i/o timeout");

        assertTrue(timeout.message().contains("timeout"));
    }

    @Test
    void input_normalization_edge_cases() {
        // Test edge cases that might break normalization logic

        // Unicode characters (should be preserved after lowercasing)
        String unicodeInput = "Κafka-Ñamespace"; // Greek K, Spanish Ñ
        String normalized = InputUtils.normalizeNamespace(unicodeInput);
        assertEquals(unicodeInput.toLowerCase(Locale.ENGLISH), normalized);

        // Mixed whitespace
        assertEquals("kafka", InputUtils.normalizeNamespace(" \t kafka \n "));
        assertEquals("cluster", InputUtils.normalizeClusterName(" \r cluster \t "));

        // Already normalized input (idempotent)
        String alreadyNormalized = "already-normalized";
        assertEquals(alreadyNormalized, InputUtils.normalizeNamespace(alreadyNormalized));
        assertEquals(alreadyNormalized, InputUtils.normalizeClusterName(alreadyNormalized));

        // Input with leading/trailing special characters
        assertEquals("test.namespace", InputUtils.normalizeNamespace(" test.namespace "));
        assertEquals("my-cluster_v1", InputUtils.normalizeClusterName(" my-cluster_v1 "));
    }
}
