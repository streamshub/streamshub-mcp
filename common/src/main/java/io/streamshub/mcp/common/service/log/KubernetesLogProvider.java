/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service.log;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.quarkus.arc.lookup.LookupIfProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

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

    private static final Logger LOG = Logger.getLogger(KubernetesLogProvider.class);

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
        PodResource podResource = kubernetesClient.pods()
            .inNamespace(namespace)
            .withName(podName);

        List<String> containerNames = getContainerNames(podResource);

        if (containerNames.size() <= 1) {
            String rawLogs = fetchContainerLogs(podResource, null,
                tailLines, sinceSeconds, previous, startTime);
            return LogFilterUtils.applyFilters(rawLogs, filter, keywords);
        }

        // Multi-container pod: fetch logs from each container
        int perContainerLines = Math.max(1, tailLines / containerNames.size());
        StringBuilder combined = new StringBuilder();
        for (String container : containerNames) {
            String containerLogs = fetchContainerLogs(podResource, container,
                perContainerLines, sinceSeconds, previous, startTime);
            String filtered = LogFilterUtils.applyFilters(containerLogs, filter, keywords);
            if (filtered != null && !filtered.isEmpty()) {
                combined.append("--- container: ").append(container).append(" ---\n");
                combined.append(filtered).append("\n");
            }
        }
        return combined.isEmpty() ? null : combined.toString();
    }

    private List<String> getContainerNames(final PodResource podResource) {
        try {
            Pod pod = podResource.get();
            if (pod != null && pod.getSpec() != null && pod.getSpec().getContainers() != null) {
                List<String> names = pod.getSpec().getContainers().stream()
                    .map(Container::getName)
                    .toList();
                if (!names.isEmpty()) {
                    return names;
                }
            }
        } catch (Exception e) {
            LOG.debugf("Could not list containers for pod: %s", e.getMessage());
        }
        return List.of();
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private String fetchContainerLogs(final PodResource podResource,
                                       final String containerName,
                                       final int tailLines,
                                       final Integer sinceSeconds,
                                       final Boolean previous,
                                       final String startTime) {
        var resource = containerName != null
            ? podResource.inContainer(containerName)
            : podResource;

        if (Boolean.TRUE.equals(previous)) {
            return resource.terminated()
                .tailingLines(tailLines + 1)
                .getLog();
        } else if (startTime != null) {
            return resource.sinceTime(startTime)
                .tailingLines(tailLines + 1)
                .getLog();
        } else if (sinceSeconds != null) {
            return resource.sinceSeconds(sinceSeconds)
                .tailingLines(tailLines + 1)
                .getLog();
        } else {
            return resource.tailingLines(tailLines + 1).getLog();
        }
    }
}
