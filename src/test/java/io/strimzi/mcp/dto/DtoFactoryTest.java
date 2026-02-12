/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.mcp.dto;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DtoFactoryTest {

    DtoFactoryTest() {
    }

    @Test
    void operatorLogsResult_successful_creation() {
        List<String> pods = List.of("operator-1", "operator-2");
        String logs = "INFO: Starting\nERROR: Failed\nWARN: Retry";

        OperatorLogsResult result = OperatorLogsResult.of("kafka", logs, pods, true, 2, 100);

        assertEquals("kafka", result.namespace());
        assertEquals(pods, result.operatorPods());
        assertEquals(logs, result.logs());
        assertTrue(result.hasErrors());
        assertEquals(2, result.errorCount());
        assertEquals(100, result.logLines());
        assertNotNull(result.timestamp());
        assertTrue(result.message().contains("Found 2 errors"));
        assertTrue(result.message().contains("2 pods"));
    }

    @Test
    void operatorLogsResult_no_errors_creation() {
        List<String> pods = List.of("operator-1");
        String cleanLogs = "INFO: All systems operational";

        OperatorLogsResult result = OperatorLogsResult.of("kafka", cleanLogs, pods, false, 0, 25);

        assertEquals("kafka", result.namespace());
        assertEquals(cleanLogs, result.logs());
        assertFalse(result.hasErrors());
        assertEquals(0, result.errorCount());
        assertTrue(result.message().contains("no errors found"));
    }

    @Test
    void operatorLogsResult_not_found() {
        OperatorLogsResult result = OperatorLogsResult.notFound("missing-ns");

        assertEquals("missing-ns", result.namespace());
        assertTrue(result.operatorPods().isEmpty());
        assertFalse(result.hasErrors());
        assertEquals(0, result.errorCount());
        assertNull(result.logs());
        assertTrue(result.message().contains("No Strimzi operator pods found"));
        assertTrue(result.message().contains("missing-ns"));
    }

    @Test
    void operatorLogsResult_api_error() {
        OperatorLogsResult result = OperatorLogsResult.error("kafka", "Connection timeout after 30s");

        assertEquals("kafka", result.namespace());
        assertNull(result.operatorPods());
        assertFalse(result.hasErrors());
        assertNull(result.logs());
        assertTrue(result.message().contains("Connection timeout after 30s"));
        assertTrue(result.message().contains("Error retrieving operator logs"));
    }

    @Test
    void podsResult_healthy_cluster() {
        List<PodsResult.PodInfo> pods = List.of(
            PodsResult.PodInfo.summary("kafka-0", "Running", true, "kafka", 0, 120),
            PodsResult.PodInfo.summary("kafka-1", "Running", true, "kafka", 2, 118),
            PodsResult.PodInfo.summary("zk-0", "Running", true, "zookeeper", 0, 125),
            PodsResult.PodInfo.summary("operator-1", "Running", true, "operator", 1, 200)
        );

        PodsResult result = PodsResult.of("kafka", "my-cluster", pods);

        assertEquals("kafka", result.namespace());
        assertEquals("my-cluster", result.clusterName());
        assertEquals(4, result.totalPods());
        assertEquals(pods, result.pods());
        assertNotNull(result.componentBreakdown());
        assertEquals(2, result.componentBreakdown().get("kafka"));
        assertEquals(1, result.componentBreakdown().get("zookeeper"));
        assertEquals(1, result.componentBreakdown().get("operator"));
        assertNotNull(result.timestamp());
    }

    @Test
    void podsResult_pod_info_validation() {
        PodsResult.PodInfo healthyPod = PodsResult.PodInfo.summary(
            "kafka-0", "Running", true, "kafka", 0, 60
        );

        assertEquals("kafka-0", healthyPod.name());
        assertEquals("Running", healthyPod.phase());
        assertTrue(healthyPod.ready());
        assertEquals("kafka", healthyPod.component());
        assertEquals(0, healthyPod.restarts());
        assertEquals(60, healthyPod.ageMinutes());

        // Test unhealthy pod
        PodsResult.PodInfo failingPod = PodsResult.PodInfo.summary(
            "kafka-1", "CrashLoopBackOff", false, "kafka", 15, 30
        );

        assertFalse(failingPod.ready());
        assertEquals(15, failingPod.restarts());
        assertEquals("CrashLoopBackOff", failingPod.phase());
    }

    @Test
    void podInfo_summary_nulls_all_detail_fields() {
        PodsResult.PodInfo pod = PodsResult.PodInfo.summary("kafka-0", "Running", true, "kafka", 0, 120);

        // Summary fields set
        assertEquals("kafka-0", pod.name());
        assertEquals("Running", pod.phase());
        assertTrue(pod.ready());
        assertEquals("kafka", pod.component());
        assertEquals(0, pod.restarts());
        assertEquals(120, pod.ageMinutes());

        // All detail fields null
        assertNull(pod.nodeName());
        assertNull(pod.hostIP());
        assertNull(pod.podIP());
        assertNull(pod.serviceAccount());
        assertNull(pod.labels());
        assertNull(pod.annotations());
        assertNull(pod.containers());
        assertNull(pod.volumes());
        assertNull(pod.conditions());
        assertNull(pod.startTime());
    }

    @Test
    void podsResult_empty_cluster() {
        PodsResult result = PodsResult.empty("kafka", "my-cluster");

        assertEquals("kafka", result.namespace());
        assertEquals("my-cluster", result.clusterName());
        assertEquals(0, result.totalPods());
        assertTrue(result.pods().isEmpty());
        assertTrue(result.message().contains("No Kafka pods found"));
    }

    @Test
    void operatorStatusResult_healthy_operator() {
        OperatorStatusResult result = OperatorStatusResult.of(
            "kafka",
            "strimzi-cluster-operator",
            true,
            2,
            2,
            "0.50.0",
            "quay.io/strimzi/operator:0.50.0",
            240L
        );

        assertEquals("kafka", result.namespace());
        assertEquals("strimzi-cluster-operator", result.deploymentName());
        assertTrue(result.ready());
        assertEquals(2, result.replicas());
        assertEquals(2, result.readyReplicas());
        assertEquals("0.50.0", result.version());
        assertEquals("quay.io/strimzi/operator:0.50.0", result.image());
        assertNotNull(result.timestamp());
        assertTrue(result.message().contains("is running normally"));
    }

    @Test
    void operatorStatusResult_degraded_operator() {
        OperatorStatusResult result = OperatorStatusResult.of(
            "kafka", "strimzi-cluster-operator", false, 3, 1, "0.50.0", "image", 120L
        );

        assertFalse(result.ready());
        assertEquals(3, result.replicas());
        assertEquals(1, result.readyReplicas());
        assertTrue(result.message().contains("is partially available"));
    }

    @Test
    void operatorStatusResult_not_found() {
        OperatorStatusResult result = OperatorStatusResult.notFound("missing");

        assertEquals("missing", result.namespace());
        assertNull(result.deploymentName());
        assertTrue(result.message().contains("No Strimzi operator deployment found"));
    }

    @Test
    void kafkaTopicsResult_with_topics() {
        List<TopicInfo> topics = List.of(
            new TopicInfo("orders", "my-cluster", 12, 3, "Ready"),
            new TopicInfo("payments", "my-cluster", 6, 2, "Ready"),
            new TopicInfo("logs", "my-cluster", 1, 1, "Not Ready"),
            new TopicInfo("temp", "my-cluster", null, null, "Unknown")
        );

        KafkaTopicsResult result = KafkaTopicsResult.of("kafka", "my-cluster", topics);

        assertEquals("kafka", result.namespace());
        assertEquals("my-cluster", result.clusterName());
        assertEquals(4, result.totalTopics());
        assertEquals(topics, result.topics());
        assertTrue(result.message().contains("Found 4 topics"));

        // Validate individual topics
        TopicInfo ordersTopic = topics.stream()
            .filter(t -> "orders".equals(t.name()))
            .findFirst()
            .orElseThrow();

        assertEquals("orders", ordersTopic.name());
        assertEquals("my-cluster", ordersTopic.cluster());
        assertEquals(12, ordersTopic.partitions());
        assertEquals(3, ordersTopic.replicas());
        assertEquals("Ready", ordersTopic.status());

        // Test topic with nulls
        TopicInfo tempTopic = topics.stream()
            .filter(t -> "temp".equals(t.name()))
            .findFirst()
            .orElseThrow();

        assertNull(tempTopic.partitions());
        assertNull(tempTopic.replicas());
        assertEquals("Unknown", tempTopic.status());
    }

    @Test
    void kafkaTopicsResult_empty() {
        KafkaTopicsResult result = KafkaTopicsResult.empty("kafka", "my-cluster");

        assertEquals("kafka", result.namespace());
        assertEquals("my-cluster", result.clusterName());
        assertEquals(0, result.totalTopics());
        assertTrue(result.topics().isEmpty());
        assertTrue(result.message().contains("No topics found"));
    }

    @Test
    void kafkaTopicsResult_error() {
        KafkaTopicsResult result = KafkaTopicsResult.error("kafka", "my-cluster", "CRD not found");

        assertEquals("kafka", result.namespace());
        assertEquals("my-cluster", result.clusterName());
        assertEquals(0, result.totalTopics());
        assertTrue(result.topics().isEmpty());
        assertTrue(result.message().contains("CRD not found"));
    }

    @Test
    void kafkaClustersResult_multiple_clusters() {
        List<KafkaClusterInfo> clusters = List.of(
            new KafkaClusterInfo("prod-main", "production", List.of()),
            new KafkaClusterInfo("prod-events", "production", List.of()),
            new KafkaClusterInfo("dev-cluster", "development", List.of()),
            new KafkaClusterInfo("test-cluster", "testing", List.of())
        );

        KafkaClustersResult result = KafkaClustersResult.of(clusters);

        assertEquals(4, result.totalClusters());
        assertEquals(clusters, result.clusters());
        assertTrue(result.message().contains("Found 4 Kafka clusters"));

        // Test cluster info
        KafkaClusterInfo prodMain = clusters.get(0);
        assertEquals("prod-main", prodMain.name());
        assertEquals("production", prodMain.namespace());
        assertEquals("prod-main (namespace: production)", prodMain.getDisplayName());

        // Test namespace distribution
        List<String> namespaces = clusters.stream()
            .map(KafkaClusterInfo::namespace)
            .distinct()
            .sorted()
            .toList();

        assertEquals(3, namespaces.size());
        assertTrue(namespaces.contains("production"));
        assertTrue(namespaces.contains("development"));
        assertTrue(namespaces.contains("testing"));
    }

    @Test
    void kafkaClustersResult_single_cluster_message() {
        List<KafkaClusterInfo> clusters = List.of(
            new KafkaClusterInfo("only-cluster", "kafka", List.of())
        );

        KafkaClustersResult result = KafkaClustersResult.of(clusters);

        assertEquals(1, result.totalClusters());
        assertTrue(result.message().contains("Found 1 Kafka cluster"));
        assertTrue(result.message().contains("only-cluster"));
    }

    @Test
    void timestamp_generation_consistency() {
        Instant before = Instant.now();

        // Create various results
        OperatorLogsResult logs = OperatorLogsResult.notFound("test");
        PodsResult pods = PodsResult.empty("test", "test");
        OperatorStatusResult status = OperatorStatusResult.notFound("test");
        KafkaTopicsResult topics = KafkaTopicsResult.empty("test", "test");
        KafkaClustersResult clusters = KafkaClustersResult.empty("test");

        Instant after = Instant.now();

        // All timestamps should be recent and consistent
        assertTimestampInRange(logs.timestamp(), before, after);
        assertTimestampInRange(pods.timestamp(), before, after);
        assertTimestampInRange(status.timestamp(), before, after);
        assertTimestampInRange(topics.timestamp(), before, after);
        assertTimestampInRange(clusters.timestamp(), before, after);
    }

    @Test
    void edge_cases_and_null_handling() {
        // Empty collections should work
        assertDoesNotThrow(() -> KafkaTopicsResult.of("ns", "cluster", List.of()));
        assertDoesNotThrow(() -> KafkaClustersResult.of(List.of()));
        assertDoesNotThrow(() -> PodsResult.of("ns", "cluster", List.of()));

        // Large numbers should be handled
        PodsResult.PodInfo veryOldPod = PodsResult.PodInfo.summary(
            "ancient-pod", "Running", true, "kafka",
            Integer.MAX_VALUE, Long.MAX_VALUE / 60 // Max minutes that fit in long
        );

        assertEquals(Integer.MAX_VALUE, veryOldPod.restarts());
        assertEquals(Long.MAX_VALUE / 60, veryOldPod.ageMinutes());

        // Special characters in names
        TopicInfo specialTopic = new TopicInfo(
            "topic-with-dashes_and.dots", "cluster-name_v2", 1, 1, "Ready"
        );
        assertEquals("topic-with-dashes_and.dots", specialTopic.name());
        assertEquals("cluster-name_v2", specialTopic.cluster());

        KafkaClusterInfo specialCluster = new KafkaClusterInfo(
            "cluster-v2.0_test", "namespace-prod.v1", List.of()
        );
        assertEquals("cluster-v2.0_test (namespace: namespace-prod.v1)",
            specialCluster.getDisplayName());
    }

    @Test
    void message_formatting_logic() {
        // Test singular vs plural message formatting

        // Single item messages
        KafkaTopicsResult singleTopic = KafkaTopicsResult.of("kafka", "cluster",
            List.of(new TopicInfo("test", "cluster", 1, 1, "Ready")));
        assertTrue(singleTopic.message().contains("Found 1 topic"));

        KafkaClustersResult singleCluster = KafkaClustersResult.of(
            List.of(new KafkaClusterInfo("test", "ns", List.of())));
        assertTrue(singleCluster.message().contains("Found 1 Kafka cluster"));

        // Multiple item messages
        KafkaTopicsResult multipleTopics = KafkaTopicsResult.of("kafka", "cluster", List.of(
            new TopicInfo("test1", "cluster", 1, 1, "Ready"),
            new TopicInfo("test2", "cluster", 1, 1, "Ready"),
            new TopicInfo("test3", "cluster", 1, 1, "Ready")
        ));
        assertTrue(multipleTopics.message().contains("Found 3 topics"));

        // Error message context
        OperatorLogsResult apiError = OperatorLogsResult.error("production",
            "Kubernetes API server unreachable");
        assertTrue(apiError.message().contains("production"));
        assertTrue(apiError.message().contains("Kubernetes API server unreachable"));
        assertTrue(apiError.message().contains("Error retrieving operator logs"));

        // Context preservation in messages
        PodsResult emptyCluster = PodsResult.empty("staging", "main-cluster");
        assertTrue(emptyCluster.message().contains("staging"));
        assertTrue(emptyCluster.message().contains("main-cluster"));
    }

    private void assertTimestampInRange(Instant timestamp, Instant before, Instant after) {
        assertTrue(timestamp.isAfter(before.minusSeconds(1)),
            "Timestamp should be after test start");
        assertTrue(timestamp.isBefore(after.plusSeconds(1)),
            "Timestamp should be before test end");
    }
}
