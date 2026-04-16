/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.loki;

import io.quarkus.arc.lookup.LookupIfProperty;
import io.streamshub.mcp.common.service.log.LogCollectorProvider;
import io.streamshub.mcp.loki.config.LokiConfig;
import io.streamshub.mcp.loki.service.LokiClient;
import io.streamshub.mcp.loki.util.LogQLSanitizer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * {@link LogCollectorProvider} implementation that queries a Grafana Loki instance
 * for pod logs via the Loki HTTP API.
 *
 * <p>Active when {@code mcp.log.provider=streamshub-loki} is set. Requires Loki
 * connection configuration via {@code quarkus.rest-client.loki.*} properties
 * and label mapping via {@code mcp.log.loki.*} properties.</p>
 *
 * <p>The {@code previous} parameter is ignored because Loki stores all
 * container logs (current and previous) in the same stream. Use a
 * sufficiently wide time range to capture pre-restart logs.</p>
 */
@ApplicationScoped
@LookupIfProperty(name = "mcp.log.provider", stringValue = "streamshub-loki")
public class LokiLogProvider implements LogCollectorProvider {

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
                            final Boolean previous, final String filter,
                            final List<String> keywords,
                            final String startTime, final String endTime) {
        if (Boolean.TRUE.equals(previous)) {
            LOG.debugf("Loki does not distinguish previous container logs; "
                + "using time range to capture pre-restart logs for pod %s", podName);
        }

        String query = buildLogQuery(namespace, podName, filter, keywords);

        Instant start;
        Instant end;
        if (startTime != null && endTime != null) {
            start = Instant.parse(startTime);
            end = Instant.parse(endTime);
        } else {
            int effectiveSince = sinceSeconds != null ? sinceSeconds : DEFAULT_SINCE_SECONDS;
            end = Instant.now();
            start = end.minusSeconds(effectiveSince);
        }

        LOG.debugf("Querying Loki: query=%s, start=%s, end=%s", query, start, end);

        try {
            LokiResponse response = lokiClient.get().queryRange(
                query,
                toEpochNanos(start),
                toEpochNanos(end),
                tailLines + 1,
                "backward");
            return extractLogLines(response);
        } catch (Exception e) {
            LOG.warnf("Failed to query Loki for pod %s/%s: %s", namespace, podName, e.getMessage());
            return null;
        }
    }

    /**
     * Build a LogQL query with stream selector and optional line filters.
     *
     * @param namespace the Kubernetes namespace
     * @param podName   the pod name
     * @param filter    optional filter: "errors", "warnings", or a regex pattern
     * @param keywords  optional list of keywords for line matching
     * @return the LogQL query string
     */
    String buildLogQuery(final String namespace, final String podName,
                         final String filter, final List<String> keywords) {
        StringBuilder query = new StringBuilder();
        query.append(String.format("{%s=\"%s\", %s=\"%s\"}",
            LogQLSanitizer.sanitizeLabelName(config.label().namespace()),
            LogQLSanitizer.sanitizeLabelValue(namespace),
            LogQLSanitizer.sanitizeLabelName(config.label().pod()),
            LogQLSanitizer.sanitizeLabelValue(podName)));

        String filterRegex = resolveFilterRegex(filter);
        if (filterRegex != null) {
            query.append(" |~ \"").append(LogQLSanitizer.sanitizeLabelValue(filterRegex)).append("\"");
        }

        if (keywords != null && !keywords.isEmpty()) {
            String keywordPattern = keywords.stream()
                .filter(k -> k != null && !k.isBlank())
                .map(k -> LogQLSanitizer.sanitizeLabelValue(k.trim()))
                .collect(Collectors.joining("|"));
            if (!keywordPattern.isEmpty()) {
                query.append(" |~ \"(?i)(").append(keywordPattern).append(")\"");
            }
        }

        return query.toString();
    }

    /**
     * Resolve a filter shortcut or custom regex into a LogQL regex pattern.
     *
     * @param filter the filter string
     * @return the regex pattern, or null if no filter
     */
    private String resolveFilterRegex(final String filter) {
        if (filter == null || filter.isBlank()) {
            return null;
        }
        String normalized = filter.trim().toLowerCase(Locale.ENGLISH);
        if ("errors".equals(normalized)) {
            return "(?i)(ERROR|EXCEPTION)";
        }
        if ("warnings".equals(normalized)) {
            return "(?i)(ERROR|EXCEPTION|WARN)";
        }
        return filter.trim();
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
