/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.readiness;

import io.streamshub.mcp.strimzi.resource.ResourceSubscriptionManager;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

import java.util.Map;

/**
 * Readiness health check that reports the health of Kubernetes resource watches.
 * Reports DOWN when any watch has exhausted its reconnection attempts.
 */
@Readiness
@ApplicationScoped
public class WatchHealthReadinessCheck implements HealthCheck {

    @Inject
    ResourceSubscriptionManager subscriptionManager;

    WatchHealthReadinessCheck() {
    }

    /**
     * Check whether all resource watches are healthy.
     * Reports UP if all watches are connected or watches are disabled.
     * Reports DOWN with details of failed watches otherwise.
     *
     * @return health check response
     */
    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder builder = HealthCheckResponse.named("watch-health");

        if (subscriptionManager.areWatchesHealthy()) {
            return builder.up().build();
        }

        builder.down();
        for (Map.Entry<String, Boolean> entry : subscriptionManager.getWatchHealthState().entrySet()) {
            builder.withData(entry.getKey(), Boolean.TRUE.equals(entry.getValue()) ? "connected" : "disconnected");
        }
        return builder.build();
    }
}
