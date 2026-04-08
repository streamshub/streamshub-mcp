/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.util.metrics;

import io.streamshub.mcp.common.dto.metrics.MetricSample;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link PrometheusTextParser}.
 */
class PrometheusTextParserTest {

    PrometheusTextParserTest() {
        // default constructor for checkstyle
    }

    @Test
    void parseSimpleMetricWithoutLabels() {
        String text = "http_requests_total 1027";
        List<MetricSample> samples = PrometheusTextParser.parse(text);

        assertEquals(1, samples.size());
        MetricSample sample = samples.get(0);
        assertEquals("http_requests_total", sample.name());
        assertEquals(1027.0, sample.value());
        assertTrue(sample.labels().isEmpty());
        assertNull(sample.timestamp());
    }

    @Test
    void parseMetricWithLabels() {
        String text = "http_requests_total{method=\"GET\",handler=\"/api\"} 1027";
        List<MetricSample> samples = PrometheusTextParser.parse(text);

        assertEquals(1, samples.size());
        MetricSample sample = samples.get(0);
        assertEquals("http_requests_total", sample.name());
        assertEquals(1027.0, sample.value());
        assertEquals("GET", sample.labels().get("method"));
        assertEquals("/api", sample.labels().get("handler"));
    }

    @Test
    void parseMetricWithTimestamp() {
        String text = "http_requests_total 1027 1395066363000";
        List<MetricSample> samples = PrometheusTextParser.parse(text);

        assertEquals(1, samples.size());
        MetricSample sample = samples.get(0);
        assertEquals(1027.0, sample.value());
        assertNotNull(sample.timestamp());
        assertEquals(1395066363000L, sample.timestamp().toEpochMilli());
    }

    @Test
    void parseSkipsCommentLines() {
        String text = """
            # HELP http_requests_total Total requests.
            # TYPE http_requests_total counter
            http_requests_total 1027
            """;
        List<MetricSample> samples = PrometheusTextParser.parse(text);

        assertEquals(1, samples.size());
        assertEquals("http_requests_total", samples.get(0).name());
    }

    @Test
    void parseSkipsEmptyLines() {
        String text = """
            http_requests_total 100

            http_errors_total 5
            """;
        List<MetricSample> samples = PrometheusTextParser.parse(text);

        assertEquals(2, samples.size());
    }

    @Test
    void parseReturnsEmptyForNullInput() {
        assertTrue(PrometheusTextParser.parse(null).isEmpty());
    }

    @Test
    void parseReturnsEmptyForBlankInput() {
        assertTrue(PrometheusTextParser.parse("   ").isEmpty());
    }

    @Test
    void parseSkipsMalformedLines() {
        String text = """
            valid_metric 42
            no_value_here
            another{broken
            good_metric 99
            """;
        List<MetricSample> samples = PrometheusTextParser.parse(text);

        assertEquals(2, samples.size());
        assertEquals("valid_metric", samples.get(0).name());
        assertEquals("good_metric", samples.get(1).name());
    }

    @Test
    void parseFiltersMetricsByName() {
        String text = """
            metric_a 1
            metric_b 2
            metric_c 3
            """;
        List<MetricSample> samples = PrometheusTextParser.parse(text, Set.of("metric_a", "metric_c"));

        assertEquals(2, samples.size());
        assertEquals("metric_a", samples.get(0).name());
        assertEquals("metric_c", samples.get(1).name());
    }

    @Test
    void parseWithEmptyFilterReturnsAll() {
        String text = """
            metric_a 1
            metric_b 2
            """;
        List<MetricSample> samples = PrometheusTextParser.parse(text, Set.of());

        assertEquals(2, samples.size());
    }

    @Test
    void parseWithNullFilterReturnsAll() {
        String text = """
            metric_a 1
            metric_b 2
            """;
        List<MetricSample> samples = PrometheusTextParser.parse(text, null);

        assertEquals(2, samples.size());
    }

    @Test
    void parseHandlesEscapedLabelValues() {
        String text = "metric{path=\"/api/v1\",desc=\"line\\\"quoted\\\"\"} 42";
        List<MetricSample> samples = PrometheusTextParser.parse(text);

        assertEquals(1, samples.size());
        assertEquals("/api/v1", samples.get(0).labels().get("path"));
        assertEquals("line\"quoted\"", samples.get(0).labels().get("desc"));
    }

    @Test
    void parseMultipleMetricsFromRealOutput() {
        String text = """
            # HELP jvm_memory_used_bytes Used bytes of a given JVM memory area.
            # TYPE jvm_memory_used_bytes gauge
            jvm_memory_used_bytes{area="heap"} 1.073741824E9
            jvm_memory_used_bytes{area="nonheap"} 1.34217728E8
            # HELP kafka_server_replica_manager_under_replicated_partitions Value
            # TYPE kafka_server_replica_manager_under_replicated_partitions gauge
            kafka_server_replica_manager_under_replicated_partitions 0.0
            """;
        List<MetricSample> samples = PrometheusTextParser.parse(text);

        assertEquals(3, samples.size());
        assertEquals("jvm_memory_used_bytes", samples.get(0).name());
        assertEquals("heap", samples.get(0).labels().get("area"));
        assertEquals(1.073741824E9, samples.get(0).value());
    }

    @Test
    void parseHandlesFloatingPointValues() {
        String text = "metric_value 3.14159";
        List<MetricSample> samples = PrometheusTextParser.parse(text);

        assertEquals(1, samples.size());
        assertEquals(3.14159, samples.get(0).value(), 0.00001);
    }
}
