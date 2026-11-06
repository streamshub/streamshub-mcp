/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service.metrics;

import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.streamshub.mcp.common.dto.metrics.MetricSample;
import io.streamshub.mcp.common.dto.metrics.MetricsQueryParams;
import io.streamshub.mcp.common.dto.metrics.PodTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PodScrapingMetricsProvider}.
 */
class PodScrapingMetricsProviderTest {

    private static final String NS = "kafka";
    private static final String BODY_ONE = "# TYPE up gauge\nup{pod=\"p-0\"} 1\nother_metric 7\n";
    private static final String BODY_TWO = "up{pod=\"p-1\"} 0\n";

    private KubernetesClient client;
    private PodResource podResource;
    private PodScrapingMetricsProvider provider;

    PodScrapingMetricsProviderTest() {
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        client = Mockito.mock(KubernetesClient.class);
        MixedOperation<Pod, PodList, PodResource> pods = Mockito.mock(MixedOperation.class);
        NonNamespaceOperation<Pod, PodList, PodResource> nsPods = Mockito.mock(NonNamespaceOperation.class);
        podResource = Mockito.mock(PodResource.class);
        when(client.pods()).thenReturn(pods);
        when(pods.inNamespace(NS)).thenReturn(nsPods);
        when(nsPods.withName(anyString())).thenReturn(podResource);
        provider = new PodScrapingMetricsProvider();
        provider.kubernetesClient = client;
    }

    private static MetricsQueryParams instant(final List<String> names, final List<PodTarget> targets,
                                              final int maxSamples) {
        return MetricsQueryParams.instant(names, null, targets, maxSamples);
    }

    private void givenPodWithPorts(final String podName, final ContainerBuilder container) {
        Pod pod = new PodBuilder()
            .withNewMetadata().withName(podName).withNamespace(NS).endMetadata()
            .withNewSpec().withContainers(container.build()).endSpec()
            .build();
        when(podResource.get()).thenReturn(pod);
    }

    @Test
    void testNullOrEmptyTargetsReturnEmptyWithoutScraping() {
        assertTrue(provider.queryMetrics(instant(null, null, 0)).isEmpty());
        assertTrue(provider.queryMetrics(instant(null, List.of(), 0)).isEmpty());
        verify(client, never()).raw(anyString());
    }

    @Test
    void testExplicitPortAndPathBuildTheProxyUrl() {
        when(client.raw("/api/v1/namespaces/kafka/pods/p-0:9999/proxy/custom")).thenReturn(BODY_ONE);

        List<MetricSample> samples = provider.queryMetrics(
            instant(null, List.of(PodTarget.of(NS, "p-0", 9999, "/custom")), 0));

        assertEquals(2, samples.size());
        verify(podResource, never()).get();
    }

    @Test
    void testDefaultPathIsMetrics() {
        when(client.raw("/api/v1/namespaces/kafka/pods/p-0:9404/proxy/metrics")).thenReturn(BODY_ONE);

        List<MetricSample> samples = provider.queryMetrics(
            instant(null, List.of(new PodTarget(NS, "p-0", 9404, null)), 0));

        assertEquals(2, samples.size());
    }

    @Test
    void testMetricNameFilterIsApplied() {
        when(client.raw(anyString())).thenReturn(BODY_ONE);

        List<MetricSample> samples = provider.queryMetrics(
            instant(List.of("up"), List.of(PodTarget.of(NS, "p-0", 9404, "/metrics")), 0));

        assertEquals(1, samples.size());
        assertEquals("up", samples.getFirst().name());
    }

    @Test
    void testPortNamedTcpPrometheusIsAutoDetected() {
        givenPodWithPorts("p-0", new ContainerBuilder().withName("kafka")
            .addNewPort().withName("tcp-clients").withContainerPort(9092).endPort()
            .addNewPort().withName("tcp-prometheus").withContainerPort(9999).endPort());
        when(client.raw("/api/v1/namespaces/kafka/pods/p-0:9999/proxy/metrics")).thenReturn(BODY_TWO);

        List<MetricSample> samples = provider.queryMetrics(instant(null, List.of(PodTarget.of(NS, "p-0")), 0));

        assertEquals(1, samples.size());
    }

    @Test
    void testPort9404IsUsedWhenPrometheusPortIsNotNamed() {
        givenPodWithPorts("p-0", new ContainerBuilder().withName("kafka")
            .addNewPort().withName("metrics").withContainerPort(9404).endPort());
        when(client.raw("/api/v1/namespaces/kafka/pods/p-0:9404/proxy/metrics")).thenReturn(BODY_TWO);

        assertEquals(1, provider.queryMetrics(instant(null, List.of(PodTarget.of(NS, "p-0")), 0)).size());
    }

