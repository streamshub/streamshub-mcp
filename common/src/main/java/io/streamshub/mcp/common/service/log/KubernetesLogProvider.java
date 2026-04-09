/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service.log;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.arc.lookup.LookupIfProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Default {@link LogCollectorProvider} implementation that reads logs directly
 * from Kubernetes pod logs via the Fabric8 client.
 *
 * <p>Active when {@code mcp.log.provider=streamshub-kubernetes} (the default).
 * Set {@code mcp.log.provider} to a different value (e.g., {@code loki})
 * to use an alternative log provider.</p>
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
                            final Boolean previous) {
        var podResource = kubernetesClient.pods()
            .inNamespace(namespace)
            .withName(podName);

        if (Boolean.TRUE.equals(previous)) {
            return podResource.terminated()
                .tailingLines(tailLines + 1)
                .getLog();
        }

        if (sinceSeconds != null) {
            return podResource.sinceSeconds(sinceSeconds)
                .tailingLines(tailLines + 1)
                .getLog();
        }

        return podResource.tailingLines(tailLines + 1).getLog();
    }
}
