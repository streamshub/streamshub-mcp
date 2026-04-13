/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service.log;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.arc.lookup.LookupIfProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Default {@link LogCollectorProvider} implementation that reads logs directly
 * from Kubernetes pod logs via the Fabric8 client.
 *
 * <p>Active when {@code mcp.log.provider=streamshub-kubernetes} (the default).
 * Since the Kubernetes pod log API does not support server-side filtering,
 * this provider fetches raw logs and applies filter and keyword matching
 * via {@link LogFilterUtils} before returning.</p>
 */
@ApplicationScoped
@LookupIfProperty(name = "mcp.log.provider", stringValue = "streamshub-kubernetes", lookupIfMissing = true)
public class KubernetesLogProvider implements LogCollectorProvider {

    @Inject
    KubernetesClient kubernetesClient;

    KubernetesLogProvider() {
    }

    @Override
    public String fetchLogs(final String namespace, final String podName,
                            final int tailLines, final Integer sinceSeconds,
                            final Boolean previous, final String filter,
                            final List<String> keywords,
                            final String startTime, final String endTime) {
        var podResource = kubernetesClient.pods()
            .inNamespace(namespace)
            .withName(podName);

        String rawLogs;
        if (Boolean.TRUE.equals(previous)) {
            rawLogs = podResource.terminated()
                .tailingLines(tailLines + 1)
                .getLog();
        } else if (startTime != null) {
            rawLogs = podResource.sinceTime(startTime)
                .tailingLines(tailLines + 1)
                .getLog();
        } else if (sinceSeconds != null) {
            rawLogs = podResource.sinceSeconds(sinceSeconds)
                .tailingLines(tailLines + 1)
                .getLog();
        } else {
            rawLogs = podResource.tailingLines(tailLines + 1).getLog();
        }

        return LogFilterUtils.applyFilters(rawLogs, filter, keywords);
    }
}
