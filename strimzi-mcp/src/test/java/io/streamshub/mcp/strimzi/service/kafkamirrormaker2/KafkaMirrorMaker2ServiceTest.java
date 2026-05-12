/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service.kafkamirrormaker2;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.strimzi.dto.kafkamirrormaker2.KafkaMirrorMaker2Response;
import io.streamshub.mcp.strimzi.service.KubernetesMockHelper;
import io.strimzi.api.kafka.model.mirrormaker2.KafkaMirrorMaker2;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service-level tests for {@link KafkaMirrorMaker2Service} with mocked Kubernetes.
 */
@QuarkusTest
class KafkaMirrorMaker2ServiceTest {

    @InjectMock
    KubernetesClient kubernetesClient;

    @Inject
    KafkaMirrorMaker2Service mirrorMakerService;

    KafkaMirrorMaker2ServiceTest() {
    }

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, KafkaMirrorMaker2.class);
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, Pod.class);

        MixedOperation<Pod, PodList, PodResource> podOp = Mockito.mock(MixedOperation.class);
        Mockito.lenient().when(kubernetesClient.pods()).thenReturn(podOp);
    }

    /**
     * Verify list returns empty for various namespace combinations.
     *
     * @param namespace the namespace filter
     */
    @ParameterizedTest
    @CsvSource(value = {"kafka", ","})
    void testListMirrorMakersReturnsEmpty(final String namespace) {
        List<KafkaMirrorMaker2Response> result = mirrorMakerService.listMirrorMakers(namespace);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    /**
     * Verify get throws when MM2 name is null.
     */
    @Test
    void testGetMirrorMakerThrowsWhenNameNull() {
        ToolCallException ex = assertThrows(ToolCallException.class,
            () -> mirrorMakerService.getMirrorMaker("kafka", null));

        assertTrue(ex.getMessage().contains("required"));
    }

    /**
     * Verify get throws when MM2 is not found in namespace.
     */
    @Test
    void testGetMirrorMakerThrowsWhenNotFoundInNamespace() {
        ToolCallException ex = assertThrows(ToolCallException.class,
            () -> mirrorMakerService.getMirrorMaker("kafka", "nonexistent"));

        assertTrue(ex.getMessage().contains("not found"));
        assertTrue(ex.getMessage().contains("namespace"));
    }

    /**
     * Verify get throws when MM2 is not found in any namespace.
     */
    @Test
    void testGetMirrorMakerThrowsWhenNotFoundInAnyNamespace() {
        ToolCallException ex = assertThrows(ToolCallException.class,
            () -> mirrorMakerService.getMirrorMaker(null, "nonexistent"));

        assertTrue(ex.getMessage().contains("not found"));
        assertTrue(ex.getMessage().contains("any namespace"));
    }

    /**
     * Verify get throws for invalid K8s name format.
     */
    @Test
    void testGetMirrorMakerThrowsForInvalidName() {
        assertThrows(ToolCallException.class,
            () -> mirrorMakerService.getMirrorMaker("kafka", "INVALID_NAME"));
    }
}
