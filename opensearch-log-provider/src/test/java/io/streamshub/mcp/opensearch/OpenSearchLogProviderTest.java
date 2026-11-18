/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.opensearch;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class OpenSearchLogProviderTest {

    @Inject
    OpenSearchLogProvider provider;

    OpenSearchLogProviderTest() {
    }

    @Test
    void testBuildSearchQueryBasic() {
        String query = provider.buildSearchQuery(
            "kafka-prod", "my-cluster-kafka-0", 200, null, null, null, null, null, null);
        assertTrue(query.contains("\"kubernetes.namespace_name.keyword\""));
        assertTrue(query.contains("\"kafka-prod\""));
        assertTrue(query.contains("\"kubernetes.pod_name.keyword\""));
        assertTrue(query.contains("\"my-cluster-kafka-0\""));
        assertTrue(query.contains("\"size\":201"));
    }

    @Test
    void testBuildSearchQueryWithErrorsFilter() {
        String query = provider.buildSearchQuery(
            "kafka", "pod-0", 200, null, "errors", null, null, null, null);
        // Matched per analyzed term: fatal, or a term ending in error/exception (TimeoutException,
        // java.lang.OutOfMemoryError), but not a config key such as errors.tolerance.
        assertTrue(query.contains(
            "{\"regexp\":{\"message\":{\"value\":\"fatal|.*(error|exception)\",\"case_insensitive\":true}}}"),
            query);
    }

    @Test
    void testBuildSearchQueryWithWarningsFilter() {
        String query = provider.buildSearchQuery(
            "kafka", "pod-0", 200, null, " Warnings ", null, null, null, null);
        assertTrue(query.contains("{\"regexp\":{\"message\":{\"value\":\"fatal|warn|warning|.*(error|exception)\","
            + "\"case_insensitive\":true}}}"), query);
    }

    @Test
    void testShortcutTermPatternsMatchTheSharedRuleOnAnalyzedTerms() {
        // Terms as the standard analyzer produces them: lowercased, dotted names kept whole.
        // Lucene regexp is anchored to the whole term, like Matcher.matches().
        Pattern errors = Pattern.compile(OpenSearchLogProvider.ERRORS_TERM_REGEXP);
        Pattern warnings = Pattern.compile(OpenSearchLogProvider.WARNINGS_TERM_REGEXP);
        for (String term : List.of("error", "fatal", "org.apache.kafka.common.errors.timeoutexception",
            "java.lang.outofmemoryerror", "java.net.connectexception")) {
            assertTrue(errors.matcher(term).matches(), term);
            assertTrue(warnings.matcher(term).matches(), term);
        }
        for (String term : List.of("warn", "warning")) {
            assertFalse(errors.matcher(term).matches(), term);
            assertTrue(warnings.matcher(term).matches(), term);
        }
        for (String term : List.of("errors.tolerance", "errors.retry.timeout", "info", "errorhandler.java",
            "org.apache.kafka.clients.admin.kafkaadminclient", "warned")) {
            assertFalse(errors.matcher(term).matches(), term);
            assertFalse(warnings.matcher(term).matches(), term);
        }
    }

    @Test
    void testBuildContainerProbeQueryHasOnlyTheSelector() {
        assertEquals("{\"query\":{\"bool\":{\"filter\":["
                + "{\"term\":{\"kubernetes.namespace_name.keyword\":\"kafka\"}},"
                + "{\"term\":{\"kubernetes.pod_name.keyword\":\"pod-0\"}},"
                + "{\"term\":{\"kubernetes.container_name.keyword\":\"kafka\"}}]}},"
                + "\"size\":1,\"_source\":false,\"terminate_after\":1}",
            provider.buildContainerProbeQuery("kafka", "pod-0", " kafka "));
    }

    @Test
    void testBuildSearchQueryWithCustomRegex() {
        String query = provider.buildSearchQuery(
            "kafka", "pod-0", 200, null, "OOM|timeout", null, null, null, null);
        assertTrue(query.contains("regexp"));
        assertTrue(query.contains("OOM|timeout"));
    }

    @Test
    void testBuildSearchQueryWithKeywords() {
        String query = provider.buildSearchQuery(
            "kafka", "pod-0", 200, null, null, List.of("OOM", "timeout"), null, null, null);
        assertTrue(query.contains("should"));
        assertTrue(query.contains("OOM"));
        assertTrue(query.contains("timeout"));
        assertTrue(query.contains("minimum_should_match"));
    }

    @Test
    void testBuildSearchQueryWithSinceSeconds() {
        String query = provider.buildSearchQuery(
            "kafka", "pod-0", 200, 3600, null, null, null, null, null);
        assertTrue(query.contains("range"));
        assertTrue(query.contains("gte"));
        assertTrue(query.contains("@timestamp"));
    }

    @Test
    void testBuildSearchQueryWithAbsoluteTimeRange() {
        String query = provider.buildSearchQuery(
            "kafka", "pod-0", 200, null, null, null,
            "2026-01-01T00:00:00Z", "2026-01-02T00:00:00Z", null);
        assertTrue(query.contains("range"));
        assertTrue(query.contains("2026-01-01T00:00:00Z"));
        assertTrue(query.contains("2026-01-02T00:00:00Z"));
    }

    @Test
    void testBuildSearchQueryEscapesSpecialCharacters() {
        String query = provider.buildSearchQuery(
            "ns\"}]}}", "pod-0", 200, null, null, null, null, null, null);
        assertTrue(query.contains("ns\\\"}]}}"));
    }

    @Test
    void testBuildSearchQueryWithoutTimeRangeHasNoRangeClause() {
        String query = provider.buildSearchQuery(
            "kafka", "pod-0", 200, null, null, null, null, null, null);
        assertFalse(query.contains("range"), query);
    }

    @Test
    void testBuildSearchQueryWithStartTimeOnlyUsesIt() {
        String query = provider.buildSearchQuery(
            "kafka", "pod-0", 200, null, null, null,
            "2026-01-01T00:00:00Z", null, null);
        assertTrue(query.contains("\"gte\":\"2026-01-01T00:00:00Z\""), query);
        assertFalse(query.contains("\"lte\""), query);
    }

    @Test
    void testExtractLogLinesFromResponse() {
        OpenSearchResponse response = new OpenSearchResponse(
            new OpenSearchResponse.HitsWrapper(
                new OpenSearchResponse.TotalHits(3, "eq"),
                List.of(
                    new OpenSearchResponse.Hit(Map.of(
                        "message", "third line",
                        "@timestamp", "2026-01-01T00:00:03Z"), null),
                    new OpenSearchResponse.Hit(Map.of(
                        "message", "second line",
                        "@timestamp", "2026-01-01T00:00:02Z"), null),
                    new OpenSearchResponse.Hit(Map.of(
                        "message", "first line",
                        "@timestamp", "2026-01-01T00:00:01Z"), null)
                )
            )
        );

        String result = provider.extractLogLines(response);
        assertNotNull(result);
        assertEquals("first line\nsecond line\nthird line\n", result);
    }

    @Test
    void testExtractLogLinesReturnsNullForEmptyHits() {
        OpenSearchResponse response = new OpenSearchResponse(
            new OpenSearchResponse.HitsWrapper(
                new OpenSearchResponse.TotalHits(0, "eq"),
                List.of()
            )
        );
        assertNull(provider.extractLogLines(response));
    }

    @Test
    void testExtractLogLinesReturnsNullForNullResponse() {
        assertNull(provider.extractLogLines(null));
    }

    @Test
    void testExtractLogLinesReturnsNullForNullHits() {
        assertNull(provider.extractLogLines(new OpenSearchResponse(null)));
    }

    @Test
    void testExtractLogLinesSkipsEntriesWithoutMessage() {
        OpenSearchResponse response = new OpenSearchResponse(
            new OpenSearchResponse.HitsWrapper(
                new OpenSearchResponse.TotalHits(2, "eq"),
                List.of(
                    new OpenSearchResponse.Hit(Map.of(
                        "message", "valid line",
                        "@timestamp", "2026-01-01T00:00:01Z"), null),
                    new OpenSearchResponse.Hit(Map.of(
                        "@timestamp", "2026-01-01T00:00:02Z"), null)
                )
            )
        );

        String result = provider.extractLogLines(response);
        assertEquals("valid line\n", result);
    }

    @Test
    void testBuildSearchQueryWithContainer() {
        String query = provider.buildSearchQuery(
            "kafka-prod", "my-cluster-kafka-0", 200, null, null, null, null, null, "kafka");
        assertTrue(query.contains("\"kubernetes.container_name.keyword\""));
        assertTrue(query.contains("\"kafka\""));
    }

    @Test
    void testBuildSearchQueryBlankContainerOmitsTerm() {
        String query = provider.buildSearchQuery(
            "kafka-prod", "my-cluster-kafka-0", 200, null, null, null, null, null, "  ");
        assertFalse(query.contains("container_name"));
    }
}
