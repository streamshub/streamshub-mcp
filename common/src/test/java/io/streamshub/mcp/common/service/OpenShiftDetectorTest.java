/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link OpenShiftDetector}.
 */
@QuarkusTest
class OpenShiftDetectorTest {

    @InjectMock
    KubernetesClient kubernetesClient;

    @Inject
    OpenShiftDetector detector;

    OpenShiftDetectorTest() {
    }

    @BeforeEach
    void setUp() {
        detector.resetCache();
    }

    @Test
    void testIsOpenShiftTrueWhenBuildApiSupported() {
        when(kubernetesClient.supports("build.openshift.io/v1", "Build")).thenReturn(true);
        assertTrue(detector.isOpenShift());
    }

    @Test
    void testIsOpenShiftFalseWhenBuildApiAbsent() {
        when(kubernetesClient.supports("build.openshift.io/v1", "Build")).thenReturn(false);
        assertFalse(detector.isOpenShift());
    }

    @Test
    void testIsOpenShiftFalseAndRetriesAfterProbeException() {
        when(kubernetesClient.supports("build.openshift.io/v1", "Build"))
            .thenThrow(new RuntimeException("discovery unavailable"));
        assertFalse(detector.isOpenShift());
        assertFalse(detector.isOpenShift());
        // A failed probe is not memoized, so each call retries the discovery API.
        verify(kubernetesClient, times(2)).supports("build.openshift.io/v1", "Build");
    }

    @Test
    void testIsOpenShiftRecoversAfterTransientProbeFailure() {
        when(kubernetesClient.supports("build.openshift.io/v1", "Build"))
            .thenThrow(new RuntimeException("discovery unavailable"))
            .thenReturn(true);
        assertFalse(detector.isOpenShift()); // first probe fails, not memoized
        assertTrue(detector.isOpenShift());  // retry succeeds and is memoized
        assertTrue(detector.isOpenShift());  // served from cache, no further probe
        verify(kubernetesClient, times(2)).supports("build.openshift.io/v1", "Build");
    }
}
