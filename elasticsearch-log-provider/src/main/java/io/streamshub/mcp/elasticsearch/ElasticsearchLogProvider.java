/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.elasticsearch;

import io.quarkus.arc.lookup.LookupIfProperty;
import io.streamshub.mcp.common.service.log.LogCollectorProvider;
import io.streamshub.mcp.common.service.log.LogQueryException;
import io.streamshub.mcp.common.util.ExceptionUtils;
import io.streamshub.mcp.elasticsearch.config.ElasticsearchConfig;
import io.streamshub.mcp.elasticsearch.service.ElasticsearchClient;
import io.streamshub.mcp.elasticsearch.util.ElasticsearchQuerySanitizer;
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
 * Elasticsearch-based implementation of {@link LogCollectorProvider}.
 *
 * <p>Activated when {@code mcp.log.provider=streamshub-elasticsearch}.
 * Queries Elasticsearch for logs matching Kubernetes namespace and pod name,
 * supports time-based filtering, regex patterns, and keyword search.</p>
 */
@ApplicationScoped
@LookupIfProperty(name = "mcp.log.provider", stringValue = "streamshub-elasticsearch")
public class ElasticsearchLogProvider implements LogCollectorProvider {

    private static final Logger LOG = Logger.getLogger(ElasticsearchLogProvider.class);
    private static final int DEFAULT_SINCE_SECONDS = 3600;

    @Inject
    ElasticsearchConfig config;

    @Inject
    @RestClient
    Instance<ElasticsearchClient> elasticsearchClient;

    ElasticsearchLogProvider() {
    }

    @Override
    public String fetchLogs(final String namespace, final String podName,
                            final int tailLines, final Integer sinceSeconds,
                            final Boolean previous, final String filter,
                            final List<String> keywords,
                            final String startTime, final String endTime) {
        if (Boolean.TRUE.equals(previous)) {
            LOG.debugf("Elasticsearch does not distinguish previous container logs; "
                + "using time range to capture pre-restart logs for pod %s", podName);
        }

        String query = buildSearchQuery(namespace, podName, tailLines,
            sinceSeconds, filter, keywords, startTime, endTime);

        LOG.debugf("Querying Elasticsearch: index=%s, query=%s", config.indexPattern(), query);

        try {
            ElasticsearchResponse response = elasticsearchClient.get()
                .search(config.indexPattern(), query);
            return extractLogLines(response);
        } catch (Exception e) {
            LOG.warnf("Failed to query Elasticsearch for pod %s/%s: %s",
                namespace, podName, e.getMessage());
            throw new LogQueryException(
                String.format("Elasticsearch query failed for pod %s/%s: %s",
                    namespace, podName, ExceptionUtils.rootCauseMessage(e)), e);
        }
    }

    String buildSearchQuery(String namespace, String podName,
                            int tailLines, Integer sinceSeconds,
                            String filter, List<String> keywords,
                            String startTime, String endTime) {
        String nsField = ElasticsearchQuerySanitizer.sanitizeFieldName(config.field().namespace());
        String podField = ElasticsearchQuerySanitizer.sanitizeFieldName(config.field().pod());
        String msgField = ElasticsearchQuerySanitizer.sanitizeFieldName(config.field().message());
        String tsField = ElasticsearchQuerySanitizer.sanitizeFieldName(config.field().timestamp());

        List<String> filterClauses = new ArrayList<>();

        filterClauses.add(String.format("{\"term\":{\"%s.keyword\":\"%s\"}}",
            nsField, ElasticsearchQuerySanitizer.sanitizeValue(namespace)));
        filterClauses.add(String.format("{\"term\":{\"%s.keyword\":\"%s\"}}",
            podField, ElasticsearchQuerySanitizer.sanitizeValue(podName)));

        String timeRangeClause = buildTimeRangeClause(tsField, sinceSeconds, startTime, endTime);
        if (timeRangeClause != null) {
            filterClauses.add(timeRangeClause);
        }

        String filterRegex = resolveFilterRegex(filter);
        if (filterRegex != null) {
            filterClauses.add(String.format("{\"regexp\":{\"%s\":{\"value\":\"%s\",\"case_insensitive\":true}}}",
                msgField, ElasticsearchQuerySanitizer.sanitizeValue(filterRegex)));
        }

        String shouldClause = "";
        if (keywords != null && !keywords.isEmpty()) {
            String keywordClauses = keywords.stream()
                .filter(k -> k != null && !k.isBlank())
                .map(k -> String.format("{\"match_phrase\":{\"%s\":\"%s\"}}",
                    msgField, ElasticsearchQuerySanitizer.sanitizeValue(k.trim())))
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

    String extractLogLines(ElasticsearchResponse response) {
        if (response == null || response.hits() == null || response.hits().hits() == null) {
            return null;
        }

        String msgField = config.field().message();

        List<ElasticsearchResponse.Hit> hits = new ArrayList<>(response.hits().hits());
        Collections.reverse(hits);

        List<String> lines = hits.stream()
            .filter(hit -> hit.source() != null && hit.source().containsKey(msgField))
            .map(hit -> String.valueOf(hit.source().get(msgField)))
            .toList();

        return lines.isEmpty() ? null : String.join("\n", lines) + "\n";
    }

    private String buildTimeRangeClause(String tsField, Integer sinceSeconds,
                                         String startTime, String endTime) {
        if (startTime != null && endTime != null) {
            return String.format("{\"range\":{\"%s\":{\"gte\":\"%s\",\"lte\":\"%s\"}}}",
                tsField,
                ElasticsearchQuerySanitizer.sanitizeValue(startTime),
                ElasticsearchQuerySanitizer.sanitizeValue(endTime));
        }
        int effectiveSince = sinceSeconds != null ? sinceSeconds : DEFAULT_SINCE_SECONDS;
        Instant since = Instant.now().minusSeconds(effectiveSince);
        return String.format("{\"range\":{\"%s\":{\"gte\":\"%s\"}}}",
            tsField, since.toString());
    }

    private String resolveFilterRegex(String filter) {
        if (filter == null || filter.isBlank()) {
            return null;
        }
        String normalized = filter.trim().toLowerCase(Locale.ROOT);
        if ("errors".equals(normalized)) {
            return "(ERROR|EXCEPTION)";
        }
        if ("warnings".equals(normalized)) {
            return "(ERROR|EXCEPTION|WARN)";
        }
        return filter.trim();
    }
}
