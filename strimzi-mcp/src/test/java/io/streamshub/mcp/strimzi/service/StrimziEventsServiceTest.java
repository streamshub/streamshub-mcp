/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service;

import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.EventBuilder;
import io.fabric8.kubernetes.api.model.EventList;
import io.fabric8.kubernetes.api.model.ObjectReferenceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.V1APIGroupDSL;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.strimzi.dto.StrimziEventsResponse;
import io.strimzi.api.kafka.model.kafka.Kafka;
import io.strimzi.api.kafka.model.kafka.KafkaBuilder;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link StrimziEventsService}.
 */
@QuarkusTest
class StrimziEventsServiceTest {

    @InjectMock
    KubernetesClient kubernetesClient;

    @Inject
    StrimziEventsService eventsService;

    StrimziEventsServiceTest() {
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        // Mock pods (empty by default)
        MixedOperation<Pod, PodList, PodResource> podOp = Mockito.mock(MixedOperation.class);
        Mockito.lenient().when(kubernetesClient.pods()).thenReturn(podOp);

        // Mock events API (empty by default)
        setupEmptyEvents();
    }

    @Test
    void testThrowsWhenClusterNameMissing() {
        assertThrows(ToolCallException.class, () ->
            eventsService.getClusterEvents("kafka", null, null));
    }

    @Test
    void testReturnsEventsForKafkaCR() {
        Kafka kafka = new KafkaBuilder()
            .withNewMetadata().withName("my-cluster").withNamespace("kafka").endMetadata()
            .build();
        setupKafkaCRLookup(kafka);

        Event event = new EventBuilder()
            .withNewMetadata().withName("event-1").withNamespace("kafka").endMetadata()
            .withType("Warning")
            .withReason("ReconciliationException")
            .withMessage("Failed to reconcile Kafka cluster")
            .withCount(3)
            .withFirstTimestamp(Instant.now().minusSeconds(600).toString())
            .withLastTimestamp(Instant.now().toString())
            .withInvolvedObject(new ObjectReferenceBuilder()
                .withName("my-cluster").withKind("Kafka").build())
            .withNewSource().withComponent("strimzi-cluster-operator").endSource()
            .build();

        setupEventsResponse(List.of(event));

        StrimziEventsResponse result = eventsService.getClusterEvents("kafka", "my-cluster", null);

        assertNotNull(result);
        assertEquals("my-cluster", result.clusterName());
        assertEquals("kafka", result.namespace());
        assertEquals(1, result.totalEvents());
        assertEquals(1, result.resources().size());
        assertEquals("Kafka", result.resources().getFirst().resourceKind());
        assertEquals("Warning", result.resources().getFirst().events().getFirst().type());
        assertEquals("ReconciliationException", result.resources().getFirst().events().getFirst().reason());
        assertEquals("strimzi-cluster-operator", result.resources().getFirst().events().getFirst().source());
    }

    @Test
    void testReturnsEmptyWhenNoEvents() {
        Kafka kafka = new KafkaBuilder()
            .withNewMetadata().withName("my-cluster").withNamespace("kafka").endMetadata()
            .build();
        setupKafkaCRLookup(kafka);
        setupEventsResponse(List.of());

        StrimziEventsResponse result = eventsService.getClusterEvents("kafka", "my-cluster", null);

        assertNotNull(result);
        assertEquals(0, result.totalEvents());
        assertTrue(result.resources().isEmpty());
        assertTrue(result.message().contains("No events found"));
    }

    @SuppressWarnings("unchecked")
    private void setupKafkaCRLookup(final Kafka kafka) {
        // KafkaService uses KubernetesResourceService which uses client.resources(Kafka.class)
        // Mock the full chain
        MixedOperation kafkaOp = Mockito.mock(MixedOperation.class);
        when(kubernetesClient.resources(Kafka.class)).thenReturn(kafkaOp);

        NonNamespaceOperation nsKafkaOp = Mockito.mock(NonNamespaceOperation.class);
        when(kafkaOp.inNamespace(kafka.getMetadata().getNamespace())).thenReturn(nsKafkaOp);

        Resource kafkaResource = Mockito.mock(Resource.class);
        when(nsKafkaOp.withName(kafka.getMetadata().getName())).thenReturn(kafkaResource);
        when(kafkaResource.get()).thenReturn(kafka);
    }

    @SuppressWarnings("unchecked")
    private void setupEmptyEvents() {
        V1APIGroupDSL v1 = Mockito.mock(V1APIGroupDSL.class);
        Mockito.lenient().when(kubernetesClient.v1()).thenReturn(v1);

        MixedOperation<Event, EventList, Resource<Event>> eventOp = Mockito.mock(MixedOperation.class);
        Mockito.lenient().when(v1.events()).thenReturn(eventOp);

        NonNamespaceOperation<Event, EventList, Resource<Event>> nsEventOp =
            Mockito.mock(NonNamespaceOperation.class);
        Mockito.lenient().when(eventOp.inNamespace(anyString())).thenReturn(nsEventOp);

        FilterWatchListDeletable<Event, EventList, Resource<Event>> fieldEventOp =
            Mockito.mock(FilterWatchListDeletable.class);
        Mockito.lenient().when(nsEventOp.withField(anyString(), anyString())).thenReturn(fieldEventOp);
        Mockito.lenient().when(fieldEventOp.withField(anyString(), anyString())).thenReturn(fieldEventOp);

        EventList emptyEventList = new EventList();
        emptyEventList.setItems(List.of());
        Mockito.lenient().when(fieldEventOp.list()).thenReturn(emptyEventList);
    }

    @SuppressWarnings("unchecked")
    private void setupEventsResponse(final List<Event> events) {
        V1APIGroupDSL v1 = Mockito.mock(V1APIGroupDSL.class);
        when(kubernetesClient.v1()).thenReturn(v1);

        MixedOperation<Event, EventList, Resource<Event>> eventOp = Mockito.mock(MixedOperation.class);
        when(v1.events()).thenReturn(eventOp);

        NonNamespaceOperation<Event, EventList, Resource<Event>> nsEventOp =
            Mockito.mock(NonNamespaceOperation.class);
        when(eventOp.inNamespace(anyString())).thenReturn(nsEventOp);

        FilterWatchListDeletable<Event, EventList, Resource<Event>> fieldEventOp =
            Mockito.mock(FilterWatchListDeletable.class);
        when(nsEventOp.withField(anyString(), anyString())).thenReturn(fieldEventOp);
        when(fieldEventOp.withField(anyString(), anyString())).thenReturn(fieldEventOp);

        EventList eventList = new EventList();
        eventList.setItems(events);
        when(fieldEventOp.list()).thenReturn(eventList);
    }
}
