/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.opensearch;

import io.streamshub.mcp.common.dto.LogFetchRequest;
import io.streamshub.mcp.opensearch.config.OpenSearchConfig;
import io.streamshub.mcp.opensearch.service.OpenSearchClient;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link OpenSearchLogProvider#emptyContainerHint(LogFetchRequest)}: the hint for a
 * {@code container} filter that returned no lines, and the probe that keeps it off a time window
 * or filter that matched nothing.
 */
class OpenSearchLogProviderContainerHintTest {

    private static final String INDEX = "kubernetes-*";
    private static final String PROBE = "{\"query\":{\"bool\":{\"filter\":["
        + "{\"term\":{\"kubernetes.namespace_name.keyword\":\"kafka\"}},"
        + "{\"term\":{\"kubernetes.pod_name.keyword\":\"pod-0\"}},"
        + "{\"term\":{\"kubernetes.container_name.keyword\":\"kafka\"}}]}},"
        + "\"size\":1,\"_source\":false,\"terminate_after\":1}";
    private static final String HINT =
        String.format(OpenSearchLogProvider.CONTAINER_NO_LINES_WARNING, "kafka", "kubernetes.container_name");

    private OpenSearchLogProvider provider;
    private OpenSearchClient client;

    OpenSearchLogProviderContainerHintTest() {
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        OpenSearchConfig config = Mockito.mock(OpenSearchConfig.class);
        OpenSearchConfig.FieldConfig fields = Mockito.mock(OpenSearchConfig.FieldConfig.class);
        when(config.field()).thenReturn(fields);
        when(config.indexPattern()).thenReturn(INDEX);
        when(fields.namespace()).thenReturn("kubernetes.namespace_name");
        when(fields.pod()).thenReturn("kubernetes.pod_name");
        when(fields.container()).thenReturn("kubernetes.container_name");
        when(fields.message()).thenReturn("message");
        when(fields.timestamp()).thenReturn("@timestamp");

        client = Mockito.mock(OpenSearchClient.class);
        Instance<OpenSearchClient> clientInstance = Mockito.mock(Instance.class);
        when(clientInstance.get()).thenReturn(client);

        provider = new OpenSearchLogProvider();
        provider.config = config;
        provider.opensearchClient = clientInstance;
    }

    @Test
    void testEmptyProbeGivesHint() {
        when(client.search(anyString(), anyString())).thenReturn(response(List.of()));

        assertEquals(HINT, provider.emptyContainerHint(request("kafka", null, null)));
        verify(client).search(eq(INDEX), eq(PROBE));
    }

    @Test
    void testFilteredShortWindowWithDocumentsInProbeGivesNoHint() {
        // A correctly mapped container whose last 5 minutes hold no error: the probe has no time
        // range and no text filter, so it finds the container's older documents.
        when(client.search(anyString(), anyString())).thenReturn(response(List.of(
            new OpenSearchResponse.Hit(null, null))));

        assertNull(provider.emptyContainerHint(request("kafka", "errors", 300)));
        verify(client).search(eq(INDEX), eq(PROBE));
    }

    @Test
    void testProbeWithoutHitsListGivesHint() {
        when(client.search(anyString(), anyString())).thenReturn(new OpenSearchResponse(null));

        assertEquals(HINT, provider.emptyContainerHint(request("kafka", null, null)));
    }

    @Test
    void testProbeFailureGivesNoHint() {
        when(client.search(anyString(), anyString())).thenThrow(new IllegalStateException("connection refused"));

        assertNull(provider.emptyContainerHint(request("kafka", "errors", null)));
    }

    @Test
    void testNoContainerGivesNoHint() {
        assertNull(provider.emptyContainerHint(request(null, null, null)));
        assertNull(provider.emptyContainerHint(request("  ", null, null)));
        verify(client, never()).search(anyString(), anyString());
    }

    private static LogFetchRequest request(final String container, final String filter, final Integer sinceSeconds) {
        return new LogFetchRequest("kafka", "pod-0", 100, sinceSeconds, null, filter, null, null, null, container);
    }

    private static OpenSearchResponse response(final List<OpenSearchResponse.Hit> hits) {
        return new OpenSearchResponse(new OpenSearchResponse.HitsWrapper(
            new OpenSearchResponse.TotalHits(hits.size(), "eq"), hits));
    }
}