    @Test
    void testFallsBackToPort8080WhenNoPortCanBeDetected() {
        givenPodWithPorts("p-0", new ContainerBuilder().withName("kafka")
            .addNewPort().withName("tcp-clients").withContainerPort(9092).endPort());
        when(client.raw("/api/v1/namespaces/kafka/pods/p-0:8080/proxy/metrics")).thenReturn(BODY_TWO);

        assertEquals(1, provider.queryMetrics(instant(null, List.of(PodTarget.of(NS, "p-0")), 0)).size());
    }

    @Test
    void testFallsBackToPort8080WhenPodDoesNotExist() {
        when(podResource.get()).thenReturn(null);
        when(client.raw("/api/v1/namespaces/kafka/pods/p-0:8080/proxy/metrics")).thenReturn(BODY_TWO);

        assertEquals(1, provider.queryMetrics(instant(null, List.of(PodTarget.of(NS, "p-0")), 0)).size());
    }

    @Test
    void testFallsBackToPort8080WhenPodLookupFails() {
        when(podResource.get()).thenThrow(new RuntimeException("forbidden"));
        when(client.raw("/api/v1/namespaces/kafka/pods/p-0:8080/proxy/metrics")).thenReturn(BODY_TWO);

        assertEquals(1, provider.queryMetrics(instant(null, List.of(PodTarget.of(NS, "p-0")), 0)).size());
    }

    @Test
    void testSampleCapStopsMidPodAndSkipsRemainingTargets() {
        when(client.raw("/api/v1/namespaces/kafka/pods/p-0:9404/proxy/metrics")).thenReturn(BODY_ONE);

        List<MetricSample> samples = provider.queryMetrics(instant(null, List.of(
            PodTarget.of(NS, "p-0", 9404, "/metrics"),
            PodTarget.of(NS, "p-1", 9404, "/metrics")), 1));

        assertEquals(1, samples.size());
        verify(client, never()).raw("/api/v1/namespaces/kafka/pods/p-1:9404/proxy/metrics");
    }

    @Test
    void testZeroMaxSamplesMeansUnlimited() {
        when(client.raw("/api/v1/namespaces/kafka/pods/p-0:9404/proxy/metrics")).thenReturn(BODY_ONE);
        when(client.raw("/api/v1/namespaces/kafka/pods/p-1:9404/proxy/metrics")).thenReturn(BODY_TWO);

        List<MetricSample> samples = provider.queryMetrics(instant(null, List.of(
            PodTarget.of(NS, "p-0", 9404, "/metrics"),
            PodTarget.of(NS, "p-1", 9404, "/metrics")), 0));

        assertEquals(3, samples.size());
    }

    @Test
    void testPartialFailureReturnsSamplesFromHealthyPods() {
        when(client.raw("/api/v1/namespaces/kafka/pods/p-0:9404/proxy/metrics"))
            .thenThrow(new RuntimeException("connection refused"));
        when(client.raw("/api/v1/namespaces/kafka/pods/p-1:9404/proxy/metrics")).thenReturn(BODY_TWO);

        List<MetricSample> samples = provider.queryMetrics(instant(null, List.of(
            PodTarget.of(NS, "p-0", 9404, "/metrics"),
            PodTarget.of(NS, "p-1", 9404, "/metrics")), 0));

        assertEquals(1, samples.size());
    }

    @Test
    void testAllTargetsFailingThrowsWithEveryFailure() {
        when(client.raw(anyString())).thenThrow(new RuntimeException("connection refused"));

        MetricsQueryException e = assertThrows(MetricsQueryException.class, () -> provider.queryMetrics(
            instant(null, List.of(
                PodTarget.of(NS, "p-0", 9404, "/metrics"),
                PodTarget.of(NS, "p-1", 9404, "/metrics")), 0)));

        assertTrue(e.getMessage().contains("kafka/p-0"), e.getMessage());
        assertTrue(e.getMessage().contains("kafka/p-1"), e.getMessage());
    }

    @Test
    void testEmptyBodyYieldsNoSamplesAndIsNotAFailure() {
        when(client.raw(anyString())).thenReturn("");

        assertTrue(provider.queryMetrics(
            instant(null, List.of(PodTarget.of(NS, "p-0", 9404, "/metrics")), 0)).isEmpty());
    }

    @Test
    void testRangeQueryIsServedAsPointInTime() {
        when(client.raw(anyString())).thenReturn(BODY_TWO);
        MetricsQueryParams range = MetricsQueryParams.range(null, null,
            List.of(PodTarget.of(NS, "p-0", 9404, "/metrics")),
            Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T01:00:00Z"), 60, 0);

        assertEquals(1, provider.queryMetrics(range).size());
    }
}
