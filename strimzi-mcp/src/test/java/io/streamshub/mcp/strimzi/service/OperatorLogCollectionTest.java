/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Pod;
import io.streamshub.mcp.common.dto.LogCollectionParams;
import io.streamshub.mcp.common.dto.PodLogsResult;
import io.streamshub.mcp.common.service.DeploymentService;
import io.streamshub.mcp.common.service.KubernetesResourceService;
import io.streamshub.mcp.common.service.log.LogCollectionService;
import io.streamshub.mcp.strimzi.config.StrimziConstants;
import io.streamshub.mcp.strimzi.dto.StrimziOperatorLogsResponse;
import io.strimzi.api.ResourceLabels;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for operator log collection via {@link StrimziOperatorService#getOperatorLogs}.
 * Verifies that the service correctly delegates to {@link LogCollectionService}
 * regardless of the underlying log provider.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OperatorLogCollectionTest {

    @Mock
    KubernetesResourceService k8sService;

    @Mock
    LogCollectionService logCollectionService;

    @Mock
    DeploymentService deploymentService;

    private StrimziOperatorService operatorService;

    OperatorLogCollectionTest() {
    }

    @BeforeEach
    void setUp() throws Exception {
        operatorService = new StrimziOperatorService();
        setField(operatorService, "k8sService", k8sService);
        setField(operatorService, "logCollectionService", logCollectionService);
        setField(operatorService, "deploymentService", deploymentService);
    }

    @Test
    void testEmptyPodsReturnsNotFound() {
        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("strimzi"),
            eq(ResourceLabels.STRIMZI_KIND_LABEL), eq(StrimziConstants.KindValues.CLUSTER_OPERATOR)))
            .thenReturn(List.of());

        LogCollectionParams options = LogCollectionParams.of(null, null, 200, null);

        StrimziOperatorLogsResponse response = operatorService.getOperatorLogs("strimzi", null, options);

        assertNotNull(response);
        assertTrue(response.message().contains("No Strimzi operator pods found"));
        verifyNoInteractions(logCollectionService);
    }

    @Test
    void testSuccessfulLogCollection() {
        Pod pod = createOperatorPod("strimzi-cluster-operator-abc123", "strimzi");

        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("strimzi"),
            eq(ResourceLabels.STRIMZI_KIND_LABEL), eq(StrimziConstants.KindValues.CLUSTER_OPERATOR)))
            .thenReturn(List.of(pod));

        PodLogsResult logsResult = new PodLogsResult(
            List.of("strimzi-cluster-operator-abc123"),
            "=== Pod: strimzi-cluster-operator-abc123 ===\nINFO Reconciliation complete\n",
            0, 5, 5, false);

        when(logCollectionService.collectLogs(eq("strimzi"), any(), any()))
            .thenReturn(logsResult);

        LogCollectionParams options = LogCollectionParams.of(null, null, 200, null);

        StrimziOperatorLogsResponse response = operatorService.getOperatorLogs("strimzi", null, options);

        assertNotNull(response);
        assertEquals("strimzi", response.namespace());
        assertFalse(response.hasErrors());
        assertEquals(5, response.logLines());
        assertTrue(response.logs().contains("Reconciliation complete"));

        verify(logCollectionService).collectLogs(eq("strimzi"), any(), any());
    }

    @Test
    void testLogCollectionWithErrors() {
        Pod pod = createOperatorPod("strimzi-cluster-operator-abc123", "strimzi");

        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("strimzi"),
            eq(ResourceLabels.STRIMZI_KIND_LABEL), eq(StrimziConstants.KindValues.CLUSTER_OPERATOR)))
            .thenReturn(List.of(pod));

        PodLogsResult logsResult = new PodLogsResult(
            List.of("strimzi-cluster-operator-abc123"),
            "ERROR Reconciliation #5 failed\n",
            3, 20, 3, false);

        when(logCollectionService.collectLogs(eq("strimzi"), any(), any()))
            .thenReturn(logsResult);

        LogCollectionParams options = LogCollectionParams.of("errors", null, 200, null);

        StrimziOperatorLogsResponse response = operatorService.getOperatorLogs("strimzi", null, options);

        assertTrue(response.hasErrors());
        assertEquals(3, response.errorCount());
    }

    @Test
    void testLogCollectionPassesOptionsThrough() {
        Pod pod = createOperatorPod("strimzi-cluster-operator-abc123", "strimzi");

        when(k8sService.queryResourcesByLabel(eq(Pod.class), eq("strimzi"),
            eq(ResourceLabels.STRIMZI_KIND_LABEL), eq(StrimziConstants.KindValues.CLUSTER_OPERATOR)))
            .thenReturn(List.of(pod));

        when(logCollectionService.collectLogs(any(), any(), any()))
            .thenReturn(new PodLogsResult(List.of(), "", 0, 0, 0, false));

        LogCollectionParams options = LogCollectionParams.builder(50)
            .filter("warnings")
            .sinceSeconds(600)
            .previous(true)
            .build();

        operatorService.getOperatorLogs("strimzi", null, options);

        verify(logCollectionService).collectLogs(eq("strimzi"), any(), eq(options));
    }

    private Pod createOperatorPod(final String name, final String namespace) {
        Pod pod = new Pod();
        ObjectMeta meta = new ObjectMeta();
        meta.setName(name);
        meta.setNamespace(namespace);
        meta.setLabels(Map.of(
            ResourceLabels.STRIMZI_KIND_LABEL, StrimziConstants.KindValues.CLUSTER_OPERATOR));
        pod.setMetadata(meta);
        return pod;
    }

    private static void setField(final Object target, final String fieldName,
                                  final Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
