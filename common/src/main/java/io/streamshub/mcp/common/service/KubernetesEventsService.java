/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service;

import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.MicroTime;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.streamshub.mcp.common.dto.ResourceEventsResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.List;

/**
 * Generic service for querying Kubernetes events by involved object.
 *
 * <p>Queries core v1 Events using field selectors on {@code involvedObject.name}
 * and {@code involvedObject.kind}. Results are filtered and sorted (most recent first)
 * by each event's last observation time. This works for events created through core/v1
 * (kubelet style: {@code lastTimestamp}, {@code count}, {@code source.component}) and through
 * {@code events.k8s.io/v1} (scheduler style: {@code eventTime}, {@code series},
 * {@code reportingComponent}).</p>
 */
@ApplicationScoped
public class KubernetesEventsService {

    private static final Logger LOG = Logger.getLogger(KubernetesEventsService.class);

    @Inject
    KubernetesClient kubernetesClient;

    KubernetesEventsService() {
    }

    /**
     * Get events for a specific Kubernetes resource.
     *
     * @param namespace    the namespace to query
     * @param resourceName the name of the involved resource
     * @param resourceKind the kind of the involved resource (e.g., Pod, Kafka)
     * @param sinceTime    only return events last observed after this time, or null for all;
     *                     events with no parseable time are always kept
     * @return events grouped under the resource identity
     */
    public ResourceEventsResult getEventsForResource(final String namespace,
                                                      final String resourceName,
                                                      final String resourceKind,
                                                      final Instant sinceTime) {
        LOG.debugf("Querying events for %s/%s in namespace %s", resourceKind, resourceName, namespace);

        List<Event> events = kubernetesClient.v1().events()
            .inNamespace(namespace)
            .withField("involvedObject.name", resourceName)
            .withField("involvedObject.kind", resourceKind)
            .list()
            .getItems();

        List<ResourceEventsResult.EventInfo> eventInfos = events.stream()
            .filter(event -> isWithin(event, sinceTime))
            .sorted(Comparator.comparing(KubernetesEventsService::lastObserved,
                Comparator.nullsFirst(Comparator.naturalOrder())).reversed())
            .map(KubernetesEventsService::toEventInfo)
            .toList();

        return new ResourceEventsResult(resourceKind, resourceName, eventInfos);
    }

    /**
     * When the event was last observed: the first value that parses among {@code lastTimestamp},
     * {@code series.lastObservedTime}, {@code eventTime}, {@code firstTimestamp} and
     * {@code metadata.creationTimestamp}.
     *
     * @param event the event
     * @return the last observation time, or null if none parses
     */
    static Instant lastObserved(final Event event) {
        Instant time = parseTimestamp(event.getLastTimestamp());
        if (time == null && event.getSeries() != null) {
            time = parseMicroTime(event.getSeries().getLastObservedTime());
        }
        if (time == null) {
            time = parseMicroTime(event.getEventTime());
        }
        if (time == null) {
            time = parseTimestamp(event.getFirstTimestamp());
        }
        if (time == null && event.getMetadata() != null) {
            time = parseTimestamp(event.getMetadata().getCreationTimestamp());
        }
        return time;
    }

    /**
     * When the event was first observed: {@code firstTimestamp}, else {@code eventTime}.
     *
     * @param event the event
     * @return the first observation time, or null if neither parses
     */
    static Instant firstObserved(final Event event) {
        Instant time = parseTimestamp(event.getFirstTimestamp());
        return time != null ? time : parseMicroTime(event.getEventTime());
    }

    /**
     * How often the event occurred: {@code count} when positive, else {@code series.count};
     * a new-style event without a series happened once.
     *
     * @param event the event
     * @return the number of occurrences, or null if unknown
     */
    static Integer occurrences(final Event event) {
        if (event.getCount() != null && event.getCount() > 0) {
            return event.getCount();
        }
        if (event.getSeries() != null && event.getSeries().getCount() != null) {
            return event.getSeries().getCount();
        }
        return event.getEventTime() != null ? Integer.valueOf(1) : null;
    }

    /**
     * The component that reported the event: {@code source.component}, else {@code reportingComponent}.
     * Blank values are skipped (old-style events carry {@code reportingComponent: ""}).
     *
     * @param event the event
     * @return the reporting component, or null if none is set
     */
    static String reporter(final Event event) {
        if (event.getSource() != null && event.getSource().getComponent() != null
            && !event.getSource().getComponent().isBlank()) {
            return event.getSource().getComponent();
        }
        String reporting = event.getReportingComponent();
        return reporting != null && !reporting.isBlank() ? reporting : null;
    }

    private static ResourceEventsResult.EventInfo toEventInfo(final Event event) {
        Instant first = firstObserved(event);
        Instant last = lastObserved(event);
        return new ResourceEventsResult.EventInfo(
            event.getType(),
            event.getReason(),
            event.getMessage(),
            occurrences(event),
            first != null ? first.toString() : null,
            last != null ? last.toString() : null,
            reporter(event)
        );
    }

    private static boolean isWithin(final Event event, final Instant sinceTime) {
        if (sinceTime == null) {
            return true;
        }
        Instant last = lastObserved(event);
        // Keep events with no parseable time rather than silently dropping them
        return last == null || last.isAfter(sinceTime);
    }

    private static Instant parseMicroTime(final MicroTime microTime) {
        return microTime != null ? parseTimestamp(microTime.getTime()) : null;
    }

    private static Instant parseTimestamp(final String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(timestamp);
        } catch (DateTimeParseException e) {
            LOG.debugf("Could not parse event timestamp '%s': %s", timestamp, e.getMessage());
            return null;
        }
    }
}
