/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service.log;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ContainerResource;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.dsl.PrettyLoggable;
import io.fabric8.kubernetes.client.dsl.TailPrettyLoggable;
import io.fabric8.kubernetes.client.dsl.TimeTailPrettyLoggable;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.common.dto.LogFetchRequest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link KubernetesLogProvider} verifying Kubernetes API interactions
 * for pod log fetching with different parameter combinations.
 */
@QuarkusTest
class KubernetesLogProviderTest {

    private static final String NAMESPACE = "kafka";
    private static final String POD_NAME = "my-cluster-kafka-0";
    private static final int TAIL_LINES = 100;

    @InjectMock
    KubernetesClient kubernetesClient;

    @Inject
    KubernetesLogProvider kubernetesLogCollectorProvider;

    @SuppressWarnings("unchecked")
    private MixedOperation<Pod, PodList, PodResource> podOp;
    private PodResource podResource;

    KubernetesLogProviderTest() {
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        podOp = Mockito.mock(MixedOperation.class);
        podResource = Mockito.mock(PodResource.class);

        when(kubernetesClient.pods()).thenReturn(podOp);
        when(podOp.inNamespace(NAMESPACE)).thenReturn(podOp);
        when(podOp.withName(POD_NAME)).thenReturn(podResource);
    }

    @Test
    void testFetchLogsBasic() {
        PrettyLoggable prettyLoggable = Mockito.mock(PrettyLoggable.class);
        Mockito.doReturn(prettyLoggable).when(podResource).tailingLines(TAIL_LINES + 1);
        when(prettyLoggable.getLog()).thenReturn("line1\nline2\n");

        String result = kubernetesLogCollectorProvider.fetchLogs(
            new LogFetchRequest(NAMESPACE, POD_NAME, TAIL_LINES, null, null, null, null, null, null, null));

        assertEquals("line1\nline2\n", result);
    }

    @Test
    void testFetchLogsWithSinceSeconds() {
        TailPrettyLoggable sinceLoggable = Mockito.mock(TailPrettyLoggable.class);
        PrettyLoggable prettyLoggable = Mockito.mock(PrettyLoggable.class);
        Mockito.doReturn(sinceLoggable).when(podResource).sinceSeconds(300);
        when(sinceLoggable.tailingLines(TAIL_LINES + 1)).thenReturn(prettyLoggable);
        when(prettyLoggable.getLog()).thenReturn("recent log\n");

        String result = kubernetesLogCollectorProvider.fetchLogs(
            new LogFetchRequest(NAMESPACE, POD_NAME, TAIL_LINES, 300, null, null, null, null, null, null));

        assertEquals("recent log\n", result);
    }

    @Test
    void testFetchLogsWithPrevious() {
        TimeTailPrettyLoggable terminatedLoggable = Mockito.mock(TimeTailPrettyLoggable.class);
        PrettyLoggable prettyLoggable = Mockito.mock(PrettyLoggable.class);
        Mockito.doReturn(terminatedLoggable).when(podResource).terminated();
        Mockito.doReturn(prettyLoggable).when(terminatedLoggable).tailingLines(TAIL_LINES + 1);
        when(prettyLoggable.getLog()).thenReturn("previous container log\n");

        String result = kubernetesLogCollectorProvider.fetchLogs(
            new LogFetchRequest(NAMESPACE, POD_NAME, TAIL_LINES, null, true, null, null, null, null, null));

        assertEquals("previous container log\n", result);
        verify(podResource).terminated();
    }

    @Test
    void testFetchLogsPreviousTakesPrecedenceOverSinceSeconds() {
        TimeTailPrettyLoggable terminatedLoggable = Mockito.mock(TimeTailPrettyLoggable.class);
        PrettyLoggable prettyLoggable = Mockito.mock(PrettyLoggable.class);
        Mockito.doReturn(terminatedLoggable).when(podResource).terminated();
        Mockito.doReturn(prettyLoggable).when(terminatedLoggable).tailingLines(TAIL_LINES + 1);
        when(prettyLoggable.getLog()).thenReturn("previous log\n");

        String result = kubernetesLogCollectorProvider.fetchLogs(
            new LogFetchRequest(NAMESPACE, POD_NAME, TAIL_LINES, 300, true, null, null, null, null, null));

        assertEquals("previous log\n", result);
        verify(podResource).terminated();
    }

