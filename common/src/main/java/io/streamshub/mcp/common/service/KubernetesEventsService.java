/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service;

import io.fabric8.kubernetes.api.model.Event;
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
 * and {@code involvedObject.kind}. Results are filtered by time and sorted
 * by last timestamp descending (most recent first).</p>
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
     * @param sinceTime    only return events after this time, or null for all
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
            .filter(event -> isAfter(event, sinceTime))
            .sorted(Comparator.comparing(
                (Event e) -> parseTimestamp(e.getLastTimestamp())).reversed())
            .map(this::toEventInfo)
            .toList();

        return new ResourceEventsResult(resourceKind, resourceName, eventInfos);
    }

    private ResourceEventsResult.EventInfo toEventInfo(final Event event) {
        String source = null;
        if (event.getSource() != null && event.getSource().getComponent() != null) {
            source = event.getSource().getComponent();
        }
        return new ResourceEventsResult.EventInfo(
            event.getType(),
            event.getReason(),
            event.getMessage(),
            event.getCount(),
            event.getFirstTimestamp(),
            event.getLastTimestamp(),
            source
        );
    }

    private boolean isAfter(final Event event, final Instant sinceTime) {
        if (sinceTime == null) {
            return true;
        }
        Instant eventTime = parseTimestamp(event.getLastTimestamp());
        return eventTime.isAfter(sinceTime);
    }

    private Instant parseTimestamp(final String timestamp) {
        if (timestamp == null || timestamp.isEmpty()) {
            return Instant.EPOCH;
        }
        try {
            return Instant.parse(timestamp);
        } catch (DateTimeParseException e) {
            LOG.debugf("Could not parse event timestamp '%s': %s", timestamp, e.getMessage());
            return Instant.EPOCH;
        }
    }
}
