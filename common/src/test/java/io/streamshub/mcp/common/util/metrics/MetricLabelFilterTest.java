/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.util.metrics;

import io.streamshub.mcp.common.dto.metrics.AggregationLevel;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link MetricLabelFilter}.
 */
class MetricLabelFilterTest {

    MetricLabelFilterTest() {
    }

    @Test
    void nullInputReturnsEmptyMap() {
        Map<String, String> result = MetricLabelFilter.filterLabels(null);
        assertTrue(result.isEmpty());
    }

    @Test
    void emptyMapReturnsEmptyMap() {
        Map<String, String> result = MetricLabelFilter.filterLabels(Map.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void onlyInternalLabelsReturnsEmptyMap() {
        Map<String, String> labels = Map.of(
            "__name__", "kafka_metric",
            "job", "kafka",
            "instance", "10.0.0.1:9090",
            "endpoint", "metrics",
            "container", "kafka",
            "prometheus", "monitoring/prometheus",
            "service", "kafka-metrics"
        );
        Map<String, String> result = MetricLabelFilter.filterLabels(labels);
        assertTrue(result.isEmpty());
    }

    @Test
    void domainLabelsAreKept() {
        Map<String, String> labels = Map.of(
            "namespace", "kafka-system",
            "kind", "Kafka",
            "strimzi_io_cluster", "my-cluster"
        );
        Map<String, String> result = MetricLabelFilter.filterLabels(labels);
        assertEquals(3, result.size());
        assertEquals("kafka-system", result.get("namespace"));
        assertEquals("Kafka", result.get("kind"));
        assertEquals("my-cluster", result.get("strimzi_io_cluster"));
    }

    @Test
    void podLabelIsKept() {
        Map<String, String> labels = Map.of("pod", "my-cluster-kafka-0");
        Map<String, String> result = MetricLabelFilter.filterLabels(labels);
        assertEquals(1, result.size());
        assertEquals("my-cluster-kafka-0", result.get("pod"));
    }

    @Test
    void mixedLabelsFiltersCorrectly() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("__name__", "kafka_metric");
        labels.put("namespace", "kafka-system");
        labels.put("job", "kafka");
        labels.put("pod", "broker-0");
        labels.put("instance", "10.0.0.1:9090");
        labels.put("kind", "Kafka");

        Map<String, String> result = MetricLabelFilter.filterLabels(labels);
        assertEquals(3, result.size());
        assertEquals("kafka-system", result.get("namespace"));
        assertEquals("broker-0", result.get("pod"));
        assertEquals("Kafka", result.get("kind"));
    }

    @Test
    void partitionLevelKeepsEverything() {
        Map<String, String> labels = allLabels();
        Map<String, String> result = MetricLabelFilter.labelsForAggregation(labels, AggregationLevel.PARTITION);
        assertTrue(result.containsKey("pod"));
        assertTrue(result.containsKey("topic"));
        assertTrue(result.containsKey("partition"));
        assertTrue(result.containsKey("type"));
        assertFalse(result.containsKey("__name__"));
        assertFalse(result.containsKey("kubernetes_pod_name"));
        assertFalse(result.containsKey("strimzi_io_pod_name"));
    }

    @Test
    void topicLevelStripsPartition() {
        Map<String, String> labels = allLabels();
        Map<String, String> result = MetricLabelFilter.labelsForAggregation(labels, AggregationLevel.TOPIC);
        assertTrue(result.containsKey("pod"));
        assertTrue(result.containsKey("topic"));
        assertFalse(result.containsKey("partition"));
        assertTrue(result.containsKey("type"));
        assertFalse(result.containsKey("kubernetes_pod_name"));
        assertFalse(result.containsKey("strimzi_io_pod_name"));
    }

    @Test
    void brokerLevelStripsTopicAndPartition() {
        Map<String, String> labels = allLabels();
        Map<String, String> result = MetricLabelFilter.labelsForAggregation(labels, AggregationLevel.BROKER);
        assertTrue(result.containsKey("pod"));
        assertFalse(result.containsKey("topic"));
        assertFalse(result.containsKey("partition"));
        assertTrue(result.containsKey("type"));
        assertFalse(result.containsKey("kubernetes_pod_name"));
        assertFalse(result.containsKey("strimzi_io_pod_name"));
    }

    @Test
    void clusterLevelStripsTopicPartitionAndPodIdentity() {
        Map<String, String> labels = allLabels();
        Map<String, String> result = MetricLabelFilter.labelsForAggregation(labels, AggregationLevel.CLUSTER);
        assertFalse(result.containsKey("pod"));
        assertFalse(result.containsKey("kubernetes_pod_name"));
        assertFalse(result.containsKey("strimzi_io_pod_name"));
        assertFalse(result.containsKey("node_name"));
        assertFalse(result.containsKey("node_ip"));
        assertFalse(result.containsKey("topic"));
        assertFalse(result.containsKey("partition"));
        assertTrue(result.containsKey("type"));
        assertTrue(result.containsKey("namespace"));
    }

    @Test
    void labelsForAggregationWithNullReturnsEmpty() {
        assertTrue(MetricLabelFilter.labelsForAggregation(null, AggregationLevel.BROKER).isEmpty());
    }

    @Test
    void labelsForAggregationAlwaysStripsInternalLabels() {
        Map<String, String> labels = Map.of("__name__", "test", "job", "kafka", "type", "producer");
        for (AggregationLevel level : AggregationLevel.values()) {
            Map<String, String> result = MetricLabelFilter.labelsForAggregation(labels, level);
            assertFalse(result.containsKey("__name__"));
            assertFalse(result.containsKey("job"));
            assertTrue(result.containsKey("type"));
        }
    }

    @Test
    void duplicateValuesAreStrippedByFilterLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("pod", "broker-0");
        labels.put("kubernetes_pod_name", "broker-0");
        labels.put("strimzi_io_pod_name", "broker-0");
        labels.put("namespace", "kafka-system");

        Map<String, String> result = MetricLabelFilter.filterLabels(labels);
        assertTrue(result.containsKey("pod"));
        assertFalse(result.containsKey("kubernetes_pod_name"));
        assertFalse(result.containsKey("strimzi_io_pod_name"));
        assertTrue(result.containsKey("namespace"));
    }