    @Test
    void testFetchLogsReturnsNullWhenNoLogs() {
        PrettyLoggable prettyLoggable = Mockito.mock(PrettyLoggable.class);
        Mockito.doReturn(prettyLoggable).when(podResource).tailingLines(TAIL_LINES + 1);
        when(prettyLoggable.getLog()).thenReturn(null);

        String result = kubernetesLogCollectorProvider.fetchLogs(
            new LogFetchRequest(NAMESPACE, POD_NAME, TAIL_LINES, null, null, null, null, null, null, null));

        assertNull(result);
    }

    @Test
    void testFetchLogsNarrowsToSingleContainerAtFullTailLines() {
        ContainerResource containerResource = Mockito.mock(ContainerResource.class);
        PrettyLoggable prettyLoggable = Mockito.mock(PrettyLoggable.class);
        Mockito.doReturn(containerResource).when(podResource).inContainer("kafka");
        Mockito.doReturn(prettyLoggable).when(containerResource).tailingLines(TAIL_LINES + 1);
        when(prettyLoggable.getLog()).thenReturn("broker log\n");

        String result = kubernetesLogCollectorProvider.fetchLogs(
            new LogFetchRequest(NAMESPACE, POD_NAME, TAIL_LINES, null, null, null, null, null, null, "kafka"));

        assertEquals("broker log\n", result);
        verify(podResource).inContainer("kafka");
        verify(podResource, never()).get(); // container discovery is skipped
        verify(containerResource).tailingLines(TAIL_LINES + 1); // full tailLines, no split
    }

    @Test
    void testFetchLogsTrimsContainerName() {
        ContainerResource containerResource = Mockito.mock(ContainerResource.class);
        PrettyLoggable prettyLoggable = Mockito.mock(PrettyLoggable.class);
        Mockito.doReturn(containerResource).when(podResource).inContainer("kafka");
        Mockito.doReturn(prettyLoggable).when(containerResource).tailingLines(TAIL_LINES + 1);
        when(prettyLoggable.getLog()).thenReturn("trimmed container log\n");

        String result = kubernetesLogCollectorProvider.fetchLogs(
            new LogFetchRequest(NAMESPACE, POD_NAME, TAIL_LINES, null, null, null, null, null, null, " kafka "));

        assertEquals("trimmed container log\n", result);
        verify(podResource).inContainer("kafka");
    }

    @Test
    void testFetchLogsWithContainerAndPrevious() {
        ContainerResource containerResource = Mockito.mock(ContainerResource.class);
        TimeTailPrettyLoggable terminatedLoggable = Mockito.mock(TimeTailPrettyLoggable.class);
        PrettyLoggable prettyLoggable = Mockito.mock(PrettyLoggable.class);
        Mockito.doReturn(containerResource).when(podResource).inContainer("kafka");
        Mockito.doReturn(terminatedLoggable).when(containerResource).terminated();
        Mockito.doReturn(prettyLoggable).when(terminatedLoggable).tailingLines(TAIL_LINES + 1);
        when(prettyLoggable.getLog()).thenReturn("previous broker log\n");

        String result = kubernetesLogCollectorProvider.fetchLogs(
            new LogFetchRequest(NAMESPACE, POD_NAME, TAIL_LINES, null, true, null, null, null, null, "kafka"));

        assertEquals("previous broker log\n", result);
        verify(containerResource).terminated();
    }

    @Test
    void testBlankContainerIsTreatedAsAllContainers() {
        PrettyLoggable prettyLoggable = Mockito.mock(PrettyLoggable.class);
        Mockito.doReturn(prettyLoggable).when(podResource).tailingLines(TAIL_LINES + 1);
        when(prettyLoggable.getLog()).thenReturn("all containers log\n");

        String result = kubernetesLogCollectorProvider.fetchLogs(
            new LogFetchRequest(NAMESPACE, POD_NAME, TAIL_LINES, null, null, null, null, null, null, "   "));

        assertEquals("all containers log\n", result);
        verify(podResource, never()).inContainer("   ");
    }
}
