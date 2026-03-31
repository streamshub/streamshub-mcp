/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.util.metrics;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
