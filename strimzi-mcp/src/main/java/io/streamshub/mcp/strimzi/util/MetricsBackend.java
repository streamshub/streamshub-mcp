/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.util;

import io.strimzi.api.kafka.model.common.metrics.StrimziMetricsReporter;
import io.strimzi.api.kafka.model.kafka.Kafka;

/**
 * The metrics backend in use for a Kafka cluster, resolved from
 * {@code spec.kafka.metricsConfig}.
 *
 * <p>JMX Prometheus Exporter and Strimzi Metrics Reporter expose the same MBeans
 * under structurally different metric names. The backend determines which name set
 * to request from the metrics provider.</p>
 */
public enum MetricsBackend {

    /**
     * JMX Prometheus Exporter ({@code type: jmxPrometheusExporter}).
     * This is the default when {@code metricsConfig} is absent or unrecognised.
     */
    JMX_EXPORTER,

    /**
     * Strimzi Metrics Reporter ({@code type: strimziMetricsReporter}).
     * Names differ from JMX Exporter for most broker-topic metrics.
     */
    STRIMZI_REPORTER;

    /**
     * Resolves the metrics backend from the Kafka CR.
     *
     * <p>Returns {@link #STRIMZI_REPORTER} when {@code spec.kafka.metricsConfig} is a
     * {@link StrimziMetricsReporter} instance, {@link #JMX_EXPORTER} otherwise
     * (including when {@code metricsConfig} is {@code null}).</p>
     *
     * @param kafka the Kafka CR to inspect (must not be null)
     * @return the resolved backend; never null
     */
    public static MetricsBackend fromKafka(final Kafka kafka) {
        if (kafka.getSpec() == null || kafka.getSpec().getKafka() == null) {
            return JMX_EXPORTER;
        }
        if (kafka.getSpec().getKafka().getMetricsConfig() instanceof StrimziMetricsReporter) {
            return STRIMZI_REPORTER;
        }
        return JMX_EXPORTER;
    }
}
