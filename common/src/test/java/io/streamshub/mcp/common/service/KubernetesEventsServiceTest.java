/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

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

    @Inject
    ObjectMapper objectMapper;

    private static final DateTimeFormatter MICRO =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

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
        assertEquals(1, eventsService.getEventsForResource(
            "kafka", "my-pod", "Pod", Instant.now().minusSeconds(300)).events().size());
    }

    @Test
    void testEventTimeOnlyEventIsFilteredBySinceTime() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        setupEventsResponse("kafka", List.of(
            newStyle("FailedScheduling", now.minus(58, ChronoUnit.MINUTES), 6, now.minus(55, ChronoUnit.MINUTES))));

        ResourceEventsResult result = eventsService.getEventsForResource(
            "kafka", "my-pod", "Pod", now.minus(5, ChronoUnit.MINUTES));

        assertTrue(result.events().isEmpty());
    }

    @Test
    void testEventTimeOnlyEventFieldsAreMapped() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant eventTime = now.minus(58, ChronoUnit.MINUTES);
        Instant lastObserved = now.minus(55, ChronoUnit.MINUTES);
        setupEventsResponse("kafka", List.of(newStyle("FailedScheduling", eventTime, 6, lastObserved)));

        ResourceEventsResult.EventInfo info = eventsService.getEventsForResource(
            "kafka", "my-pod", "Pod", null).events().getFirst();

        assertEquals(6, info.count());
        assertEquals("default-scheduler", info.source());
        assertEquals(lastObserved.toString(), info.lastTime());
        assertEquals(eventTime.toString(), info.firstTime());
    }

    @Test
    void testSingletonNewStyleEventCountsOnce() throws Exception {
        Instant eventTime = Instant.now().truncatedTo(ChronoUnit.MICROS).minus(1, ChronoUnit.MINUTES);
        setupEventsResponse("kafka", List.of(newStyle("Scheduled", eventTime, null, null)));

        ResourceEventsResult.EventInfo info = eventsService.getEventsForResource(
            "kafka", "my-pod", "Pod", null).events().getFirst();

        assertEquals(1, info.count());
        assertEquals(eventTime.toString(), info.lastTime());
        assertEquals(eventTime.toString(), info.firstTime());
    }

    @Test
    void testSeriesEventKeptWhenLastObservedIsRecent() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant lastObserved = now.minus(2, ChronoUnit.MINUTES);
        setupEventsResponse("kafka", List.of(
            newStyle("FailedScheduling", now.minus(58, ChronoUnit.MINUTES), 6, lastObserved)));

        ResourceEventsResult result = eventsService.getEventsForResource(
            "kafka", "my-pod", "Pod", now.minus(5, ChronoUnit.MINUTES));

        assertEquals(1, result.events().size());
        assertEquals(lastObserved.toString(), result.events().getFirst().lastTime());
    }

    @Test
    void testMixedStylesSortByLastObservation() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant pulled = now.minus(10, ChronoUnit.MINUTES);
        setupEventsResponse("kafka", List.of(
            parseEvent("Untimed", null, "\"source\": {}"),
            oldStyle("Pulled", pulled, pulled, 1),
            newStyle("Scheduled", now.minus(1, ChronoUnit.MINUTES), null, null),
            newStyle("FailedScheduling", now.minus(58, ChronoUnit.MINUTES), 6, now.minus(30, ChronoUnit.MINUTES))));

        List<String> reasons = eventsService.getEventsForResource("kafka", "my-pod", "Pod", null)
            .events().stream().map(ResourceEventsResult.EventInfo::reason).toList();

        assertEquals(List.of("Scheduled", "Pulled", "FailedScheduling", "Untimed"), reasons);
    }

    @Test
    void testCoreV1EventKeepsSourceComponent() throws Exception {
        Instant now = Instant.now();
        setupEventsResponse("kafka", List.of(oldStyle("Pulled", now.minusSeconds(120), now.minusSeconds(60), 1)));

        ResourceEventsResult.EventInfo info = eventsService.getEventsForResource(
            "kafka", "my-pod", "Pod", null).events().getFirst();

        assertEquals("kubelet", info.source());
        assertEquals(1, info.count());
    }

    @Test
    void testLastTimestampWinsOverEventTime() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant hourAgo = now.minus(1, ChronoUnit.HOURS);
        setupEventsResponse("kafka", List.of(parseEvent("Killing", hourAgo,
            "\"lastTimestamp\": \"" + hourAgo.truncatedTo(ChronoUnit.SECONDS) + "\", "
                + "\"eventTime\": \"" + MICRO.format(now.minus(1, ChronoUnit.MINUTES)) + "\", "
                + "\"reportingComponent\": \"kubelet\", \"source\": {}")));

        ResourceEventsResult result = eventsService.getEventsForResource(
            "kafka", "my-pod", "Pod", now.minus(5, ChronoUnit.MINUTES));

        assertTrue(result.events().isEmpty());
    }

    @Test
    void testUnparseableLastTimestampFallsThrough() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant eventTime = now.minus(1, ChronoUnit.MINUTES);
        setupEventsResponse("kafka", List.of(parseEvent("Killing", null,
            "\"lastTimestamp\": \"not-a-time\", \"eventTime\": \"" + MICRO.format(eventTime) + "\"")));

        ResourceEventsResult result = eventsService.getEventsForResource(
            "kafka", "my-pod", "Pod", now.minus(5, ChronoUnit.MINUTES));

        assertEquals(1, result.events().size());
        assertEquals(eventTime.toString(), result.events().getFirst().lastTime());
    }

    @Test
    void testCreationTimestampIsLastResort() throws Exception {
        Instant now = Instant.now();
        setupEventsResponse("kafka", List.of(parseEvent("Created", now.minus(2, ChronoUnit.HOURS),
            "\"source\": {}")));

        ResourceEventsResult result = eventsService.getEventsForResource(
            "kafka", "my-pod", "Pod", now.minus(5, ChronoUnit.MINUTES));

        assertTrue(result.events().isEmpty());
    }

    /**
     * Deserialises a core/v1 Event as the API server returns it.
     *
     * @param reason  the event reason
     * @param created the metadata creation timestamp, or null to omit it
     * @param fields  the remaining top-level fields as a JSON fragment (times, source, series, count)
     * @return the event
     * @throws Exception if the JSON cannot be parsed
     */
    private Event parseEvent(final String reason, final Instant created, final String fields) throws Exception {
        String createdJson = created == null
            ? "" : ", \"creationTimestamp\": \"" + created.truncatedTo(ChronoUnit.SECONDS) + "\"";
        String json = """
            {"apiVersion": "v1", "kind": "Event",
             "metadata": {"name": "my-pod.%s", "namespace": "kafka"%s},
             "involvedObject": {"apiVersion": "v1", "kind": "Pod", "name": "my-pod", "namespace": "kafka"},
             "type": "Warning", "reason": "%s", "message": "Event %s",
             %s}
            """.formatted(reason.toLowerCase(Locale.ROOT), createdJson, reason, reason, fields);
        return objectMapper.readValue(json, Event.class);
    }

    /**
     * An events.k8s.io-style event (scheduler): only {@code eventTime}, optional {@code series},
     * {@code reportingComponent}, empty {@code source}, null first/last timestamps.
     */
    private Event newStyle(final String reason, final Instant eventTime, final Integer seriesCount,
                           final Instant lastObserved) throws Exception {
        String series = seriesCount == null ? ""
            : "\"series\": {\"count\": " + seriesCount + ", \"lastObservedTime\": \"" + MICRO.format(lastObserved) + "\"}, ";
        return parseEvent(reason, eventTime, "\"eventTime\": \"" + MICRO.format(eventTime) + "\", " + series
            + "\"reportingComponent\": \"default-scheduler\", \"reportingInstance\": \"default-scheduler-master-0\", "
            + "\"action\": \"Scheduling\", \"source\": {}, \"firstTimestamp\": null, \"lastTimestamp\": null");
    }

    /**
     * An old-style core/v1 event (kubelet): first/last timestamps, count, {@code source.component},
     * null {@code eventTime} and blank {@code reportingComponent}.
     */
    private Event oldStyle(final String reason, final Instant first, final Instant last, final int count)
        throws Exception {
        return parseEvent(reason, first, "\"source\": {\"component\": \"kubelet\", \"host\": \"worker-1\"}, "
            + "\"firstTimestamp\": \"" + first.truncatedTo(ChronoUnit.SECONDS) + "\", "
            + "\"lastTimestamp\": \"" + last.truncatedTo(ChronoUnit.SECONDS) + "\", \"count\": " + count + ", "
            + "\"eventTime\": null, \"reportingComponent\": \"\", \"reportingInstance\": \"\"");
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
