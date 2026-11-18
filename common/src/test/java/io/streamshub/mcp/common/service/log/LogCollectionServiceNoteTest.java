/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service.log;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Pod;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.common.dto.LogCollectionParams;
import io.streamshub.mcp.common.dto.LogFetchRequest;
import io.streamshub.mcp.common.dto.PodLogsResult;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests that {@link LogCollectionService} adds the provider's notes to the result warnings:
 * the search scope note ({@link LogCollectorProvider#searchScopeNote(LogFetchRequest)}) and
 * the empty-container hint ({@link LogCollectorProvider#emptyContainerHint(LogFetchRequest)}).
 */
@QuarkusTest
class LogCollectionServiceNoteTest {

    private static final String NOTE = "Provider searched only the last 60 minutes";
    private static final String HINT = "Provider hint: check the container label mapping";

    @InjectMock
    KubernetesLogProvider provider;

    @Inject
    LogCollectionService logCollectionService;

    LogCollectionServiceNoteTest() {
    }

    @Test
    void testProviderNoteAddedOnceToWarnings() {
        when(provider.fetchLogs(ArgumentMatchers.any())).thenReturn(LogLineFixtures.WARN_LINE + "\n");
        when(provider.searchScopeNote(ArgumentMatchers.any())).thenReturn(NOTE);

        PodLogsResult result = logCollectionService.collectLogs("kafka",
            List.of(createPod("pod-1"), createPod("pod-2")), LogCollectionParams.builder(100).filter("warnings").build());

        assertEquals(List.of(NOTE), result.warnings());
        assertEquals(2, result.totalLines());
    }

    @Test
    void testProviderNoteNotInAllPodsFailedError() {
        when(provider.fetchLogs(ArgumentMatchers.any())).thenThrow(new LogQueryException("boom", null));
        when(provider.searchScopeNote(ArgumentMatchers.any())).thenReturn(NOTE);

        LogQueryException ex = assertThrows(LogQueryException.class, () -> logCollectionService.collectLogs("kafka",
            List.of(createPod("pod-1")), LogCollectionParams.builder(100).filter("errors").build()));

        assertTrue(ex.getMessage().contains("boom"), ex.getMessage());
        assertFalse(ex.getMessage().contains(NOTE), ex.getMessage());
    }

    @Test
    void testNoNoteNoWarning() {
        when(provider.fetchLogs(ArgumentMatchers.any())).thenReturn(LogLineFixtures.WARN_LINE + "\n");
        when(provider.searchScopeNote(ArgumentMatchers.any())).thenReturn(null);

        PodLogsResult result = logCollectionService.collectLogs("kafka",
            List.of(createPod("pod-1")), LogCollectionParams.builder(100).build());

        assertTrue(result.warnings().isEmpty(), result.warnings().toString());
    }

    @Test
    void testEmptyContainerHintAskedOnceAndAdded() {
        when(provider.fetchLogs(ArgumentMatchers.any())).thenReturn(null);
        when(provider.emptyContainerHint(ArgumentMatchers.any())).thenReturn(HINT);

        PodLogsResult result = logCollectionService.collectLogs("kafka",
            List.of(createPod("pod-1"), createPod("pod-2")), LogCollectionParams.builder(100).container("kafka").build());

        assertEquals(List.of(HINT), result.warnings());
        verify(provider, times(1)).emptyContainerHint(ArgumentMatchers.any());
    }

    @Test
    void testEmptyContainerHintNotAskedWhenLinesReturned() {
        when(provider.fetchLogs(ArgumentMatchers.any())).thenReturn(LogLineFixtures.WARN_LINE + "\n");
        when(provider.emptyContainerHint(ArgumentMatchers.any())).thenReturn(HINT);

        PodLogsResult result = logCollectionService.collectLogs("kafka",
            List.of(createPod("pod-1")), LogCollectionParams.builder(100).container("kafka").build());

        assertTrue(result.warnings().isEmpty(), result.warnings().toString());
        verify(provider, never()).emptyContainerHint(ArgumentMatchers.any());
    }

    @Test
    void testEmptyContainerHintNotAskedWithoutContainer() {
        when(provider.fetchLogs(ArgumentMatchers.any())).thenReturn(null);
        when(provider.emptyContainerHint(ArgumentMatchers.any())).thenReturn(HINT);

        PodLogsResult result = logCollectionService.collectLogs("kafka",
            List.of(createPod("pod-1")), LogCollectionParams.builder(100).build());

        assertTrue(result.warnings().isEmpty(), result.warnings().toString());
        verify(provider, never()).emptyContainerHint(ArgumentMatchers.any());
    }

    private static Pod createPod(final String name) {
        Pod pod = new Pod();
        ObjectMeta metadata = new ObjectMeta();
        metadata.setName(name);
        metadata.setNamespace("kafka");
        pod.setMetadata(metadata);
        return pod;
    }
}
