/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.loki;

import io.quarkus.arc.lookup.LookupIfProperty;
import io.streamshub.mcp.common.dto.LogFetchRequest;
import io.streamshub.mcp.common.service.log.LogCollectorProvider;
import io.streamshub.mcp.common.service.log.LogFilterUtils;
import io.streamshub.mcp.common.service.log.LogQueryException;
import io.streamshub.mcp.common.util.ExceptionUtils;
import io.streamshub.mcp.loki.config.LokiConfig;
import io.streamshub.mcp.loki.service.LokiClient;
import io.streamshub.mcp.loki.util.LogQLSanitizer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * {@link LogCollectorProvider} implementation that queries a Grafana Loki instance
 * for pod logs via the Loki HTTP API.
 *
 * <p>Active when {@code mcp.log.provider=streamshub-loki} is set. Requires Loki
 * connection configuration via {@code quarkus.rest-client.loki.*} properties
 * and label mapping via {@code mcp.log.loki.*} properties.</p>
 *
 * <p>With {@code mcp.log.loki.unwrap-json=true} (off by default; set it for OpenShift
 * Logging) the query reduces JSON log records, such as the OpenShift Logging ViaQ records,
 * to their message field ({@code mcp.log.loki.json-message-field}, default {@code message})
 * with a {@code json | line_format | drop} stage right after the stream selector. Line
 * filters then match only the message, and only the message is returned. Plain-text
 * lines (Promtail, Grafana Alloy) fall back to their original text. By default raw lines
 * are returned, so a JSON line that an application logs itself keeps its level field.</p>
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

    /**
     * Hint for a {@code container} filter that returned no lines from any pod while the
     * container's stream also has no line within {@code mcp.log.loki.max-lookback}: usually a
     * wrong container label mapping. Arguments: the container, the configured container label.
     */
    static final String CONTAINER_NO_LINES_WARNING = "No log lines were returned for container '%s' from any pod."
        + " If the container exists, check the container name and mcp.log.loki.label.container"
        + " (currently '%s'; OpenShift Logging uses kubernetes_container_name).";

    @Inject
    LokiConfig config;

    @Inject
    @RestClient
    Instance<LokiClient> lokiClient;

    LokiLogProvider() {
    }

    @Override
    public String fetchLogs(final LogFetchRequest request) {
        final String namespace = request.namespace();
        final String podName = request.podName();
        final int tailLines = request.tailLines();
        final Boolean previous = request.previous();
        final String filter = request.filter();
        final List<String> keywords = request.keywords();

        if (Boolean.TRUE.equals(previous)) {
            LOG.debugf("Loki does not distinguish previous container logs; "
                + "using time range to capture pre-restart logs for pod %s", podName);
        }

        String query = buildLogQuery(namespace, podName, filter, keywords, request.container());

        QueryWindow window = resolveWindow(request, Instant.now());
        Instant start = window.start();
        Instant end = window.end();

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
            throw new LogQueryException(
                String.format("Loki query failed for pod %s/%s: %s",
                    namespace, podName, ExceptionUtils.rootCauseMessage(e)), e);
        }
    }

    /**
     * Build a LogQL query with stream selector, the JSON message unwrap stage when
     * {@code mcp.log.loki.unwrap-json} is enabled, and optional line filters.
     *
     * @param namespace the Kubernetes namespace
     * @param podName   the pod name
     * @param filter    optional filter: "errors", "warnings", or a regex pattern
     * @param keywords  optional list of keywords for line matching
     * @param container optional container name to filter logs
     * @return the LogQL query string
     */
    String buildLogQuery(final String namespace, final String podName,
                         final String filter, final List<String> keywords,
                         final String container) {
        return buildLogQuery(namespace, podName, filter, keywords, container,
            config.unwrapJson() ? config.jsonMessageField() : null);
    }

    /**
     * Build a LogQL query with stream selector, an optional JSON message unwrap stage
     * and optional line filters.
     *
     * <p>When {@code messageField} is set, the stage
     * {@code | json mcp_message="<field>" | line_format "{{if .mcp_message}}{{.mcp_message}}{{else}}{{__line__}}{{end}}"}
     * follows the selector, then a {@code !~} line filter that drops lines that are still a
     * JSON record with an empty message field (blank log lines, which would otherwise come
     * back as the whole record), then {@code | drop mcp_message, __error__, __error_details__}.
     * The line filters and the returned lines see only the message. Lines that are not JSON,
     * or have no such field, keep their original text.</p>
     *
     * @param namespace    the Kubernetes namespace
     * @param podName      the pod name
     * @param filter       optional filter: "errors", "warnings", or a regex pattern
     * @param keywords     optional list of keywords for line matching
     * @param container    optional container name to filter logs
     * @param messageField JSON field holding the message, or null to keep raw lines
     * @return the LogQL query string
     * @throws IllegalArgumentException if {@code messageField} is not a valid JSON field path
     */
    String buildLogQuery(final String namespace, final String podName,
                         final String filter, final List<String> keywords,
                         final String container, final String messageField) {
        StringBuilder query = new StringBuilder();
        query.append(String.format("{%s=\"%s\", %s=\"%s\"",
            LogQLSanitizer.sanitizeLabelName(config.label().namespace()),
            LogQLSanitizer.sanitizeLabelValue(namespace),
            LogQLSanitizer.sanitizeLabelName(config.label().pod()),
            LogQLSanitizer.sanitizeLabelValue(podName)));

        if (container != null && !container.isBlank()) {
            query.append(String.format(", %s=\"%s\"",
                LogQLSanitizer.sanitizeLabelName(config.label().container()),
                LogQLSanitizer.sanitizeLabelValue(container.trim())));
        }
        query.append("}");

        if (messageField != null) {
            String field = LogQLSanitizer.sanitizeJsonField(messageField);
            String lastSegment = field.substring(field.lastIndexOf('.') + 1);
            // A record whose message is empty falls back to the whole JSON record in line_format
            // (Loki renders a missing and an empty field the same), so drop such records before
            // the line filters. The filter sits before "| drop": after a drop label list, "!~"
            // would parse as a conditional-drop matcher, not a line filter.
            // The regex is a text heuristic, not a JSON check. It assumes compact JSON (no space
            // around ':'), as Vector emits for ViaQ, so "message":null and pretty-printed records
            // still come back whole. It matches the field's last segment at any depth, so a record
            // with a non-empty message but another empty field of that name is dropped too.
            String emptyMessageRegex = "^\\{.*\"" + lastSegment + "\":\"\"";
            query.append(" | json mcp_message=\"").append(field)
                .append("\" | line_format \"{{if .mcp_message}}{{.mcp_message}}{{else}}{{__line__}}{{end}}\"")
                .append(" !~ \"").append(LogQLSanitizer.sanitizeLabelValue(emptyMessageRegex)).append("\"")
                .append(" | drop mcp_message, __error__, __error_details__");
        }

        String filterRegex = LogFilterUtils.resolveFilterRegex(filter);
        if (filterRegex != null) {
            query.append(" |~ \"").append(LogQLSanitizer.sanitizeLabelValue(filterRegex)).append("\"");
        }

        if (keywords != null && !keywords.isEmpty()) {
            String keywordPattern = keywords.stream()
                .filter(k -> k != null && !k.isBlank())
                .map(k -> LogQLSanitizer.sanitizeLabelValue(LogQLSanitizer.escapeRegexLiteral(k.trim())))
                .collect(Collectors.joining("|"));
            if (!keywordPattern.isEmpty()) {
                query.append(" |~ \"(?i)(").append(keywordPattern).append(")\"");
            }
        }

        return query.toString();
    }

    /**
     * Resolve the time window of a query.
     *
     * <ol>
     *   <li>{@code startTime} set: from {@code startTime} to {@code endTime}, or to now</li>
     *   <li>{@code sinceSeconds} set: the last {@code sinceSeconds}</li>
     *   <li>a filter or a non-blank keyword: the last {@code mcp.log.loki.filter-lookback}
     *       (capped at {@code max-lookback})</li>
     *   <li>otherwise: the last {@code mcp.log.loki.max-lookback}</li>
     * </ol>
     *
     * @param request the fetch request
     * @param now     the current instant
     * @return the query window
     */
    QueryWindow resolveWindow(final LogFetchRequest request, final Instant now) {
        if (request.startTime() != null) {
            Instant end = request.endTime() != null ? Instant.parse(request.endTime()) : now;
            return new QueryWindow(Instant.parse(request.startTime()), end);
        }
        if (request.sinceSeconds() != null) {
            return new QueryWindow(now.minusSeconds(request.sinceSeconds()), now);
        }
        if (hasLineFilters(request)) {
            return new QueryWindow(now.minus(effectiveFilterLookback()), now);
        }
        return new QueryWindow(now.minus(config.maxLookback()), now);
    }

    /**
     * Report the bounded window of a filtered query without a time range, so the caller
     * knows older matches were not searched.
     *
     * @param request the fetch request
     * @return the note, or null when the request has a time range or no line filters
     */
    @Override
    public String searchScopeNote(final LogFetchRequest request) {
        if (request.startTime() != null || request.sinceSeconds() != null || !hasLineFilters(request)) {
            return null;
        }
        return String.format("Loki searched only the last %d minutes for lines matching the filter or keywords,"
            + " because no sinceMinutes or startTime/endTime was given. Pass sinceMinutes to search further back.",
            effectiveFilterLookback().toMinutes());
    }

    /**
     * Hint at a wrong container label mapping when a {@code container} request returned no lines.
     * The hint is given only when the container's stream has no line at all within
     * {@code mcp.log.loki.max-lookback}. An unfiltered request without a time range already searched
     * that window, so its empty result is the answer. Otherwise one probe of the same selector over
     * {@code max-lookback}, without line filters and with limit 1, tells a wrong mapping (still empty)
     * from a filtered search that matched nothing, or from a short {@code sinceMinutes} window in which
     * a correctly mapped container logged nothing.
     *
     * @param request the fetch request of the first pod
     * @return the hint, or null when there is no container, the probe found lines, or the probe failed
     */
    @Override
    public String emptyContainerHint(final LogFetchRequest request) {
        String container = request.container();
        if (container == null || container.isBlank()) {
            return null;
        }
        if (!searchedMaxLookbackUnfiltered(request) && containerStreamHasLines(request)) {
            return null;
        }
        return String.format(CONTAINER_NO_LINES_WARNING, container.trim(), config.label().container());
    }

    private static boolean searchedMaxLookbackUnfiltered(final LogFetchRequest request) {
        return request.startTime() == null && request.sinceSeconds() == null && !hasLineFilters(request);
    }

    private boolean containerStreamHasLines(final LogFetchRequest request) {
        String probe = buildLogQuery(request.namespace(), request.podName(), null, null, request.container(), null);
        Instant now = Instant.now();
        try {
            LokiResponse response = lokiClient.get().queryRange(
                probe, toEpochNanos(now.minus(config.maxLookback())), toEpochNanos(now), 1, "backward");
            return extractLogLines(response) != null;
        } catch (Exception e) {
            LOG.debugf("Loki container probe failed for pod %s/%s: %s",
                request.namespace(), request.podName(), e.getMessage());
            // Cannot tell a wrong mapping from an empty search: give no hint.
            return true;
        }
    }

    private Duration effectiveFilterLookback() {
        Duration filterLookback = config.filterLookback();
        Duration maxLookback = config.maxLookback();
        return filterLookback.compareTo(maxLookback) <= 0 ? filterLookback : maxLookback;
    }

    private static boolean hasLineFilters(final LogFetchRequest request) {
        return LogFilterUtils.hasLineFilters(request.filter(), request.keywords());
    }

    /**
     * Time window of a Loki query.
     *
     * @param start the inclusive start
     * @param end   the end
     */
    record QueryWindow(Instant start, Instant end) {
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
