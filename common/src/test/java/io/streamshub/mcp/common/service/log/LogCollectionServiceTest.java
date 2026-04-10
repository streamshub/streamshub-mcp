/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service.log;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.common.dto.LogCollectionParams;
import io.streamshub.mcp.common.dto.PodLogsResult;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link LogCollectionService} log orchestration,
 * callbacks, and cancellation.
 */
@QuarkusTest
class LogCollectionServiceTest {

    @InjectMock
    KubernetesClient kubernetesClient;

    @Inject
    LogCollectionService logCollectionService;

    LogCollectionServiceTest() {
    }

    @Test
    void testCollectLogsCancellationStopsProcessing() {
        Pod pod = createSimplePod("pod-1");

        Runnable cancelCheck = () -> {
            throw new RuntimeException("Cancelled");
        };

        LogCollectionParams options = LogCollectionParams.builder(100)
            .cancelCheck(cancelCheck)
            .build();

        assertThrows(RuntimeException.class, () ->
            logCollectionService.collectLogs("kafka", List.of(pod), options));
    }

    @Test
    void testCollectLogsProgressCallbackCalledPerPod() {
        Pod pod1 = createSimplePod("pod-1");
        Pod pod2 = createSimplePod("pod-2");
        Pod pod3 = createSimplePod("pod-3");

        List<int[]> progressCalls = new ArrayList<>();

        LogCollectionParams options = LogCollectionParams.builder(100)
            .progressCallback((completed, total) -> progressCalls.add(new int[]{completed, total}))
            .build();

        logCollectionService.collectLogs("kafka", List.of(pod1, pod2, pod3), options);

        assertEquals(3, progressCalls.size());
        assertEquals(1, progressCalls.get(0)[0]);
        assertEquals(3, progressCalls.get(0)[1]);
        assertEquals(3, progressCalls.get(2)[0]);
        assertEquals(3, progressCalls.get(2)[1]);
    }

    @Test
    void testCollectLogsNotifierCalledPerPod() {
        Pod pod = createSimplePod("test-pod");

        List<String> notifications = new ArrayList<>();

        LogCollectionParams options = LogCollectionParams.builder(100)
            .notifier(notifications::add)
            .build();

        logCollectionService.collectLogs("kafka", List.of(pod), options);

        assertEquals(1, notifications.size());
        assertTrue(notifications.getFirst().contains("test-pod (1/1)"));
    }

    @Test
    void testCollectLogsEmptyPodList() {
        LogCollectionParams options = LogCollectionParams.of(null, null, 100, null);
        PodLogsResult result = logCollectionService.collectLogs("kafka", List.of(), options);

        assertNotNull(result);
        assertTrue(result.podNames().isEmpty());
        assertEquals(0, result.totalLines());
    }

    private Pod createSimplePod(final String name) {
        Pod pod = new Pod();
        ObjectMeta metadata = new ObjectMeta();
        metadata.setName(name);
        metadata.setNamespace("kafka");
        pod.setMetadata(metadata);
        return pod;
    }
}
