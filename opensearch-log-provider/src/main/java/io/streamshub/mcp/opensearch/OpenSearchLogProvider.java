/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.opensearch;

import io.quarkus.arc.lookup.LookupIfProperty;
import io.streamshub.mcp.common.dto.LogFetchRequest;
import io.streamshub.mcp.common.service.log.LogCollectorProvider;
import io.streamshub.mcp.common.service.log.LogFilterUtils;
import io.streamshub.mcp.common.service.log.LogQueryException;
import io.streamshub.mcp.common.util.ExceptionUtils;
import io.streamshub.mcp.opensearch.config.OpenSearchConfig;
import io.streamshub.mcp.opensearch.service.OpenSearchClient;
import io.streamshub.mcp.opensearch.util.OpenSearchQuerySanitizer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * OpenSearch-based implementation of {@link LogCollectorProvider}.
 *
 * <p>Activated when {@code mcp.log.provider=streamshub-opensearch}.
 * Queries OpenSearch for logs matching Kubernetes namespace and pod name,
 * supports time-based filtering, regex patterns, and keyword search.</p>
 *
 * <p>The {@code filter} runs as a {@code regexp} query on the message field, which
 * OpenSearch matches against each analyzed term (word), not against the whole line.
 * The {@code errors}/{@code warnings} shortcuts therefore use term patterns
 * ({@link #ERRORS_TERM_REGEXP}, {@link #WARNINGS_TERM_REGEXP}) that follow the shared
 * rule of {@link LogFilterUtils#ERRORS_REGEX} and {@link LogFilterUtils#WARNINGS_REGEX}.</p>
 *
 * <p>Those patterns start with a wildcard, so a search with a {@code filter} or {@code keywords}
 * but without a time range covers only the last {@code mcp.log.opensearch.filter-lookback}
 * (default 1 hour) and reports it through {@link #searchScopeNote(LogFetchRequest)}. Searches
 * without a filter or keywords have no default window.</p>
 */
@ApplicationScoped
@LookupIfProperty(name = "mcp.log.provider", stringValue = "streamshub-opensearch")
public class OpenSearchLogProvider implements LogCollectorProvider {

    private static final Logger LOG = Logger.getLogger(OpenSearchLogProvider.class);

    /**
     * Term pattern for {@code filter=errors}: a {@code fatal} word, or a word that ends with
     * {@code error} or {@code exception}. The standard analyzer lowercases words and keeps a dotted
     * class name such as {@code org.apache.kafka.common.errors.TimeoutException} as one term, so this
     * matches an {@code ERROR}/{@code FATAL} level and exception/error class names, while a config key
     * such as {@code errors.tolerance} does not match. The query sets {@code case_insensitive}.
     */
    static final String ERRORS_TERM_REGEXP = "fatal|.*(error|exception)";

    /**
     * Term pattern for {@code filter=warnings}: {@link #ERRORS_TERM_REGEXP} plus a {@code warn}
     * or {@code warning} word.
     */
    static final String WARNINGS_TERM_REGEXP = "fatal|warn|warning|.*(error|exception)";

    /**
     * Hint for a {@code container} filter that returned no lines from any pod while the indices
     * hold no document at all for that pod and container: usually a wrong container field mapping.
     * Arguments: the container, the configured container field.
     */
    static final String CONTAINER_NO_LINES_WARNING = "No log lines were returned for container '%s' from any pod."
        + " If the container exists, check the container name and mcp.log.opensearch.field.container"
        + " (currently '%s', matched on its .keyword sub-field).";

    @Inject
    OpenSearchConfig config;

    @Inject
    @RestClient
    Instance<OpenSearchClient> opensearchClient;

    OpenSearchLogProvider() {
    }

    @Override
    public String fetchLogs(final LogFetchRequest request) {
        final String namespace = request.namespace();
        final String podName = request.podName();
        final int tailLines = request.tailLines();
        final Integer sinceSeconds = request.sinceSeconds();
        final Boolean previous = request.previous();
        final String filter = request.filter();
        final List<String> keywords = request.keywords();
        final String startTime = request.startTime();
        final String endTime = request.endTime();

        if (Boolean.TRUE.equals(previous)) {
            LOG.debugf("OpenSearch does not distinguish previous container logs; "
                + "using time range to capture pre-restart logs for pod %s", podName);
        }

        String query = buildSearchQuery(namespace, podName, tailLines,
            sinceSeconds, filter, keywords, startTime, endTime, request.container());

        LOG.debugf("Querying OpenSearch: index=%s, query=%s", config.indexPattern(), query);

        try {
            OpenSearchResponse response = opensearchClient.get()
                .search(config.indexPattern(), query);
            return extractLogLines(response);
        } catch (Exception e) {
            LOG.warnf("Failed to query OpenSearch for pod %s/%s: %s",
                namespace, podName, e.getMessage());
            throw new LogQueryException(
                String.format("OpenSearch query failed for pod %s/%s: %s",
                    namespace, podName, ExceptionUtils.rootCauseMessage(e)), e);
        }
    }

    String buildSearchQuery(String namespace, String podName,
                            int tailLines, Integer sinceSeconds,
                            String filter, List<String> keywords,
                            String startTime, String endTime, String container) {
        String msgField = OpenSearchQuerySanitizer.sanitizeFieldName(config.field().message());
        String tsField = OpenSearchQuerySanitizer.sanitizeFieldName(config.field().timestamp());

        List<String> filterClauses = selectorClauses(namespace, podName, container);

        String timeRangeClause = buildTimeRangeClause(tsField, sinceSeconds, startTime, endTime,
            LogFilterUtils.hasLineFilters(filter, keywords));
        if (timeRangeClause != null) {
            filterClauses.add(timeRangeClause);
        }

        String filterRegex = resolveFilterRegex(filter);
        if (filterRegex != null) {
            filterClauses.add(String.format("{\"regexp\":{\"%s\":{\"value\":\"%s\",\"case_insensitive\":true}}}",
                msgField, OpenSearchQuerySanitizer.sanitizeValue(filterRegex)));
        }

        String shouldClause = "";
        if (keywords != null && !keywords.isEmpty()) {
            String keywordClauses = keywords.stream()
                .filter(k -> k != null && !k.isBlank())
                .map(k -> String.format("{\"match_phrase\":{\"%s\":\"%s\"}}",
                    msgField, OpenSearchQuerySanitizer.sanitizeValue(k.trim())))
                .collect(Collectors.joining(","));
            if (!keywordClauses.isEmpty()) {
                shouldClause = String.format(",\"should\":[%s],\"minimum_should_match\":1",
                    keywordClauses);
            }
        }

        return String.format(
            "{\"query\":{\"bool\":{\"filter\":[%s]%s}},\"sort\":[{\"%s\":\"desc\"}],\"size\":%d}",
            String.join(",", filterClauses),
            shouldClause,
            tsField,
            tailLines + 1);
    }

    /**
     * Build the probe for {@link #emptyContainerHint(LogFetchRequest)}: the namespace, pod and
     * container term filters only, with no time range and no text filters, returning at most one
     * hit without its source.
     *
     * @param namespace the Kubernetes namespace
     * @param podName   the pod name
     * @param container the container name
     * @return the probe query body
     */
    String buildContainerProbeQuery(final String namespace, final String podName, final String container) {
        return String.format("{\"query\":{\"bool\":{\"filter\":[%s]}},\"size\":1,\"_source\":false,"
                + "\"terminate_after\":1}",
            String.join(",", selectorClauses(namespace, podName, container)));
    }

    /**
     * Hint at a wrong container field mapping when a {@code container} request returned no lines.
     * One probe of the same namespace, pod and container, without a time range or text filters
     * (indexed, size 1), tells a wrong mapping (no document at all) from a time window or filter
     * that matched nothing.
     *
     * @param request the fetch request of the first pod
     * @return the hint, or null when there is no container, the probe found a document, or the probe failed
     */
    @Override
    public String emptyContainerHint(final LogFetchRequest request) {
        String container = request.container();
        if (container == null || container.isBlank()) {
            return null;
        }
        if (containerHasDocuments(request)) {
            return null;
        }
        return String.format(CONTAINER_NO_LINES_WARNING, container.trim(), config.field().container());
    }

    private boolean containerHasDocuments(final LogFetchRequest request) {
        String probe = buildContainerProbeQuery(request.namespace(), request.podName(), request.container());
        try {
            OpenSearchResponse response = opensearchClient.get().search(config.indexPattern(), probe);
            return response != null && response.hits() != null && response.hits().hits() != null
                && !response.hits().hits().isEmpty();
        } catch (Exception e) {
            LOG.debugf("OpenSearch container probe failed for pod %s/%s: %s",
                request.namespace(), request.podName(), e.getMessage());
            // Cannot tell a wrong mapping from an empty search: give no hint.
            return true;
        }
    }

    private List<String> selectorClauses(final String namespace, final String podName, final String container) {
        String nsField = OpenSearchQuerySanitizer.sanitizeFieldName(config.field().namespace());
        String podField = OpenSearchQuerySanitizer.sanitizeFieldName(config.field().pod());

        List<String> clauses = new ArrayList<>();
        clauses.add(String.format("{\"term\":{\"%s.keyword\":\"%s\"}}",
            nsField, OpenSearchQuerySanitizer.sanitizeValue(namespace)));
        clauses.add(String.format("{\"term\":{\"%s.keyword\":\"%s\"}}",
            podField, OpenSearchQuerySanitizer.sanitizeValue(podName)));

        if (container != null && !container.isBlank()) {
            String containerField = OpenSearchQuerySanitizer.sanitizeFieldName(config.field().container());
            clauses.add(String.format("{\"term\":{\"%s.keyword\":\"%s\"}}",
                containerField, OpenSearchQuerySanitizer.sanitizeValue(container.trim())));
        }
        return clauses;
    }

    String extractLogLines(OpenSearchResponse response) {
        if (response == null || response.hits() == null || response.hits().hits() == null) {
            return null;
        }

        String msgField = config.field().message();

        List<OpenSearchResponse.Hit> hits = new ArrayList<>(response.hits().hits());
        Collections.reverse(hits);

        List<String> lines = hits.stream()
            .filter(hit -> hit.source() != null && hit.source().containsKey(msgField))
            .map(hit -> String.valueOf(hit.source().get(msgField)))
            .toList();

        return lines.isEmpty() ? null : String.join("\n", lines) + "\n";
    }

    /**
     * Build the time range filter clause.
     *
     * <ol>
     *   <li>{@code startTime} set: from {@code startTime} to {@code endTime}, or open-ended</li>
     *   <li>{@code sinceSeconds} set: the last {@code sinceSeconds}</li>
     *   <li>a filter or a non-blank keyword: the last {@code mcp.log.opensearch.filter-lookback},
     *       because the {@code errors}/{@code warnings} term patterns start with a wildcard and
     *       OpenSearch checks every term of the message field in each searched index</li>
     *   <li>otherwise no range: the newest {@code tailLines} lines are returned regardless of age</li>
     * </ol>
     *
     * @param tsField        the timestamp field
     * @param sinceSeconds   optional relative window in seconds
     * @param startTime      optional absolute start (ISO 8601)
     * @param endTime        optional absolute end (ISO 8601), used only with {@code startTime}
     * @param hasLineFilters whether the search has a filter or a non-blank keyword
     * @return the range clause, or null for no time restriction
     */
    private String buildTimeRangeClause(String tsField, Integer sinceSeconds,
                                         String startTime, String endTime, boolean hasLineFilters) {
        if (startTime != null && endTime != null) {
            return String.format("{\"range\":{\"%s\":{\"gte\":\"%s\",\"lte\":\"%s\"}}}",
                tsField,
                OpenSearchQuerySanitizer.sanitizeValue(startTime),
                OpenSearchQuerySanitizer.sanitizeValue(endTime));
        }
        if (startTime != null) {
            return String.format("{\"range\":{\"%s\":{\"gte\":\"%s\"}}}",
                tsField, OpenSearchQuerySanitizer.sanitizeValue(startTime));
        }
        if (sinceSeconds != null) {
            Instant since = Instant.now().minusSeconds(sinceSeconds);
            return String.format("{\"range\":{\"%s\":{\"gte\":\"%s\"}}}",
                tsField, since.toString());
        }
        if (hasLineFilters) {
            Instant since = Instant.now().minus(config.filterLookback());
            return String.format("{\"range\":{\"%s\":{\"gte\":\"%s\"}}}",
                tsField, since.toString());
        }
        return null;
    }

    /**
     * Report the bounded window of a filtered search without a time range, so the caller
     * knows older matches were not searched.
     *
     * @param request the fetch request
     * @return the note, or null when the request has a time range or no filter or keywords
     */
    @Override
    public String searchScopeNote(final LogFetchRequest request) {
        if (request.startTime() != null || request.sinceSeconds() != null
            || !LogFilterUtils.hasLineFilters(request.filter(), request.keywords())) {
            return null;
        }
        return String.format("OpenSearch searched only the last %d minutes for lines matching the filter or"
            + " keywords, because no sinceMinutes or startTime/endTime was given. Pass sinceMinutes to search"
            + " further back.", config.filterLookback().toMinutes());
    }

    private String resolveFilterRegex(String filter) {
        if (filter == null || filter.isBlank()) {
            return null;
        }
        String normalized = filter.trim().toLowerCase(Locale.ROOT);
        if ("errors".equals(normalized)) {
            return ERRORS_TERM_REGEXP;
        }
        if ("warnings".equals(normalized)) {
            return WARNINGS_TERM_REGEXP;
        }
        return filter.trim();
    }
}
