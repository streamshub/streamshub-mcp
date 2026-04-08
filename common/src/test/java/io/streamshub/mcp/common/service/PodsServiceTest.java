/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodCondition;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.PodStatus;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.common.dto.PodSummaryResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link PodsService} pod summary extraction and input validation.
 */
@QuarkusTest
class PodsServiceTest {

    @InjectMock
    KubernetesClient kubernetesClient;

    @Inject
    PodsService podsService;

    PodsServiceTest() {
    }

    @Test
    void testExtractPodSummaryRunningPod() {
        Pod pod = createPod("my-pod", "Running", true, 0);

        PodSummaryResponse.PodInfo info = podsService.extractPodSummary("kafka", pod);

        assertNotNull(info);
        assertEquals("my-pod", info.name());
        assertEquals("Running", info.phase());
        assertTrue(info.ready());
        assertEquals(0, info.restarts());
    }

    @Test
    void testExtractPodSummaryNotReadyPod() {
        Pod pod = createPod("failing-pod", "Running", false, 5);

        PodSummaryResponse.PodInfo info = podsService.extractPodSummary("kafka", pod);

        assertNotNull(info);
        assertEquals("failing-pod", info.name());
        assertFalse(info.ready());
        assertEquals(5, info.restarts());
    }

    @Test
    void testExtractPodSummaryPendingPod() {
        Pod pod = createPod("pending-pod", "Pending", false, 0);

        PodSummaryResponse.PodInfo info = podsService.extractPodSummary("kafka", pod);

        assertNotNull(info);
        assertEquals("Pending", info.phase());
        assertFalse(info.ready());
    }

    @Test
    void testExtractPodSummaryNullStatus() {
        Pod pod = createPodWithoutStatus("no-status-pod");

        PodSummaryResponse.PodInfo info = podsService.extractPodSummary("kafka", pod);

        assertNotNull(info);
        assertEquals("no-status-pod", info.name());
        assertFalse(info.ready());
    }

    @Test
    void testDescribePodNullNamespaceThrows() {
        assertThrows(IllegalArgumentException.class, () ->
            podsService.describePod(null, "my-pod"));
    }

    @Test
    void testDescribePodBlankNameThrows() {
        assertThrows(IllegalArgumentException.class, () ->
            podsService.describePod("kafka", "  "));
    }

    private Pod createPod(final String name, final String phase,
                          final boolean ready, final int restarts) {
        Pod pod = new Pod();

        ObjectMeta metadata = new ObjectMeta();
        metadata.setName(name);
        metadata.setNamespace("kafka");
        metadata.setLabels(Map.of());
        metadata.setCreationTimestamp(Instant.now().minus(60, ChronoUnit.MINUTES).toString());
        pod.setMetadata(metadata);

        PodStatus status = new PodStatus();
        status.setPhase(phase);
        status.setStartTime(Instant.now().minus(60, ChronoUnit.MINUTES).toString());

        PodCondition condition = new PodCondition();
        condition.setType("Ready");
        condition.setStatus(ready ? "True" : "False");
        status.setConditions(List.of(condition));

        ContainerStatus containerStatus = new ContainerStatus();
        containerStatus.setName("main");
        containerStatus.setRestartCount(restarts);
        containerStatus.setReady(ready);
        status.setContainerStatuses(List.of(containerStatus));

        pod.setStatus(status);

        Container container = new Container();
        container.setName("main");
        container.setResources(new ResourceRequirements());
        PodSpec spec = new PodSpec();
        spec.setContainers(List.of(container));
        pod.setSpec(spec);

        return pod;
    }

    private Pod createPodWithoutStatus(final String name) {
        Pod pod = new Pod();

        ObjectMeta metadata = new ObjectMeta();
        metadata.setName(name);
        metadata.setNamespace("kafka");
        metadata.setLabels(Map.of());
        metadata.setCreationTimestamp(Instant.now().toString());
        pod.setMetadata(metadata);

        Container container = new Container();
        container.setName("main");
        container.setResources(new ResourceRequirements());
        PodSpec spec = new PodSpec();
        spec.setContainers(List.of(container));
        pod.setSpec(spec);

        return pod;
    }
}
