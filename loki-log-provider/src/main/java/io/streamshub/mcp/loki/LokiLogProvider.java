/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.loki;

import io.quarkus.arc.lookup.LookupIfProperty;
import io.streamshub.mcp.common.service.log.LogProvider;
import io.streamshub.mcp.loki.config.LokiConfig;
import io.streamshub.mcp.loki.service.LokiClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * {@link LogProvider} implementation that queries a Grafana Loki instance
 * for pod logs via the Loki HTTP API.
 *
 * <p>Active when {@code mcp.log.provider=loki} is set. Requires Loki
 * connection configuration via {@code quarkus.rest-client.loki.*} properties
 * and label mapping via {@code mcp.log.loki.*} properties.</p>
 *
 * <p>The {@code previous} parameter is ignored because Loki stores all
 * container logs (current and previous) in the same stream. Use a
 * sufficiently wide time range to capture pre-restart logs.</p>
 */
@ApplicationScoped
@LookupIfProperty(name = "mcp.log.provider", stringValue = "loki")
public class LokiLogProvider implements LogProvider {

    private static final Logger LOG = Logger.getLogger(LokiLogProvider.class);
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final int DEFAULT_SINCE_SECONDS = 3600;

    @Inject
    LokiConfig config;

    @Inject
    @RestClient
    Instance<LokiClient> lokiClient;

    LokiLogProvider() {
    }

    @Override
    public String fetchLogs(final String namespace, final String podName,
                            final int tailLines, final Integer sinceSeconds,
                            final Boolean previous) {
        if (Boolean.TRUE.equals(previous)) {
            LOG.debugf("Loki does not distinguish previous container logs; "
                + "using time range to capture pre-restart logs for pod %s", podName);
        }

        String query = buildLogQuery(namespace, podName);
        int effectiveSince = sinceSeconds != null ? sinceSeconds : DEFAULT_SINCE_SECONDS;

        Instant now = Instant.now();
        Instant start = now.minusSeconds(effectiveSince);

        LOG.debugf("Querying Loki: query=%s, range=%ds", query, effectiveSince);

        try {
            LokiResponse response = lokiClient.get().queryRange(
                query,
                toEpochNanos(start),
                toEpochNanos(now),
                tailLines + 1,
                "backward");
            return extractLogLines(response);
        } catch (Exception e) {
            LOG.warnf("Failed to query Loki for pod %s/%s: %s", namespace, podName, e.getMessage());
            return null;
        }
    }

    /**
     * Build a LogQL stream selector for the given namespace and pod.
     *
     * @param namespace the Kubernetes namespace
     * @param podName   the pod name
     * @return the LogQL query string
     */
    String buildLogQuery(final String namespace, final String podName) {
        return String.format("{%s=\"%s\", %s=\"%s\"}",
            config.label().namespace(), namespace,
            config.label().pod(), podName);
    }

    /**
     * Extract log lines from a Loki response and join them into a single string.
     * Lines are sorted chronologically (oldest first) since Loki returns them
     * in reverse order when {@code direction=backward}.
     *
     * @param response the Loki API response
     * @return the concatenated log output, or null if no results
     */
    String extractLogLines(final LokiResponse response) {
        if (response == null || response.data() == null
            || response.data().result() == null) {
            return null;
        }

        List<String> lines = response.data().result().stream()
            .filter(stream -> stream.values() != null)
            .flatMap(stream -> stream.values().stream())
            .filter(entry -> entry.size() >= 2)
            .sorted(Comparator.comparing(List::getFirst))
            .map(entry -> entry.get(1))
            .toList();

        return lines.isEmpty() ? null : String.join("\n", lines) + "\n";
    }

    private static long toEpochNanos(final Instant instant) {
        return instant.getEpochSecond() * NANOS_PER_SECOND + instant.getNano();
    }
}
