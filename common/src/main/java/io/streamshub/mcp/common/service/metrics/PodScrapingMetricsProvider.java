/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service.metrics;

import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.arc.lookup.LookupIfProperty;
import io.streamshub.mcp.common.dto.metrics.MetricSample;
import io.streamshub.mcp.common.dto.metrics.MetricsQueryParams;
import io.streamshub.mcp.common.dto.metrics.PodTarget;
import io.streamshub.mcp.common.util.metrics.MetricLabelFilter;
import io.streamshub.mcp.common.util.metrics.PrometheusTextParser;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Metrics provider that scrapes Prometheus text exposition endpoints directly from pods
 * via the Kubernetes API pod proxy.
 */
@ApplicationScoped
@LookupIfProperty(name = "mcp.metrics.provider", stringValue = "streamshub-pod-scraping")
public class PodScrapingMetricsProvider implements MetricsProvider {

    private static final Logger LOG = Logger.getLogger(PodScrapingMetricsProvider.class);

    /** Strimzi convention for the Prometheus metrics port name. */
    private static final String PROMETHEUS_PORT_NAME = "tcp-prometheus";

    /** Strimzi default metrics port. */
    private static final int STRIMZI_METRICS_PORT = 9404;

    /** Generic fallback metrics port. */
    private static final int DEFAULT_METRICS_PORT = 8080;

    @Inject
    KubernetesClient kubernetesClient;

    PodScrapingMetricsProvider() {
        // package-private no-arg constructor for CDI
    }

    @Override
    public List<MetricSample> queryMetrics(final MetricsQueryParams params) {
        if (params.isRangeQuery()) {
            LOG.warn("Pod scraping provider does not support range queries."
                + " Returning point-in-time data instead.");
        }

        List<PodTarget> targets = params.podTargets();
        if (targets == null || targets.isEmpty()) {
            LOG.debug("No pod targets provided for scraping");
            return List.of();
        }

        Set<String> metricFilter = params.metricNames() != null
            ? new HashSet<>(params.metricNames()) : null;

        LOG.debugf("Scraping %d pod target(s), metric filter: %s",
            targets.size(),
            metricFilter != null ? metricFilter : "none");

        List<MetricSample> allSamples = new ArrayList<>();

        for (PodTarget target : targets) {
            int port = resolvePort(target);
            String path = target.path() != null ? target.path() : PodTarget.DEFAULT_PATH;
            String proxyUrl = String.format("/api/v1/namespaces/%s/pods/%s:%d/proxy%s",
                target.namespace(), target.podName(), port, path);

            LOG.debugf("Scraping metrics from %s", proxyUrl);

            try {
                String body = kubernetesClient.raw(proxyUrl);
                if (body != null && !body.isEmpty()) {
                    List<MetricSample> samples = PrometheusTextParser.parse(body, metricFilter);
                    LOG.debugf("Scraped %d sample(s) from pod %s/%s",
                        samples.size(), target.namespace(), target.podName());
                    for (MetricSample sample : samples) {
                        allSamples.add(MetricSample.of(sample.name(),
                            MetricLabelFilter.filterLabels(sample.labels()),
                            sample.value(), sample.timestamp()));
                    }
                } else {
                    LOG.debugf("Empty metrics response from pod %s/%s",
                        target.namespace(), target.podName());
                }
            } catch (Exception e) {
                LOG.debugf("Error scraping metrics from pod %s/%s: %s",
                    target.namespace(), target.podName(), e.getMessage());
            }
        }

        LOG.debugf("Scraping complete: collected %d total sample(s) from %d target(s)",
            allSamples.size(), targets.size());

        return allSamples;
    }

    /**
     * Resolves the metrics port for a pod target. If the target has an explicit port,
     * use it. Otherwise, look up the pod and try to auto-detect the port.
     *
     * @param target the pod target
     * @return the resolved port number
     */
    private int resolvePort(final PodTarget target) {
        if (target.port() != null) {
            return target.port();
        }

        try {
            Pod pod = kubernetesClient.pods()
                .inNamespace(target.namespace())
                .withName(target.podName())
                .get();

            if (pod != null && pod.getSpec() != null && pod.getSpec().getContainers() != null) {
                // First look for a port named "tcp-prometheus"
                for (io.fabric8.kubernetes.api.model.Container container : pod.getSpec().getContainers()) {
                    if (container.getPorts() != null) {
                        for (ContainerPort cp : container.getPorts()) {
                            if (PROMETHEUS_PORT_NAME.equals(cp.getName())) {
                                return cp.getContainerPort();
                            }
                        }
                    }
                }

                // Fallback: check for known default ports
                for (io.fabric8.kubernetes.api.model.Container container : pod.getSpec().getContainers()) {
                    if (container.getPorts() != null) {
                        for (ContainerPort cp : container.getPorts()) {
                            if (cp.getContainerPort() == STRIMZI_METRICS_PORT) {
                                return STRIMZI_METRICS_PORT;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.debugf("Could not auto-detect metrics port for pod %s/%s: %s",
                target.namespace(), target.podName(), e.getMessage());
        }

        return DEFAULT_METRICS_PORT;
    }
}