    @Test
    void canonicalLabelsAreNeverStripped() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("pod", "broker-0");
        labels.put("topic", "my-topic");
        labels.put("partition", "0");
        labels.put("other_pod", "broker-0");
        labels.put("other_topic", "my-topic");

        Map<String, String> result = MetricLabelFilter.filterLabels(labels);
        assertTrue(result.containsKey("pod"));
        assertTrue(result.containsKey("topic"));
        assertTrue(result.containsKey("partition"));
        assertFalse(result.containsKey("other_pod"));
        assertFalse(result.containsKey("other_topic"));
    }

    @Test
    void uniqueValuesAreKept() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("pod", "broker-0");
        labels.put("strimzi_io_cluster", "my-cluster");
        labels.put("strimzi_io_kind", "Kafka");
        labels.put("namespace", "kafka-system");

        Map<String, String> result = MetricLabelFilter.filterLabels(labels);
        assertEquals(4, result.size());
        assertTrue(result.containsKey("strimzi_io_cluster"));
        assertTrue(result.containsKey("strimzi_io_kind"));
        assertTrue(result.containsKey("namespace"));
    }

    @Test
    void deduplicateByValueWithNoCanonicalLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("strimzi_io_cluster", "my-cluster");
        labels.put("namespace", "kafka-system");

        Map<String, String> result = MetricLabelFilter.deduplicateByValue(labels);
        assertEquals(2, result.size());
    }

    private static Map<String, String> allLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("__name__", "test_metric");
        labels.put("job", "kafka");
        labels.put("pod", "broker-0");
        labels.put("kubernetes_pod_name", "broker-0");
        labels.put("strimzi_io_pod_name", "broker-0");
        labels.put("node_name", "worker-1");
        labels.put("node_ip", "10.0.0.1");
        labels.put("topic", "my-topic");
        labels.put("partition", "0");
        labels.put("type", "producer-metrics");
        labels.put("namespace", "kafka-system");
        return labels;
    }
}
