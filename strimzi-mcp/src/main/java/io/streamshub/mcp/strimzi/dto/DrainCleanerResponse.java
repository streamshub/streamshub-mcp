/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Strimzi Drain Cleaner deployment information.
 *
 * @param name              the deployment name
 * @param namespace         the Kubernetes namespace
 * @param ready             whether the deployment is ready and healthy
 * @param replicas          the desired number of replicas
 * @param readyReplicas     the number of ready replicas
 * @param version           the drain cleaner version (extracted from image)
 * @param image             the container image
 * @param uptimeHours       the uptime in hours
 * @param status            the overall status (e.g., 'HEALTHY', 'DEGRADED')
 * @param mode              the operating mode ('standard' or 'legacy')
 * @param webhookConfigured whether the ValidatingWebhookConfiguration exists
 * @param failurePolicy     the webhook failure policy ('Ignore' or 'Fail')
 * @param denyEviction      whether eviction denial is enabled
 * @param drainKafka        whether Kafka pod draining is enabled
 * @param watchedNamespaces the namespaces monitored by the drain cleaner
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DrainCleanerResponse(
    @JsonProperty("name") String name,
    @JsonProperty("namespace") String namespace,
    @JsonProperty("ready") boolean ready,
    @JsonProperty("replicas") Integer replicas,
    @JsonProperty("ready_replicas") Integer readyReplicas,
    @JsonProperty("version") String version,
    @JsonProperty("image") String image,
    @JsonProperty("uptime_hours") String uptimeHours,
    @JsonProperty("status") String status,
    @JsonProperty("mode") String mode,
    @JsonProperty("webhook_configured") Boolean webhookConfigured,
    @JsonProperty("failure_policy") String failurePolicy,
    @JsonProperty("deny_eviction") Boolean denyEviction,
    @JsonProperty("drain_kafka") Boolean drainKafka,
    @JsonProperty("watched_namespaces") String watchedNamespaces
) {

    /**
     * Creates a drain cleaner response with full deployment details.
     *
     * @param name              the deployment name
     * @param namespace         the namespace
     * @param ready             whether the deployment is ready
     * @param replicas          the desired replicas
     * @param readyReplicas     the ready replicas
     * @param version           the version
     * @param image             the container image
     * @param uptimeHours       the uptime in hours
     * @param status            the overall status
     * @param mode              the operating mode
     * @param webhookConfigured whether the webhook is configured
     * @param failurePolicy     the webhook failure policy
     * @param denyEviction      whether eviction denial is enabled
     * @param drainKafka        whether Kafka pod draining is enabled
     * @param watchedNamespaces the watched namespaces
     * @return a new DrainCleanerResponse
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static DrainCleanerResponse of(String name, String namespace, boolean ready,
                                          Integer replicas, Integer readyReplicas,
                                          String version, String image, String uptimeHours,
                                          String status, String mode,
                                          Boolean webhookConfigured, String failurePolicy,
                                          Boolean denyEviction, Boolean drainKafka,
                                          String watchedNamespaces) {
        return new DrainCleanerResponse(name, namespace, ready, replicas, readyReplicas,
            version, image, uptimeHours, status, mode,
            webhookConfigured, failurePolicy, denyEviction, drainKafka, watchedNamespaces);
    }

    /**
     * Returns a human-readable display name.
     *
     * @return the display name
     */
    public String getDisplayName() {
        return String.format("%s (namespace: %s)", name, namespace);
    }
}
