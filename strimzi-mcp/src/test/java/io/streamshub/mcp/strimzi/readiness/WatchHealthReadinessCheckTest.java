/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.readiness;

import io.streamshub.mcp.strimzi.resource.ResourceSubscriptionManager;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.eclipse.microprofile.health.HealthCheckResponse.Status.DOWN;
import static org.eclipse.microprofile.health.HealthCheckResponse.Status.UP;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
/**
 * Unit tests for {@link WatchHealthReadinessCheck}.
 */
@ExtendWith(MockitoExtension.class)
class WatchHealthReadinessCheckTest {

    WatchHealthReadinessCheckTest() {
        // default constructor for checkstyle
    }

    @Mock
    ResourceSubscriptionManager subscriptionManager;

    @InjectMocks
    WatchHealthReadinessCheck healthCheck;

    @BeforeEach
    void setUp() {
        healthCheck = new WatchHealthReadinessCheck();
        try {
            java.lang.reflect.Field field =
                WatchHealthReadinessCheck.class.getDeclaredField("subscriptionManager");
            field.setAccessible(true);
            field.set(healthCheck, subscriptionManager);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void reportsUpWhenAllWatchesHealthy() {
        when(subscriptionManager.areWatchesHealthy()).thenReturn(true);

        HealthCheckResponse response = healthCheck.call();

        assertEquals(UP, response.getStatus());
        assertEquals("watch-health", response.getName());
    }

    @Test
    void reportsDownWhenWatchFailed() {
        when(subscriptionManager.areWatchesHealthy()).thenReturn(false);
        when(subscriptionManager.getWatchHealthState()).thenReturn(Map.of(
            "Kafka", true,
            "KafkaTopic", false,
            "Operator", true
        ));

        HealthCheckResponse response = healthCheck.call();

        assertEquals(DOWN, response.getStatus());
        assertTrue(response.getData().isPresent());
        assertEquals("disconnected", response.getData().get().get("KafkaTopic"));
        assertEquals("connected", response.getData().get().get("Kafka"));
    }

    @Test
    void reportsUpWhenWatchesDisabled() {
        when(subscriptionManager.areWatchesHealthy()).thenReturn(true);

        HealthCheckResponse response = healthCheck.call();

        assertEquals(UP, response.getStatus());
    }
}
