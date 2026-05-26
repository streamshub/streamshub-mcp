/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.strimzi.dto.kafkarebalance.KafkaRebalanceResponse;
import io.streamshub.mcp.strimzi.service.kafkarebalance.KafkaRebalanceService;
import io.strimzi.api.kafka.model.rebalance.KafkaRebalance;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
/**
 * Service-level tests for {@link KafkaRebalanceService} with mocked Kubernetes.
 */
@QuarkusTest
class KafkaRebalanceServiceTest {

    @InjectMock
    KubernetesClient kubernetesClient;

    @Inject
    KafkaRebalanceService rebalanceService;

    KafkaRebalanceServiceTest() {
    }

    @BeforeEach
    void setUp() {
        KubernetesMockHelper.setupEmptyResourceQuery(kubernetesClient, KafkaRebalance.class);
    }

    /**
     * Verify list returns empty for various namespace/cluster filter combinations.
     *
     * @param namespace   the namespace filter
     * @param clusterName the cluster name filter
     */
    @ParameterizedTest
    @CsvSource(value = {
        "kafka,",
        "kafka,my-cluster",
        ",",
        ",my-cluster"
    })
    void testListRebalancesReturnsEmpty(final String namespace, final String clusterName) {
        List<KafkaRebalanceResponse> result = rebalanceService.listRebalances(
            namespace, clusterName);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    /**
     * Verify get throws when rebalance name is null.
     */
    @Test
    void testGetRebalanceThrowsWhenNameNull() {
        ToolCallException ex = assertThrows(ToolCallException.class,
            () -> rebalanceService.getRebalance("kafka", null));

        assertTrue(ex.getMessage().contains("required"));
    }

    /**
     * Verify get throws when rebalance is not found in namespace.
     */
    @Test
    void testGetRebalanceThrowsWhenNotFoundInNamespace() {
        ToolCallException ex = assertThrows(ToolCallException.class,
            () -> rebalanceService.getRebalance("kafka", "nonexistent"));

        assertTrue(ex.getMessage().contains("not found"));
        assertTrue(ex.getMessage().contains("namespace"));
    }

    /**
     * Verify get throws when rebalance is not found in any namespace.
     */
    @Test
    void testGetRebalanceThrowsWhenNotFoundInAnyNamespace() {
        ToolCallException ex = assertThrows(ToolCallException.class,
            () -> rebalanceService.getRebalance(null, "nonexistent"));

        assertTrue(ex.getMessage().contains("not found"));
        assertTrue(ex.getMessage().contains("any namespace"));
    }

    /**
     * Verify active rebalance states are correctly identified.
     *
     * @param state the rebalance state name
     */
    @ParameterizedTest
    @ValueSource(strings = {"New", "PendingProposal", "ProposalReady", "Rebalancing", "Stopped"})
    void testIsActiveRebalanceStateTrue(final String state) {
        assertTrue(KafkaRebalanceService.isActiveRebalanceState(state));
    }

    /**
     * Verify terminal and unknown rebalance states are not active.
     *
     * @param state the rebalance state name
     */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Ready", "NotReady", "ReconciliationPaused", "Unknown"})
    void testIsActiveRebalanceStateFalse(final String state) {
        assertFalse(KafkaRebalanceService.isActiveRebalanceState(state));
    }

    /**
     * Verify get throws for invalid K8s name format.
     */
    @Test
    void testGetRebalanceThrowsForInvalidName() {
        assertThrows(ToolCallException.class,
            () -> rebalanceService.getRebalance("kafka", "INVALID_NAME"));
    }
}
