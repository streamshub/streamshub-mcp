/*
 * Copyright StreamsHub authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.streamshub.mcp.strimzi.service;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkiverse.mcp.server.ToolCallException;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.streamshub.mcp.strimzi.dto.KafkaUserResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Service-level tests for {@link KafkaUserService} with mocked Kubernetes.
 */
@QuarkusTest
class KafkaUserServiceTest {

    @InjectMock
    KubernetesClient kubernetesClient;

    @Inject
    KafkaUserService userService;

    KafkaUserServiceTest() {
    }

    /**
     * Verify list returns empty when no KafkaUsers exist.
     */
    @Test
    void testListUsersReturnsEmptyWhenNoneExist() {
        List<KafkaUserResponse> result = userService.listUsers("kafka", null);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    /**
     * Verify list returns empty when filtering by cluster and none match.
     */
    @Test
    void testListUsersFiltersByCluster() {
        List<KafkaUserResponse> result = userService.listUsers("kafka", "my-cluster");

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    /**
     * Verify list returns empty when searching all namespaces without cluster filter.
     */
    @Test
    void testListUsersAllNamespacesNoCLuster() {
        List<KafkaUserResponse> result = userService.listUsers(null, null);

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    /**
     * Verify list returns empty when searching all namespaces with cluster filter.
     */
    @Test
    void testListUsersAllNamespacesWithCluster() {
        List<KafkaUserResponse> result = userService.listUsers(null, "my-cluster");

        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    /**
     * Verify get throws when user name is null.
     */
    @Test
    void testGetUserThrowsWhenNameIsNull() {
        ToolCallException ex = assertThrows(ToolCallException.class,
            () -> userService.getUser("kafka", null));

        assertTrue(ex.getMessage().contains("required"));
    }

    /**
     * Verify get throws when user is not found in a specific namespace.
     */
    @Test
    void testGetUserThrowsWhenNotFoundInNamespace() {
        ToolCallException ex = assertThrows(ToolCallException.class,
            () -> userService.getUser("kafka", "nonexistent"));

        assertTrue(ex.getMessage().contains("not found"));
        assertTrue(ex.getMessage().contains("namespace"));
    }

    /**
     * Verify get throws when user is not found in any namespace.
     */
    @Test
    void testGetUserThrowsWhenNotFoundInAnyNamespace() {
        ToolCallException ex = assertThrows(ToolCallException.class,
            () -> userService.getUser(null, "nonexistent"));

        assertTrue(ex.getMessage().contains("not found"));
        assertTrue(ex.getMessage().contains("any namespace"));
    }
}
