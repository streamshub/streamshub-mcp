/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.loki;

import io.streamshub.mcp.common.dto.LogFetchRequest;
import io.streamshub.mcp.loki.config.LokiConfig;
import io.streamshub.mcp.loki.service.LokiClient;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link LokiLogProvider#emptyContainerHint(LogFetchRequest)}: the hint for a
 * {@code container} filter that returned no lines, and the max-lookback probe that keeps it off
 * healthy filtered searches and short windows of a quiet container.
 */
class LokiLogProviderContainerHintTest {

    private static final String SELECTOR_ONLY = "{namespace=\"kafka\", pod=\"pod-0\", container=\"kafka\"}";
    private static final Duration MAX_LOOKBACK = Duration.ofHours(720);

    private LokiLogProvider provider;
    private LokiClient client;

    LokiLogProviderContainerHintTest() {
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        LokiConfig config = Mockito.mock(LokiConfig.class);
        LokiConfig.LabelConfig labels = Mockito.mock(LokiConfig.LabelConfig.class);
        when(config.label()).thenReturn(labels);
        when(labels.namespace()).thenReturn("namespace");
        when(labels.pod()).thenReturn("pod");
        when(labels.container()).thenReturn("container");
        when(config.unwrapJson()).thenReturn(true);
        when(config.jsonMessageField()).thenReturn("message");
        when(config.maxLookback()).thenReturn(MAX_LOOKBACK);
        when(config.filterLookback()).thenReturn(Duration.ofHours(1));

        client = Mockito.mock(LokiClient.class);
        Instance<LokiClient> clientInstance = Mockito.mock(Instance.class);
        when(clientInstance.get()).thenReturn(client);

        provider = new LokiLogProvider();
        provider.config = config;
        provider.lokiClient = clientInstance;
    }

    @Test
    void testUnfilteredEmptyContainerWithoutTimeRangeGivesHintWithoutProbe() {
        // The request itself already searched the whole max-lookback window without line filters.
        String hint = provider.emptyContainerHint(request("kafka", null, null));

        assertEquals(String.format(LokiLogProvider.CONTAINER_NO_LINES_WARNING, "kafka", "container"), hint);
        verify(client, never()).queryRange(anyString(), anyLong(), anyLong(), anyInt(), anyString());
    }

    @Test
    void testFilteredEmptyContainerWithLinesInProbeGivesNoHint() {
        when(client.queryRange(anyString(), anyLong(), anyLong(), anyInt(), anyString()))
            .thenReturn(response(List.of(List.of("1000", "2026-11-13 11:20:00,000 INFO healthy line"))));

        assertNull(provider.emptyContainerHint(request("kafka", "errors", null)));
        // One probe: the same selector without line filters or the unwrap stage, limit 1, over max-lookback.
        assertProbedMaxLookback();
    }

    @Test
    void testShortWindowEmptyContainerWithLinesInProbeGivesNoHint() {
        // A correctly mapped container that logged nothing in the last 5 minutes but has older lines.
        when(client.queryRange(anyString(), anyLong(), anyLong(), anyInt(), anyString()))
            .thenReturn(response(List.of(List.of("1000", "2026-11-13 11:20:00,000 INFO healthy line"))));

        assertNull(provider.emptyContainerHint(request("kafka", null, null, 300)));
        assertProbedMaxLookback();
    }

    @Test
    void testShortWindowEmptyContainerWithEmptyProbeGivesHint() {
        when(client.queryRange(anyString(), anyLong(), anyLong(), anyInt(), anyString()))
            .thenReturn(response(List.of()));

        String hint = provider.emptyContainerHint(request("kafka", null, null, 300));

        assertEquals(String.format(LokiLogProvider.CONTAINER_NO_LINES_WARNING, "kafka", "container"), hint);
        assertProbedMaxLookback();
    }

    @Test
    void testKeywordsEmptyContainerWithEmptyProbeGivesHint() {
        when(client.queryRange(anyString(), anyLong(), anyLong(), anyInt(), anyString()))
            .thenReturn(response(List.of()));

        String hint = provider.emptyContainerHint(request("kafka", null, List.of("OOM")));

        assertEquals(String.format(LokiLogProvider.CONTAINER_NO_LINES_WARNING, "kafka", "container"), hint);
        assertProbedMaxLookback();
    }

    @Test
    void testProbeFailureGivesNoHint() {
        when(client.queryRange(anyString(), anyLong(), anyLong(), anyInt(), anyString()))
            .thenThrow(new IllegalStateException("connection refused"));

        assertNull(provider.emptyContainerHint(request("kafka", "errors", null)));
    }

    @Test
    void testNoContainerGivesNoHint() {
        assertNull(provider.emptyContainerHint(request(null, null, null)));
        assertNull(provider.emptyContainerHint(request("  ", null, null)));
        verify(client, never()).queryRange(ArgumentMatchers.anyString(), anyLong(), anyLong(), anyInt(), anyString());
    }

    private void assertProbedMaxLookback() {
        ArgumentCaptor<Long> start = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> end = ArgumentCaptor.forClass(Long.class);
        verify(client).queryRange(eq(SELECTOR_ONLY), start.capture(), end.capture(), eq(1), eq("backward"));
        assertEquals(MAX_LOOKBACK.toNanos(), end.getValue() - start.getValue());
    }

    private static LogFetchRequest request(final String container, final String filter,
                                           final List<String> keywords) {
        return request(container, filter, keywords, null);
    }

    private static LogFetchRequest request(final String container, final String filter,
                                           final List<String> keywords, final Integer sinceSeconds) {
        return new LogFetchRequest("kafka", "pod-0", 100, sinceSeconds, null, filter, keywords, null, null,
            container);
    }

    private static LokiResponse response(final List<List<String>> values) {
        return new LokiResponse("success", new LokiResponse.LokiData("streams",
            values.isEmpty() ? List.of() : List.of(new LokiResponse.LokiStream(Map.of(), values))));
    }
}
