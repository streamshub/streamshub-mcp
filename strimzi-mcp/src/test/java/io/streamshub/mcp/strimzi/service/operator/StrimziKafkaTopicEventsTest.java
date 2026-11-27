/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.operator;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.streamshub.mcp.common.dto.ResourceEventsResult;
import io.streamshub.mcp.common.service.KubernetesEventsService;
import io.streamshub.mcp.common.service.KubernetesResourceService;
import io.streamshub.mcp.strimzi.dto.operator.StrimziEventsResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link StrimziEventsService#getKafkaTopicEvents}.
 */
class StrimziKafkaTopicEventsTest {

    private final KubernetesResourceService k8sService = Mockito.mock(KubernetesResourceService.class);
    private final KubernetesEventsService eventsService = Mockito.mock(KubernetesEventsService.class);
    private final StrimziEventsService service = new StrimziEventsService();

    StrimziKafkaTopicEventsTest() {
    }

    @BeforeEach
    void setUp() {
        service.k8sService = k8sService;
        service.eventsService = eventsService;
        service.maxRelatedResources = 50;
        when(eventsService.getEventsForResource(anyString(), anyString(), anyString(), any()))
            .thenAnswer(inv -> new ResourceEventsResult(inv.getArgument(2), inv.getArgument(1), List.of(
                new ResourceEventsResult.EventInfo("Warning", "Reason", "msg", 1, null, null, null))));
    }

    @Test
    void testCoversTheTopicAndTheEntityOperatorOnly() {
        Pod eo = new PodBuilder().withNewMetadata().withName("my-cluster-entity-operator-abc-123")
            .withNamespace("kafka").endMetadata().build();
        when(k8sService.queryResourcesByLabels(eq(Pod.class), eq("kafka"), any())).thenReturn(List.of(eo));

        StrimziEventsResponse response = service.getKafkaTopicEvents("kafka", "orders", "my-cluster", null);

        assertEquals(List.of("KafkaTopic/orders", "Pod/my-cluster-entity-operator-abc-123"),
            response.resources().stream().map(r -> r.resourceKind() + "/" + r.resourceName()).toList());
        verify(k8sService).queryResourcesByLabels(Pod.class, "kafka",
            Map.of("app.kubernetes.io/name", "entity-operator", "strimzi.io/cluster", "my-cluster"));
    }

    @Test
    void testWithoutClusterSkipsTheOperatorPods() {
        StrimziEventsResponse response = service.getKafkaTopicEvents("kafka", "orders", null, null);

        assertEquals(1, response.resources().size());
        verify(k8sService, never()).queryResourcesByLabels(eq(Pod.class), anyString(), any());
        verify(eventsService).getEventsForResource(eq("kafka"), eq("orders"), eq("KafkaTopic"), isNull());
    }
}
