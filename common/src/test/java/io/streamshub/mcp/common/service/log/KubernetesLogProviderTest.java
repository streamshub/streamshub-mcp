/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.common.service.log;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.dsl.PrettyLoggable;
import io.fabric8.kubernetes.client.dsl.TailPrettyLoggable;
import io.fabric8.kubernetes.client.dsl.TimeTailPrettyLoggable;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

        String result = kubernetesLogCollectorProvider.fetchLogs(NAMESPACE, POD_NAME, TAIL_LINES, null, null);

        assertEquals("line1\nline2\n", result);
    }

    @Test
    void testFetchLogsWithSinceSeconds() {
        TailPrettyLoggable sinceLoggable = Mockito.mock(TailPrettyLoggable.class);
        PrettyLoggable prettyLoggable = Mockito.mock(PrettyLoggable.class);
        Mockito.doReturn(sinceLoggable).when(podResource).sinceSeconds(300);
        when(sinceLoggable.tailingLines(TAIL_LINES + 1)).thenReturn(prettyLoggable);
        when(prettyLoggable.getLog()).thenReturn("recent log\n");

        String result = kubernetesLogCollectorProvider.fetchLogs(NAMESPACE, POD_NAME, TAIL_LINES, 300, null);

        assertEquals("recent log\n", result);
    }

    @Test
    void testFetchLogsWithPrevious() {
        TimeTailPrettyLoggable terminatedLoggable = Mockito.mock(TimeTailPrettyLoggable.class);
        PrettyLoggable prettyLoggable = Mockito.mock(PrettyLoggable.class);
        Mockito.doReturn(terminatedLoggable).when(podResource).terminated();
        Mockito.doReturn(prettyLoggable).when(terminatedLoggable).tailingLines(TAIL_LINES + 1);
        when(prettyLoggable.getLog()).thenReturn("previous container log\n");

        String result = kubernetesLogCollectorProvider.fetchLogs(NAMESPACE, POD_NAME, TAIL_LINES, null, true);

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

        String result = kubernetesLogCollectorProvider.fetchLogs(NAMESPACE, POD_NAME, TAIL_LINES, 300, true);

        assertEquals("previous log\n", result);
        verify(podResource).terminated();
    }

    @Test
    void testFetchLogsReturnsNullWhenNoLogs() {
        PrettyLoggable prettyLoggable = Mockito.mock(PrettyLoggable.class);
        Mockito.doReturn(prettyLoggable).when(podResource).tailingLines(TAIL_LINES + 1);
        when(prettyLoggable.getLog()).thenReturn(null);

        String result = kubernetesLogCollectorProvider.fetchLogs(NAMESPACE, POD_NAME, TAIL_LINES, null, null);

        assertNull(result);
    }
}
