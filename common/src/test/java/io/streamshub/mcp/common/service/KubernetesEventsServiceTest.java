/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service;

import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.EventBuilder;
import io.fabric8.kubernetes.api.model.EventList;
import io.fabric8.kubernetes.api.model.ObjectReferenceBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.NonNamespaceOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.V1APIGroupDSL;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.common.dto.ResourceEventsResult;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link KubernetesEventsService}.
 */
@QuarkusTest
class KubernetesEventsServiceTest {

    @InjectMock
    KubernetesClient kubernetesClient;

    @Inject
    KubernetesEventsService eventsService;

    KubernetesEventsServiceTest() {
    }

    @Test
    void testReturnsEventsForResource() {
        Instant now = Instant.now();
        Event event = new EventBuilder()
            .withNewMetadata().withName("event-1").withNamespace("kafka").endMetadata()
            .withType("Warning")
            .withReason("FailedScheduling")
            .withMessage("0/3 nodes are available")
            .withCount(2)
            .withFirstTimestamp(now.minusSeconds(300).toString())
            .withLastTimestamp(now.toString())
            .withInvolvedObject(new ObjectReferenceBuilder()
                .withName("my-pod").withKind("Pod").build())
            .withNewSource().withComponent("default-scheduler").endSource()
            .build();

        setupEventsResponse("kafka", List.of(event));

        ResourceEventsResult result = eventsService.getEventsForResource(
            "kafka", "my-pod", "Pod", null);

        assertNotNull(result);
        assertEquals("Pod", result.resourceKind());
        assertEquals("my-pod", result.resourceName());
        assertEquals(1, result.events().size());

        ResourceEventsResult.EventInfo info = result.events().getFirst();
        assertEquals("Warning", info.type());
        assertEquals("FailedScheduling", info.reason());
        assertEquals("0/3 nodes are available", info.message());
        assertEquals(2, info.count());
        assertEquals("default-scheduler", info.source());
    }

    @Test
    void testReturnsEmptyWhenNoEvents() {
        setupEventsResponse("kafka", List.of());

        ResourceEventsResult result = eventsService.getEventsForResource(
            "kafka", "my-pod", "Pod", null);

        assertNotNull(result);
        assertEquals("Pod", result.resourceKind());
        assertTrue(result.events().isEmpty());
    }

    @Test
    void testFiltersBySinceTime() {
        Instant now = Instant.now();
        Event recentEvent = new EventBuilder()
            .withNewMetadata().withName("recent").withNamespace("kafka").endMetadata()
            .withType("Normal")
            .withReason("Scheduled")
            .withMessage("Successfully assigned")
            .withLastTimestamp(now.minusSeconds(60).toString())
            .withNewSource().withComponent("default-scheduler").endSource()
            .build();

        Event oldEvent = new EventBuilder()
            .withNewMetadata().withName("old").withNamespace("kafka").endMetadata()
            .withType("Warning")
            .withReason("FailedMount")
            .withMessage("Unable to mount volume")
            .withLastTimestamp(now.minusSeconds(7200).toString())
            .withNewSource().withComponent("kubelet").endSource()
            .build();

        setupEventsResponse("kafka", List.of(recentEvent, oldEvent));

        Instant sinceTime = now.minusSeconds(600);
        ResourceEventsResult result = eventsService.getEventsForResource(
            "kafka", "my-pod", "Pod", sinceTime);

        assertEquals(1, result.events().size());
        assertEquals("Scheduled", result.events().getFirst().reason());
    }

    @Test
    void testSortsByLastTimestampDescending() {
        Instant now = Instant.now();
        Event older = new EventBuilder()
            .withNewMetadata().withName("older").withNamespace("kafka").endMetadata()
            .withType("Normal")
            .withReason("Pulled")
            .withMessage("Pulled image")
            .withLastTimestamp(now.minusSeconds(300).toString())
            .build();

        Event newer = new EventBuilder()
            .withNewMetadata().withName("newer").withNamespace("kafka").endMetadata()
            .withType("Normal")
            .withReason("Started")
            .withMessage("Started container")
            .withLastTimestamp(now.minusSeconds(60).toString())
            .build();

        setupEventsResponse("kafka", List.of(older, newer));

        ResourceEventsResult result = eventsService.getEventsForResource(
            "kafka", "my-pod", "Pod", null);

        assertEquals(2, result.events().size());
        assertEquals("Started", result.events().get(0).reason());
        assertEquals("Pulled", result.events().get(1).reason());
    }

    @Test
    void testHandlesNullTimestamps() {
        Event event = new EventBuilder()
            .withNewMetadata().withName("no-time").withNamespace("kafka").endMetadata()
            .withType("Warning")
            .withReason("Unknown")
            .withMessage("Something happened")
            .build();

        setupEventsResponse("kafka", List.of(event));

        ResourceEventsResult result = eventsService.getEventsForResource(
            "kafka", "my-pod", "Pod", null);

        assertEquals(1, result.events().size());
    }

    @SuppressWarnings("unchecked")
    private void setupEventsResponse(final String namespace, final List<Event> events) {
        V1APIGroupDSL v1 = Mockito.mock(V1APIGroupDSL.class);
        when(kubernetesClient.v1()).thenReturn(v1);

        MixedOperation<Event, EventList, Resource<Event>> eventOp =
            Mockito.mock(MixedOperation.class);
        when(v1.events()).thenReturn(eventOp);

        NonNamespaceOperation<Event, EventList, Resource<Event>> nsEventOp =
            Mockito.mock(NonNamespaceOperation.class);
        when(eventOp.inNamespace(namespace)).thenReturn(nsEventOp);

        FilterWatchListDeletable<Event, EventList, Resource<Event>> fieldEventOp =
            Mockito.mock(FilterWatchListDeletable.class);
        when(nsEventOp.withField(anyString(), anyString())).thenReturn(fieldEventOp);
        when(fieldEventOp.withField(anyString(), anyString())).thenReturn(fieldEventOp);

        EventList eventList = new EventList();
        eventList.setItems(events);
        when(fieldEventOp.list()).thenReturn(eventList);
    }
}
