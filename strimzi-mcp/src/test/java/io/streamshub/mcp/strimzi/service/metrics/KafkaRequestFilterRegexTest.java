/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.metrics;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.streamshub.mcp.common.dto.metrics.MetricSample;
import io.streamshub.mcp.common.dto.metrics.MetricsQueryParams;
import io.streamshub.mcp.common.dto.metrics.PodTarget;
import io.streamshub.mcp.common.service.metrics.PodScrapingMetricsProvider;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * Checks the production request type and quantile regexes of the {@code performance} category
 * against every quantile spelling, with the semantics of both metrics providers.
 */
class KafkaRequestFilterRegexTest {

    /** Quantile label values, "" standing for a series without the label. */
    private static final List<String> SPELLINGS = List.of("", "0.5", "0.50", "0.500", "0.99", "0.990", "0.999", "0.95");
    private static final Set<String> KEPT = Set.of("", "0.5", "0.50", "0.500", "0.99", "0.990");

    KafkaRequestFilterRegexTest() {
    }

    private static Map<String, String> defaultMatchers() {
        return KafkaMetricsService.requestRegexMatchers(
            KafkaMetricsService.resolveRequestTypes(null, true), true);
    }

    /**
     * PromQL {@code =~} is anchored at both ends and compares a missing label as "". A Java full match
     * has the same semantics for these regexes.
     */
    @Test
    void prometheusMatcherKeepsTheDefaultQuantilesAndSeriesWithoutTheLabel() {
        Pattern quantile = Pattern.compile(defaultMatchers().get("quantile"));
        Pattern request = Pattern.compile(defaultMatchers().get("request"));

        assertEquals(KEPT, SPELLINGS.stream().filter(q -> quantile.matcher(q).matches()).collect(Collectors.toSet()));
        assertEquals(List.of(true, true, true, true, false),
            List.of("", "Produce", "FetchConsumer", "FetchFollower", "Metadata").stream()
                .map(r -> request.matcher(r).matches()).toList());
    }

    @Test
    void prometheusQueryRendersTheProductionRegexes() throws Exception {
        Class<?> type = Class.forName("io.streamshub.mcp.metrics.prometheus.service.PrometheusMetricsProvider");
        Constructor<?> constructor = type.getDeclaredConstructor();
        constructor.setAccessible(true);
        Method build = type.getDeclaredMethod("buildPromQL", List.class, Map.class, Map.class);
        build.setAccessible(true);

        String promql = (String) build.invoke(constructor.newInstance(),
            List.of("kafka_network_requestmetrics_totaltimems"), Map.of("namespace", "kafka"), defaultMatchers());

        assertEquals("kafka_network_requestmetrics_totaltimems{namespace=\"kafka\","
            + "request=~\"Produce|FetchConsumer|FetchFollower|\",quantile=~\"|0\\\\.50*|0\\\\.990*\"}", promql);
    }

    @Test
    void podScrapingKeepsTheDefaultQuantilesAndSeriesWithoutTheLabel() throws Exception {
        StringBuilder body = new StringBuilder();
        for (String q : SPELLINGS) {
            String quantile = q.isEmpty() ? "" : ",quantile=\"" + q + "\"";
            body.append("req{request=\"Produce\"").append(quantile).append("} 1\n");
        }
        body.append("req{request=\"Metadata\",quantile=\"0.99\"} 1\n");
        body.append("idle_percent 0.9\n");

        KubernetesClient client = Mockito.mock(KubernetesClient.class);
        when(client.raw("/api/v1/namespaces/kafka/pods/p-0:9404/proxy/metrics")).thenReturn(body.toString());
        PodScrapingMetricsProvider provider = podScrapingProvider(client);

        List<MetricSample> samples = provider.queryMetrics(MetricsQueryParams.instant(null, null,
                List.of(PodTarget.of("kafka", "p-0", 9404, "/metrics")), 0)
            .withLabelRegexMatchers(defaultMatchers()));

        Set<String> keptQuantiles = samples.stream()
            .filter(s -> "req".equals(s.name()))
            .map(s -> s.labels().getOrDefault("quantile", ""))
            .collect(Collectors.toCollection(LinkedHashSet::new));
        assertEquals(KEPT, keptQuantiles);
        assertEquals(1, samples.stream().filter(s -> "idle_percent".equals(s.name())).count());
        assertEquals(0, samples.stream().filter(s -> "Metadata".equals(s.labels().get("request"))).count());
    }

    private static PodScrapingMetricsProvider podScrapingProvider(final KubernetesClient client) throws Exception {
        Constructor<PodScrapingMetricsProvider> constructor = PodScrapingMetricsProvider.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        PodScrapingMetricsProvider provider = constructor.newInstance();
        Field field = PodScrapingMetricsProvider.class.getDeclaredField("kubernetesClient");
        field.setAccessible(true);
        field.set(provider, client);
        return provider;
    }
}
